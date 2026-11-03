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

    static Trajectory walk() throws Exception {
        return Replays.trajectory(t -> new Pose(0.4 * t, 64, 0, 0, 0), 500);
    }

    static CineFileConfig walkConfig(FocusLevel level) {
        CineFileConfig c = cfg(10);
        c.focusLevel = level;
        return c;
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
