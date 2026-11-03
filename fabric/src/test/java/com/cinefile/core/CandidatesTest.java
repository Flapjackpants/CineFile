package com.cinefile.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class CandidatesTest {
    @Test
    void presenceBreaksSplitWindowsEvenWithFlagsOff() throws Exception {
        java.util.List<Replays.Action> stream = new java.util.ArrayList<>();
        for (int tick = 1; tick <= 40; tick++) {
            stream.add(Replays.next());
            if (tick == 10) stream.add(Replays.remove(8));
            if (tick == 30) stream.add(Replays.add(8, new java.util.UUID(3, 4), 150, new Pose(30, 64, 0, 0, 0)));
        }
        byte[] c = Replays.chunk(List.of(Replays.login(7), Replays.create(new Pose(0, 64, 0, 0, 0)),
                Replays.add(8, new java.util.UUID(3, 4), 150, new Pose(0, 64, 0, 0, 0))), stream);
        Trajectory t = ReplayParser.parse(Replays.files("{\"uuid\":\"u\",\"total_ticks\":41}", java.util.Map.of("c0.flashback", c)),
                12, new PlayerPacketIds(90, 91, 150)).player("00000000-0000-0003-0000-000000000004");
        List<Candidate> windows = Candidates.windows(t, 5, 500, false, false);
        assertFalse(windows.isEmpty());
        assertTrue(windows.stream().noneMatch(w -> w.start() < 10 && w.end() >= 10));
        assertTrue(windows.stream().noneMatch(w -> w.start() < 30 && w.end() >= 30));
        assertTrue(windows.stream().noneMatch(w -> w.start() >= 11 && w.end() <= 29));
    }
    // 200 ticks; teleport of 100 blocks at tick 120. Same fixture drives the Python numbers below.
    static Trajectory fixture() throws Exception {
        return Replays.trajectory(t -> new Pose(0.4 * t + (t >= 120 ? 100 : 0), 64, 3 * Math.sin(t / 10.0), (t * 7) % 90, 5), 200);
    }

    // cinefile.camera.candidate_windows(traj, 30)
    static final double[][] WIN = {
        {80, 110, 16.562275785132563}, {140, 170, 16.560027015115573}, {50, 80, 16.55980649411243}, {20, 50, 16.555217666616144},
        {10, 40, 16.541737800966338}, {40, 70, 16.53410022576188}, {150, 180, 16.533624465658832}, {70, 100, 16.526696697882574},
        {120, 150, 16.526254567751955}, {130, 160, 16.51467309142136}, {60, 90, 16.51438470092259}, {160, 190, 16.51084134340763},
        {30, 60, 16.51065968072632}, {0, 30, 16.508741628695248},
    };
    // cinefile.camera.candidate_windows(traj, 30, max_candidates=4)
    static final double[][] WIN4 = {{120, 150, 16.526254567751955}, {69, 99, 16.52190860917991}, {0, 30, 16.508741628695248}};

    private void same(double[][] want, List<Candidate> got) {
        assertEquals(want.length, got.size());
        for (int i = 0; i < want.length; i++) {
            assertEquals((int) want[i][0], got.get(i).start());
            assertEquals((int) want[i][1], got.get(i).end());
            assertEquals(want[i][2], got.get(i).score(), 1e-6);
        }
    }

    @Test
    void activityScoreMatchesPython() throws Exception {
        Trajectory t = fixture();
        // cinefile.camera.activity_score
        assertEquals(16.508741628695248, Candidates.activityScore(t, 0, 30), 1e-6);
        assertEquals(16.55980649411243, Candidates.activityScore(t, 50, 80), 1e-6);
        assertEquals(29.041693891064437, Candidates.activityScore(t, 100, 160), 1e-6);
        assertEquals(24.79413677626139, Candidates.activityScore(t, 130, 199), 1e-6);
        assertEquals(0.0, Candidates.activityScore(t, 10, 10));
    }

    @Test
    void windowsMatchPython() throws Exception {
        Trajectory t = fixture();
        same(WIN, Candidates.windows(t, 30, 500, true, false));
        same(WIN4, Candidates.windows(t, 30, 4, true, false));
    }

    @Test
    void segmentsSplitAtCuts() throws Exception {
        Trajectory t = fixture();
        List<int[]> s = Candidates.segments(t, true, false);
        assertEquals(2, s.size());
        assertArrayEquals(new int[] {0, 119}, s.get(0));
        assertArrayEquals(new int[] {120, 199}, s.get(1));
        assertEquals(1, Candidates.segments(t, false, false).size());
    }

    @Test
    void dimensionSplitIsOptional() throws Exception {
        List<Replays.Action> s = new java.util.ArrayList<>();
        for (int i = 1; i < 120; i++) {
            s.add(Replays.next());
            s.add(Replays.move(i >= 60 ? "minecraft:the_end" : "minecraft:overworld", 7, new Pose(i, 64, 0, 0, 0)));
        }
        byte[] c = Replays.chunk(List.of(Replays.create(new Pose(0, 64, 0, 0, 0))), s);
        Trajectory t = ReplayParser.parse(Replays.files("{\"uuid\":\"u\",\"total_ticks\":120}", java.util.Map.of("c0.flashback", c)), 12.0);
        assertEquals(List.of(60), t.dimensionChanges());
        boolean offCrosses = Candidates.windows(t, 30, 500, true, false).stream().anyMatch(w -> w.start() < 60 && w.end() >= 60);
        boolean onCrosses = Candidates.windows(t, 30, 500, true, true).stream().anyMatch(w -> w.start() < 60 && w.end() >= 60);
        assertTrue(offCrosses);
        assertFalse(onCrosses);
        assertFalse(Candidates.windows(t, 30, 500, true, true).isEmpty());
    }

    @Test
    void slicesMatchPython() throws Exception {
        Trajectory t = fixture();
        List<Candidate> w = Candidates.windows(t, 30, 500, true, false);
        // cinefile.camera.slice_candidates
        assertEquals(List.of(List.of(3, 4, 5, 12, 13), List.of(0, 2, 7, 10), List.of(8), List.of(1, 6, 9, 11)), Candidates.slices(w, 4));
        assertEquals(List.of(List.of(2, 3, 4, 5, 12, 13), List.of(0, 7, 10), List.of(1, 6, 8, 9, 11)), Candidates.slices(w, 3));
        List<Candidate> w4 = Candidates.windows(t, 30, 4, true, false);
        assertEquals(List.of(List.of(2), List.of(), List.of(1), List.of(0)), Candidates.slices(w4, 4));
        assertEquals(List.of(List.of(), List.of()), Candidates.slices(List.of(), 2));
    }
}
