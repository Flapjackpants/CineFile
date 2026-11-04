package com.cinefile.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    static Trajectory walk() throws Exception {
        return Replays.trajectory(t -> new Pose(0.4 * t, 64, 0, 0, 0), 500);
    }

    static CineFileConfig walkConfig(FocusLevel level) {
        CineFileConfig c = cfg(10);
        c.focusLevel = level;
        return c;
    }

    private static final String LOCAL = "00000000-0000-0001-0000-000000000002";
    private static final String OTHER = "00000000-0000-0003-0000-000000000004";

    private static Trajectory twoPlayers(boolean localActive, boolean otherActive, int from, int until) throws Exception {
        List<Replays.Action> stream = new ArrayList<>();
        for (int tick = 1; tick < 500; tick++) {
            stream.add(Replays.next());
            if (tick == from && from > 0) stream.add(Replays.add(8, new UUID(3, 4), 150, new Pose(500, 64, 0, 0, 0)));
            if (tick == until) stream.add(Replays.remove(8));
            stream.add(Replays.moves("minecraft:overworld", new int[] {7, 8}, new Pose[] {
                new Pose(localActive ? tick * 0.4 : 0, 64, 0, 0, 0),
                new Pose(500 + (otherActive ? tick * 0.4 : 0), 64, 0, 0, 0)
            }));
        }
        List<Replays.Action> snap = new ArrayList<>(List.of(Replays.login(7), Replays.create(new Pose(0, 64, 0, 0, 0))));
        if (from == 0) snap.add(Replays.add(8, new UUID(3, 4), 150, new Pose(500, 64, 0, 0, 0)));
        byte[] chunk = Replays.chunk(snap, stream);
        return ReplayParser.parse(Replays.files("{\"uuid\":\"u\",\"total_ticks\":500,\"chunks\":{\"c0.flashback\":{}}}",
            Map.of("c0.flashback", chunk)), 12, new PlayerPacketIds(90, 91, 150));
    }

    private static CineFileConfig playerCfg(String id) {
        CineFileConfig c = cfg(10);
        c.focusPlayer = id;
        c.styleId = "locked-dolly";
        c.focusLevel = FocusLevel.CENTER;
        return c;
    }

    private static void nearSubject(FakeDriver d, Trajectory subject) {
        assertFalse(d.committed.isEmpty());
        for (CameraClip k : d.committed) {
            Pose p = subject.sample(k.a().tick());
            assertNotNull(p);
            assertTrue(Math.hypot(k.a().x() - p.x(), k.a().z() - p.z()) < 20, k.toString());
        }
    }

    @Test
    void namedPlayersDetermineShotLocation() throws Exception {
        Trajectory t = twoPlayers(true, true, 0, 500);
        FakeDriver other = new FakeDriver();
        run(other, t, playerCfg(OTHER), null);
        nearSubject(other, t.player(OTHER));
        FakeDriver local = new FakeDriver();
        run(local, t, playerCfg(LOCAL), null);
        nearSubject(local, t);
    }

    @Test
    void unknownPlayerReportsErrorWithoutShowingShot() throws Exception {
        String id = "00000000-0000-0005-0000-000000000006";
        FakeDriver d = new FakeDriver();
        assertEquals("Player " + id + " isn't in this replay", run(d, twoPlayers(true, true, 0, 500), playerCfg(id), null).result().error());
        assertTrue(d.shown.isEmpty());
    }

    @Test
    void autoFollowsActivePlayerInEitherDirection() throws Exception {
        for (boolean localActive : new boolean[] {false, true}) {
            Trajectory t = twoPlayers(localActive, !localActive, 0, 500);
            FakeDriver d = new FakeDriver();
            run(d, t, playerCfg("auto"), null);
            nearSubject(d, localActive ? t : t.player(OTHER));
        }
    }

    @Test
    void verifierChecksNamedSubjectAndPresence() throws Exception {
        Trajectory t = twoPlayers(true, true, 100, 350);
        FakeDriver d = new FakeDriver();
        List<double[]> targets = new ArrayList<>();
        d.blocked = (from, to) -> { targets.add(to); return false; };
        run(d, t, playerCfg(OTHER), null);
        nearSubject(d, t.player(OTHER));
        assertFalse(targets.isEmpty());
        for (double[] to : targets) assertTrue(to[0] > 400, java.util.Arrays.toString(to));
        for (CameraClip k : d.committed) {
            assertTrue(k.startTick() >= 100);
            assertTrue(k.endTick() < 350);
        }
    }

    @Test
    void singlePlayerAutoMatchesNamedLocal() throws Exception {
        Trajectory t = walk();
        FakeDriver auto = new FakeDriver(), named = new FakeDriver();
        run(auto, t, playerCfg("auto"), null);
        run(named, t, playerCfg(t.playerUuid()), null);
        assertEquals(auto.committed, named.committed);
    }

    @Test
    void fixedFocusLevelsSetCameraHeightAndPitch() throws Exception {
        for (FocusLevel level : List.of(FocusLevel.CENTER, FocusLevel.EYE)) {
            FakeDriver d = new FakeDriver();
            run(d, walk(), walkConfig(level), null);
            assertFalse(d.committed.isEmpty(), level.toString());
            double y = level == FocusLevel.CENTER ? 64.9 : 65.62;
            for (CameraClip k : d.committed) {
                assertEquals(y, k.a().y(), 1e-6);
                assertEquals(y, k.b().y(), 1e-6);
                assertEquals(0, k.a().pitch(), 0.01);
                assertEquals(0, k.b().pitch(), 0.01);
            }
        }
    }

    @Test
    void autoFocusTriesHigherLevelWhenCenterIsBlocked() throws Exception {
        FakeDriver d = new FakeDriver();
        run(d, walk(), walkConfig(FocusLevel.AUTO), null);
        assertFalse(d.shown.isEmpty());
        assertEquals(64.9, d.shown.get(0).a().y(), 1e-6);

        FakeDriver blocked = new FakeDriver();
        blocked.blocked = (from, to) -> from[1] < 65;
        run(blocked, walk(), walkConfig(FocusLevel.AUTO), null);
        assertFalse(blocked.committed.isEmpty());
        assertTrue(blocked.committed.get(0).a().y() >= 65);
    }

    @Test
    void jobTunesDistanceToReplayFov() throws Exception {
        CineFileConfig c = walkConfig(FocusLevel.CENTER);
        c.styleId = "locked-dolly";
        FakeDriver narrow = new FakeDriver();
        narrow.fov = 53;
        run(narrow, walk(), c, null);
        FakeDriver wide = new FakeDriver();
        wide.fov = 90;
        run(wide, walk(), c, null);
        assertFalse(narrow.committed.isEmpty());
        assertFalse(wide.committed.isEmpty());
        CameraKey a = narrow.committed.get(0).a(), b = wide.committed.get(0).a();
        double near = Math.hypot(a.x() - 0.4 * a.tick(), a.z());
        double far = Math.hypot(b.x() - 0.4 * b.tick(), b.z());
        assertTrue(far < near, far + " < " + near);
    }

    @Test
    void autoStyleBalancesSubjectSizeAgainstDistance() throws Exception {
        FakeDriver d = new FakeDriver();
        d.fov = 53;
        run(d, walk(), walkConfig(FocusLevel.CENTER), null);
        assertFalse(d.shown.isEmpty());
        assertNotEquals("high-crane", d.shown.get(0).styleId());
    }

    static CineFileConfig cfg(double durationS) {
        CineFileConfig c = new CineFileConfig();
        c.durationS = durationS;
        c.rejectOffscreenPlayer = false; // these trajectories don't frame the player
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
    void planningDropsOffscreenVariants() throws Exception {
        Trajectory fast = Replays.trajectory(t -> new Pose(60 * Math.sin(2 * Math.PI * t / 200), 64, 0, 0, 0), 1000);
        FakeDriver d = new FakeDriver();
        CineFileConfig on = cfg(30);
        on.rejectOffscreenPlayer = true;
        AutoCreateJob.Result r = run(d, fast, on, null).result();
        assertEquals("No valid clip windows found", r.error());
        assertTrue(d.shown.isEmpty());
        CineFileConfig off = cfg(30);
        off.rejectOffscreenPlayer = false;
        assertNull(run(new FakeDriver(), fast, off, null).result().error());
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
    void straightDollyCommitsLevelClips() throws Exception {
        CineFileConfig c = walkConfig(FocusLevel.CENTER);
        c.styleId = "straight-dolly";
        FakeDriver d = new FakeDriver();
        run(d, walk(), c, null);
        assertFalse(d.committed.isEmpty());
        for (CameraClip k : d.committed) {
            assertEquals("straight-dolly", k.styleId());
            assertEquals(k.a().y(), k.b().y(), 1e-9);
        }
    }

    private static CameraClip latest(FakeDriver d) {
        return d.shown.get(d.shown.size() - 1);
    }

    @Test
    void straightDollyShortensBlockedMoveAndCommits() throws Exception {
        CineFileConfig c = walkConfig(FocusLevel.CENTER);
        c.styleId = "straight-dolly";
        FakeDriver d = new FakeDriver();
        d.clips = (from, to) -> {
            CameraClip k = d.shown.get(0);
            double len = Math.hypot(k.b().x() - k.a().x(), k.b().z() - k.a().z());
            return Math.hypot(to[0] - k.a().x(), to[2] - k.a().z()) > 0.6 * len; // 60% of 2 blocks is 1.2
        };
        AutoCreateJob.Result r = run(d, walk(), c, null).result();
        assertEquals(2, d.shown.size());
        assertEquals(d.shown.get(0).a(), d.shown.get(1).a());
        CameraClip first = d.shown.get(0), second = d.shown.get(1);
        double ax = second.b().x() - second.a().x(), az = second.b().z() - second.a().z();
        double bx = first.b().x() - first.a().x(), bz = first.b().z() - first.a().z();
        assertEquals(0, ax * bz - az * bx, 1e-6);
        assertTrue(Math.hypot(ax, az) < Math.hypot(bx, bz));
        assertEquals(first.b().yaw(), second.b().yaw());
        assertEquals(first.b().pitch(), second.b().pitch());
        assertTrue(d.discarded.contains(first));
        assertTrue(d.committed.contains(second));
        assertFalse(r.rejected().containsKey(Verdict.BLOCK_CLIP));
        assertEquals(d.shown.size(), d.discarded.size() + d.committed.size());
    }

    @Test
    void straightDollyFallsBackWhenFirstSampleClips() throws Exception {
        CineFileConfig c = walkConfig(FocusLevel.CENTER);
        c.styleId = "straight-dolly";
        FakeDriver d = new FakeDriver();
        d.clips = (from, to) -> true;
        AutoCreateJob.Result r = run(d, walk(), c, null).result();
        int rejected = r.rejected().getOrDefault(Verdict.BLOCK_CLIP, 0);
        assertEquals(rejected, d.shown.size());
        assertTrue(rejected > 0);
        assertTrue(d.committed.isEmpty());
    }

    @Test
    void straightDollyFallsBackWhenUnderOneBlockRemains() throws Exception {
        CineFileConfig c = walkConfig(FocusLevel.CENTER);
        c.styleId = "straight-dolly";
        FakeDriver d = new FakeDriver();
        d.clips = (from, to) -> Math.hypot(to[0] - latest(d).a().x(), to[2] - latest(d).a().z()) > 0.5;
        AutoCreateJob.Result r = run(d, walk(), c, null).result();
        int rejected = r.rejected().getOrDefault(Verdict.BLOCK_CLIP, 0);
        assertEquals(rejected, d.shown.size());
        assertTrue(rejected > 0);
        assertTrue(d.committed.isEmpty());
    }

    @Test
    void straightDollyStopsShorteningAtTheLimit() throws Exception {
        CineFileConfig c = walkConfig(FocusLevel.CENTER);
        c.styleId = "straight-dolly";
        FakeDriver d = new FakeDriver();
        d.clips = (from, to) -> Math.abs(to[0] - latest(d).b().x()) < 1e-9
                && Math.abs(to[2] - latest(d).b().z()) < 1e-9;
        AutoCreateJob.Result r = run(d, walk(), c, null).result();
        int rejected = r.rejected().getOrDefault(Verdict.BLOCK_CLIP, 0);
        assertTrue(rejected > 0);
        assertEquals((AutoCreateJob.MAX_SHORTENS + 1) * rejected, d.shown.size());
        assertTrue(d.committed.isEmpty());
        assertEquals(d.shown.size(), d.discarded.size() + d.committed.size());
    }

    @Test
    void otherStylesRejectBlockedMoveImmediately() throws Exception {
        CineFileConfig c = walkConfig(FocusLevel.CENTER);
        c.styleId = "locked-dolly";
        FakeDriver d = new FakeDriver();
        d.clips = (from, to) -> Math.abs(to[0] - latest(d).b().x()) < 1e-9
                && Math.abs(to[2] - latest(d).b().z()) < 1e-9;
        AutoCreateJob.Result r = run(d, walk(), c, null).result();
        int rejected = r.rejected().getOrDefault(Verdict.BLOCK_CLIP, 0);
        assertTrue(rejected > 0);
        assertEquals(rejected, d.shown.size());
        assertTrue(d.committed.isEmpty());
    }

    @Test
    void topDownDoesNotRepeatClipsAcrossFocusLevels() throws Exception {
        CineFileConfig c = cfg(10);
        c.styleId = "top-down";
        c.focusLevel = FocusLevel.AUTO;
        FakeDriver d = new FakeDriver();
        d.clips = (a, b) -> true;
        run(d, walk(), c, null);
        assertFalse(d.shown.isEmpty());
        assertEquals(d.shown.size(), new HashSet<>(d.shown).size());
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
