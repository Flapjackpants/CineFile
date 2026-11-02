package com.cinefile.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class AutoCreateJobTest {
    static final class FakeAi implements ShotAi {
        boolean enabled = true;
        CompletableFuture<Optional<List<Integer>>> plan = CompletableFuture.completedFuture(Optional.empty());
        Function<List<Candidate>, Optional<List<Integer>>> picker;
        Function<Integer, Double> score = n -> 8.0;
        volatile boolean planCalled;
        final List<Integer> frameCounts = new ArrayList<>();
        List<Candidate> seen;

        public boolean enabled() { return enabled; }

        public CompletableFuture<Optional<List<Integer>>> planClips(List<Candidate> ranked, String project, int target, double secs) {
            planCalled = true;
            seen = ranked;
            return picker != null ? CompletableFuture.completedFuture(picker.apply(ranked)) : plan;
        }

        public CompletableFuture<Optional<Double>> reviewClip(List<byte[]> frames, String project) {
            frameCounts.add(frames.size());
            return CompletableFuture.completedFuture(Optional.of(score.apply(frameCounts.size())));
        }
    }

    static Trajectory traj(int n) throws Exception {
        return Replays.trajectory(t -> new Pose(0.4 * t, 64, 3 * Math.sin(t / 10.0), (t * 7) % 90, 5), n);
    }

    static CineFileConfig cfg(double durationS) {
        CineFileConfig c = new CineFileConfig();
        c.durationS = durationS;
        return c;
    }

    static void pump(AutoCreateJob j) throws Exception {
        long end = System.currentTimeMillis() + 20000;
        while (!j.isDone()) {
            assertTrue(System.currentTimeMillis() < end, "job did not finish");
            j.tick();
            Thread.sleep(0, 200000);
        }
    }

    static AutoCreateJob run(FakeDriver d, Trajectory t, CineFileConfig c, ShotAi ai) throws Exception {
        AutoCreateJob j = new AutoCreateJob(d, t, c, ai);
        pump(j);
        return j;
    }

    static List<CameraClip> sorted(List<CameraClip> l) {
        List<CameraClip> s = new ArrayList<>(l);
        s.sort(Comparator.comparingInt(CameraClip::startTick));
        return s;
    }

    @Test
    void placesTargetClipsAcrossTimeline() throws Exception {
        FakeDriver d = new FakeDriver();
        AutoCreateJob j = run(d, traj(1000), cfg(30), null);
        AutoCreateJob.Result r = j.result();
        assertEquals(3, r.target());
        assertEquals(3, r.placed());
        assertEquals(3, j.placed());
        assertNull(r.error());
        assertFalse(r.cancelled());
        List<CameraClip> s = sorted(d.committed);
        for (int i = 1; i < s.size(); i++) assertTrue(s.get(i).startTick() > s.get(i - 1).endTick());
        assertTrue(s.get(0).startTick() < 340);
        assertTrue(s.get(2).endTick() > 660);
    }

    @Test
    void rejectedVariantIsDiscardedAndRetried() throws Exception {
        FakeDriver d = new FakeDriver();
        d.blocked = (a, b) -> d.shown.size() == 1;
        AutoCreateJob.Result r = run(d, traj(1000), cfg(10), null).result();
        assertEquals(1, r.placed());
        assertEquals(1, d.discarded.size());
        assertEquals(1, d.committed.size());
        assertEquals(1, r.rejected().get(Verdict.BLOCK_CLIP));
    }

    @Test
    void everyCandidateIsCommittedOrDiscarded() throws Exception {
        FakeDriver d = new FakeDriver();
        d.blocked = (a, b) -> d.shown.size() % 3 == 1;
        AutoCreateJob.Result r = run(d, traj(1000), cfg(30), null).result();
        assertTrue(d.discarded.size() > 0);
        assertEquals(d.shown.size(), d.committed.size() + d.discarded.size());
        assertEquals(d.discarded.size(), r.rejected().values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void skipsOccupiedRanges() throws Exception {
        FakeDriver d = new FakeDriver();
        d.occupied.add(new int[] {300, 500});
        run(d, traj(1000), cfg(20), null);
        assertFalse(d.committed.isEmpty());
        for (CameraClip c : d.committed) assertTrue(c.endTick() < 300 || c.startTick() > 500);
    }

    @Test
    void reportsWhenNothingCanBePlaced() throws Exception {
        FakeDriver d = new FakeDriver();
        d.occupied.add(new int[] {0, 5000});
        AutoCreateJob.Result r = run(d, traj(1000), cfg(30), null).result();
        assertEquals("No empty space left for new clips", r.error());
        assertEquals(0, r.placed());
        assertTrue(d.shown.isEmpty());

        FakeDriver d2 = new FakeDriver();
        r = run(d2, new Trajectory("u", "n", 0), cfg(30), null).result();
        assertEquals("No valid clip windows found", r.error());
        assertTrue(d2.shown.isEmpty());
    }

    @Test
    void exhaustedSliceFallsBackToFill() throws Exception {
        FakeDriver d = new FakeDriver();
        d.blocked = (a, b) -> d.cur < 350;
        AutoCreateJob j = run(d, traj(1200), cfg(30), null);
        assertTrue(d.shown.stream().anyMatch(c -> c.startTick() < 350));
        assertTrue(j.placed() >= 2);
        for (CameraClip c : d.committed) assertTrue(c.startTick() >= 350);
    }

    @Test
    void honoursStyleSetting() throws Exception {
        FakeDriver d = new FakeDriver();
        CineFileConfig c = cfg(30);
        c.styleId = "locked-dolly";
        run(d, traj(1000), c, null);
        assertFalse(d.committed.isEmpty());
        for (CameraClip k : d.committed) assertEquals("locked-dolly", k.styleId());
    }

    @Test
    void aiPlanPrefersPickedWindows() throws Exception {
        CineFileConfig c = cfg(30);
        FakeDriver base = new FakeDriver();
        run(base, traj(1000), c, null);
        assertTrue(base.committed.stream().noneMatch(k -> k.startTick() == 0));

        FakeAi ai = new FakeAi();
        ai.picker = ranked -> {
            int best = 0;
            for (int i = 1; i < ranked.size(); i++) if (ranked.get(i).start() < ranked.get(best).start()) best = i;
            return Optional.of(List.of(best));
        };
        FakeDriver d = new FakeDriver();
        run(d, traj(1000), c, ai);
        assertTrue(d.committed.stream().anyMatch(k -> k.startTick() == 0));
    }

    @Test
    void aiPlanEmptyFallsBack() throws Exception {
        FakeAi ai = new FakeAi();
        FakeDriver d = new FakeDriver();
        FakeDriver base = new FakeDriver();
        run(base, traj(1000), cfg(30), null);
        assertEquals(3, run(d, traj(1000), cfg(30), ai).placed());
        assertEquals(sorted(base.committed), sorted(d.committed));
    }

    @Test
    void visualReviewRejectsLowScores() throws Exception {
        FakeAi ai = new FakeAi();
        ai.score = n -> n == 1 ? 3.0 : 8.0;
        CineFileConfig c = cfg(10);
        c.visualReview = true;
        FakeDriver d = new FakeDriver();
        AutoCreateJob.Result r = run(d, traj(1000), c, ai).result();
        assertEquals(1, r.placed());
        assertEquals(1, r.rejected().get(Verdict.AI_REJECTED));
        assertEquals(1, d.discarded.size());
        assertEquals(List.of(3, 3), ai.frameCounts);
        assertEquals(6, d.captures);
        assertEquals(8.0, d.committed.get(0).score());
    }

    @Test
    void noCaptureWithoutVisualReview() throws Exception {
        FakeDriver d = new FakeDriver();
        run(d, traj(1000), cfg(10), new FakeAi());
        assertEquals(0, d.captures);
        assertEquals(1, d.committed.size());
    }

    @Test
    void failedCaptureSkipsReview() throws Exception {
        FakeAi ai = new FakeAi();
        CineFileConfig c = cfg(10);
        c.visualReview = true;
        FakeDriver d = new FakeDriver();
        d.png = n -> null;
        AutoCreateJob.Result r = run(d, traj(1000), c, ai).result();
        assertEquals(1, r.placed());
        assertTrue(ai.frameCounts.isEmpty());
        assertTrue(r.rejected().isEmpty());
    }

    @Test
    void tickNeverBlocksOnAi() throws Exception {
        FakeAi ai = new FakeAi();
        ai.plan = new CompletableFuture<>();
        FakeDriver d = new FakeDriver();
        AutoCreateJob j = new AutoCreateJob(d, traj(1000), cfg(30), ai);
        long end = System.currentTimeMillis() + 10000;
        while (!ai.planCalled) {
            assertTrue(System.currentTimeMillis() < end);
            j.tick();
            Thread.sleep(1);
        }
        for (int i = 0; i < 50; i++) j.tick();
        assertFalse(j.isDone());
        assertTrue(d.shown.isEmpty());
        ai.plan.complete(Optional.empty());
        pump(j);
        assertEquals(3, j.placed());
    }

    // 26-tick clips; a cut at `cut` splits the replay into one window each side
    private FakeDriver gapRun(int cut, int n, CineFileConfig.GapFill fill, int[] occupied) throws Exception {
        Trajectory t = Replays.trajectory(k -> new Pose(0.4 * k + (k >= cut ? 100 : 0), 64, 3 * Math.sin(k / 10.0), (k * 7) % 90, 5), n);
        CineFileConfig c = cfg(2.6);
        c.clipLengthS = 1.3;
        c.gapFill = fill;
        FakeDriver d = new FakeDriver();
        if (occupied != null) d.occupied.add(occupied);
        run(d, t, c, null);
        assertEquals(2, d.committed.size());
        return d;
    }

    @Test
    void gapRules() throws Exception {
        FakeDriver d = gapRun(35, 62, CineFileConfig.GapFill.CUT, null);
        assertEquals(1, d.gaps.size());
        assertArrayEquals(new int[] {26, 35}, d.gaps.get(0));
        assertTrue(gapRun(30, 60, CineFileConfig.GapFill.CUT, null).gaps.isEmpty()); // gap of 4
        assertTrue(gapRun(35, 62, CineFileConfig.GapFill.NONE, null).gaps.isEmpty());
        assertTrue(gapRun(35, 62, CineFileConfig.GapFill.CUT, new int[] {27, 34}).gaps.isEmpty());
    }

    @Test
    void setsExportRangeAtEnd() throws Exception {
        FakeDriver d = gapRun(35, 62, CineFileConfig.GapFill.CUT, null);
        assertEquals(1, d.exportRanges.size());
        List<CameraClip> s = sorted(d.committed);
        int first = s.get(0).startTick(), last = s.get(1).endTick();
        assertArrayEquals(new int[] {first, last}, d.exportRanges.get(0));
        int n = d.calls.size();
        assertEquals("export:" + first + "-" + last, d.calls.get(n - 2));
        assertEquals("seek:" + first, d.calls.get(n - 1));
    }

    @Test
    void cancelDiscardsTentativeOnly() throws Exception {
        FakeDriver d = new FakeDriver();
        AutoCreateJob j = new AutoCreateJob(d, traj(1000), cfg(30), null);
        long end = System.currentTimeMillis() + 10000;
        while (d.shown.size() < 2) {
            assertTrue(System.currentTimeMillis() < end);
            j.tick();
            Thread.sleep(0, 200000);
        }
        j.cancel();
        j.tick();
        assertTrue(j.isDone());
        assertTrue(j.result().cancelled());
        assertEquals(1, d.committed.size());
        assertEquals(List.of(d.shown.get(1)), d.discarded);
        assertTrue(d.gaps.isEmpty());
        assertTrue(d.exportRanges.isEmpty());
    }

    @Test
    void cancelDuringPlanning() throws Exception {
        FakeDriver d = new FakeDriver();
        AutoCreateJob j = new AutoCreateJob(d, traj(1000), cfg(30), null);
        j.cancel();
        j.tick();
        assertTrue(j.isDone());
        assertTrue(j.result().cancelled());
        assertTrue(d.shown.isEmpty());
    }
}
