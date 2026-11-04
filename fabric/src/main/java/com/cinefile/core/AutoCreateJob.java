package com.cinefile.core;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Fills empty replay time with verified camera clips, advanced one step per client tick. */
public final class AutoCreateJob {
    public record Result(int placed, int target, Map<Verdict, Integer> rejected, boolean cancelled, String error, double estimatedUsd) {}

    private enum State { START, PLANNING, AI_WAIT, PICK, VERIFY, REVIEW, AI_REVIEW }

    private record Variant(Trajectory subject, CameraClip clip, double mult) {}

    private record Window(int start, int end, double score, List<Variant> variants) {}

    static final int SLICE_TRIES = 6, FILL_MAX = 20, MAX_SHORTENS = 3;
    private static final int MIN_REVIEW_SCORE = 4;
    private static final double MIN_TRAVEL = 1.0;

    private final ReplayDriver d;
    private final Trajectory t;
    private final CineFileConfig cfg;
    private final ShotAi ai;
    private final int clipTicks, targetTicks, target;
    private final List<CameraClip> placed = new ArrayList<>();
    private final Map<Verdict, Integer> rejected = new EnumMap<>(Verdict.class);
    private volatile boolean cancelled;
    private boolean done;
    private Result result;
    private String error;
    private State state = State.START;
    private List<int[]> occupied;
    private CompletableFuture<List<Window>> planF;
    private CompletableFuture<Optional<List<Integer>>> aiF;
    private CompletableFuture<Optional<Double>> reviewF;
    private List<Window> ranked;
    private List<List<Integer>> slices;
    private final Set<Integer> tried = new HashSet<>();
    private double floor;
    private boolean inFill;
    private int sliceI, slicePos, sliceTried, fillPos, fillTried;
    // current attempt
    private Window cur;
    private int variant, revI, revPhase, settle, attempt;
    private Object handle;
    private ClipVerifier verifier;
    private CameraClip shortened;
    private int shortens;
    private List<byte[]> frames;
    private volatile byte[] png;
    private volatile boolean pngDone;

    public AutoCreateJob(ReplayDriver d, Trajectory t, CineFileConfig cfg, ShotAi aiOrNull) {
        this.d = d;
        this.t = t;
        Gson g = new Gson();
        this.cfg = g.fromJson(g.toJson(cfg), CineFileConfig.class); // snapshot: the user may edit settings mid-run
        this.ai = aiOrNull;
        clipTicks = Math.max(20, (int) Math.round(this.cfg.clipLengthS * 20));
        targetTicks = Math.max(clipTicks, (int) Math.round(this.cfg.durationS * 20));
        target = targetClips(this.cfg);
    }

    public static int targetClips(CineFileConfig cfg) {
        int clipTicks = Math.max(20, (int) Math.round(cfg.clipLengthS * 20));
        int targetTicks = Math.max(clipTicks, (int) Math.round(cfg.durationS * 20));
        return Math.max(1, targetTicks / clipTicks);
    }

    public void cancel() { cancelled = true; }
    public boolean isDone() { return done; }
    public Result result() { return result; }
    public int placed() { return placed.size(); }
    public int target() { return target; }

    private static boolean overlaps(int t0, int t1, int a, int b) {
        return !(t1 < a || t0 > b);
    }

    public void tick() {
        if (done) return;
        if (cancelled) {
            if (handle != null) d.discardCandidate(handle);
            handle = null;
            finish();
            return;
        }
        switch (state) {
            case START -> {
                occupied = d.occupiedRanges();
                double fov = d.fovDegrees();
                d.status("Planning clips...");
                List<int[]> occ = new ArrayList<>(occupied);
                planF = CompletableFuture.supplyAsync(() -> plan(occ, fov));
                state = State.PLANNING;
            }
            case PLANNING -> {
                if (!planF.isDone()) return;
                try {
                    ranked = planF.join();
                } catch (RuntimeException e) {
                    error = String.valueOf(e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                    finish();
                    return;
                }
                if (ranked.isEmpty()) {
                    error = occupied.isEmpty() ? "No valid clip windows found" : "No empty space left for new clips";
                    finish();
                    return;
                }
                if (ai != null && ai.enabled()) {
                    d.status("Asking AI to pick shots...");
                    aiF = ai.planClips(ranked.stream().map(w -> new Candidate(w.start(), w.end(), w.score())).toList(),
                        cfg.project, target, cfg.clipLengthS);
                    state = State.AI_WAIT;
                } else {
                    buildSlices(Optional.empty());
                }
            }
            case AI_WAIT -> {
                if (!aiF.isDone()) return;
                buildSlices(aiF.isCompletedExceptionally() ? Optional.empty() : aiF.join());
            }
            case PICK -> {
                cur = nextWindow();
                if (cur == null) {
                    finish();
                    return;
                }
                variant = 0;
                shortened = null;
                shortens = 0;
                startAttempt();
            }
            case VERIFY -> {
                Verdict v = verifier.step(d);
                if (v == null) return;
                if (v != Verdict.OK) reject(v);
                else if (cfg.visualReview && ai != null && ai.enabled()) {
                    frames = new ArrayList<>();
                    revI = 0;
                    revPhase = 0;
                    state = State.REVIEW;
                    d.status("Reviewing screenshots");
                } else commit(clip());
            }
            case REVIEW -> review();
            case AI_REVIEW -> {
                if (!reviewF.isDone()) return;
                Optional<Double> s = reviewF.isCompletedExceptionally() ? Optional.empty() : reviewF.join();
                if (s.isPresent() && s.get() < MIN_REVIEW_SCORE) reject(Verdict.AI_REJECTED);
                else commit(s.map(clip()::withScore).orElse(clip()));
            }
        }
    }

    private CameraClip clip() {
        return shortened != null ? shortened : cur.variants().get(variant).clip();
    }

    private List<Window> plan(List<int[]> occ, double fov) {
        List<Trajectory> subjects;
        if ("auto".equals(cfg.focusPlayer)) subjects = t.players();
        else {
            Trajectory named = t.player(cfg.focusPlayer);
            if (named == null) throw new IllegalArgumentException("Player " + cfg.focusPlayer + " isn't in this replay");
            subjects = List.of(named);
        }
        List<Style> styles = Styles.resolve(cfg.styleId);
        int perStyle = Styles.triesPerStyle(cfg.tries, styles.size());
        List<Window> out = new ArrayList<>();
        for (Trajectory subject : subjects) {
            for (Candidate c : Candidates.windows(subject, clipTicks, 500, cfg.rejectCuts, cfg.rejectDimensionChanges)) {
                if (occ.stream().anyMatch(o -> overlaps(c.start(), c.end(), o[0], o[1]))) continue;
                List<Variant> vs = new ArrayList<>();
                Set<CameraClip> seen = new HashSet<>();
                for (Style s : styles) {
                    for (int i = 0; i < perStyle; i++) {
                        for (FocusLevel level : cfg.focusLevel.candidates()) {
                            for (CameraClip k : CameraSynth.synthesizeAll(subject, c.start(), c.end(), Styles.variant(s, i, c.start()), 0, level, fov)) {
                                if (!seen.add(k)) continue;
                                FramingStats fs = Framing.stats(subject, k, 20, fov);
                                if (cfg.rejectOffscreenPlayer && fs.onscreenFrac() < 0.5) continue;
                                double mult = Framing.multiplier(fs) * (0.3 + 0.7 * Framing.sizeScore(subject, k, 20, fov)) * level.weight();
                                vs.add(new Variant(subject, k, mult));
                            }
                        }
                    }
                }
                if (vs.isEmpty()) continue;
                vs.sort(Comparator.comparingDouble(Variant::mult).reversed());
                out.add(new Window(c.start(), c.end(), c.score() * vs.get(0).mult(), vs));
            }
        }
        out.sort(Comparator.comparingDouble(Window::score).reversed());
        return out;
    }

    private void buildSlices(Optional<List<Integer>> picks) {
        double[] sc = ranked.stream().mapToDouble(Window::score).sorted().toArray();
        floor = Candidates.SPREAD_FLOOR_RATIO * sc[sc.length / 2];
        slices = Candidates.slices(ranked.stream().map(w -> new Candidate(w.start(), w.end(), w.score())).toList(), target);
        Set<Integer> picked = new HashSet<>(picks.orElse(List.of()));
        for (List<Integer> sl : slices) {
            List<Integer> front = new ArrayList<>(sl.stream().filter(picked::contains).toList());
            front.addAll(sl.stream().filter(x -> !picked.contains(x)).toList());
            sl.clear();
            sl.addAll(front);
        }
        state = State.PICK;
    }

    private boolean overlapsPlaced(Window w) {
        return placed.stream().anyMatch(p -> overlaps(w.start(), w.end(), p.startTick(), p.endTick()));
    }

    private Window nextWindow() {
        while (!inFill) {
            if (sliceI >= slices.size()) {
                inFill = true;
                break;
            }
            List<Integer> sl = slices.get(sliceI);
            while (slicePos < sl.size() && sliceTried < SLICE_TRIES) {
                int idx = sl.get(slicePos++);
                Window w = ranked.get(idx);
                if (w.score() < floor || overlapsPlaced(w)) continue;
                sliceTried++;
                tried.add(idx);
                return w;
            }
            nextSlice();
        }
        if ((long) placed.size() * clipTicks >= targetTicks) return null;
        while (fillPos < ranked.size() && fillTried < FILL_MAX) {
            int idx = fillPos++;
            if (tried.contains(idx) || overlapsPlaced(ranked.get(idx))) continue;
            fillTried++;
            return ranked.get(idx);
        }
        return null;
    }

    private void nextSlice() {
        sliceI++;
        slicePos = 0;
        sliceTried = 0;
    }

    private void startAttempt() {
        CameraClip k = clip();
        handle = d.showCandidate(k);
        verifier = new ClipVerifier(cur.variants().get(variant).subject(), k, handle, cfg);
        d.status("Checking " + (placed.size() + 1) + "/" + target + ": " + k.styleId());
        state = State.VERIFY;
    }

    private void reject(Verdict v) {
        d.discardCandidate(handle);
        handle = null;
        CameraClip next = v == Verdict.BLOCK_CLIP ? shortenedClip() : null;
        if (next != null) {
            shortened = next;
            shortens++;
            d.status("Shortening camera move");
            startAttempt();
            return;
        }
        shortened = null;
        shortens = 0;
        rejected.merge(v, 1, Integer::sum);
        d.status("Rejected: " + v);
        if (++variant < cur.variants().size()) startAttempt();
        else state = State.PICK;
    }

    /** Shorter copy of the current straight-dolly move, ending at the last safe sample; null if it can't shorten. */
    private CameraClip shortenedClip() {
        CameraClip k = clip();
        if (shortens >= MAX_SHORTENS || !Styles.STRAIGHT_DOLLY.equals(k.styleId())) return null;
        double[] safe = verifier.lastSafe();
        if (safe == null) return null;
        if (Math.hypot(safe[0] - k.a().x(), safe[2] - k.a().z()) < MIN_TRAVEL) return null;
        return k.withEnd(safe[0], safe[1], safe[2]);
    }

    private void commit(CameraClip k) {
        d.commitCandidate(handle, k);
        handle = null;
        placed.add(k);
        if (!inFill) nextSlice();
        state = State.PICK;
    }

    private void review() {
        CameraClip k = clip();
        int tick = new int[] {k.startTick(), (k.startTick() + k.endTick()) / 2, k.endTick()}[revI];
        switch (revPhase) {
            case 0 -> {
                d.seek(tick);
                revPhase = 1;
            }
            case 1 -> {
                if (d.atTick(tick)) {
                    revPhase = 2;
                    settle = 0;
                }
            }
            case 2 -> {
                if (++settle >= 2) {
                    pngDone = false;
                    png = null;
                    d.capture(b -> {
                        png = b;
                        pngDone = true;
                    });
                    revPhase = 3;
                }
            }
            default -> {
                if (!pngDone) return;
                if (png == null) {
                    commit(k); // a failed screenshot skips the review
                    return;
                }
                frames.add(png);
                revI++;
                revPhase = 0;
                if (revI == 3) {
                    reviewF = ai.reviewClip(frames, cfg.project);
                    state = State.AI_REVIEW;
                }
            }
        }
    }

    private void finish() {
        if (!cancelled && !placed.isEmpty()) {
            List<CameraClip> o = new ArrayList<>(placed);
            o.sort(Comparator.comparingInt(CameraClip::startTick));
            if (cfg.gapFill != CineFileConfig.GapFill.NONE) {
                for (int i = 0; i + 1 < o.size(); i++) {
                    int g0 = o.get(i).endTick(), g1 = o.get(i + 1).startTick();
                    if (g1 - g0 >= 5 && occupied.stream().noneMatch(r -> overlaps(g0, g1, r[0], r[1]))) d.addGap(g0, g1);
                }
            }
            int first = o.get(0).startTick();
            d.setExportRange(first, o.get(o.size() - 1).endTick());
            d.seek(first);
        }
        d.status(error != null ? error : "Placed " + placed.size() + "/" + target);
        result = new Result(placed.size(), target, Map.copyOf(rejected), cancelled, error, 0.0);
        done = true;
    }
}
