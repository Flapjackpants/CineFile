package com.cinefile.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClipVerifierTest {
    static Trajectory traj(int n) throws Exception {
        return Replays.trajectory(t -> new Pose(0.4 * t, 64, 3 * Math.sin(t / 10.0), (t * 7) % 90, 5), n);
    }

    static CameraClip clip(Trajectory t, int a, int b) {
        return CameraSynth.synthesize(t, a, b, Styles.all().get(0), 0);
    }

    static CineFileConfig cfg(int every) {
        CineFileConfig c = new CineFileConfig();
        c.checkEveryTicks = every;
        c.rejectOffscreenPlayer = false; // these trajectories don't frame the player
        return c;
    }

    static Verdict run(Trajectory t, CameraClip clip, CineFileConfig c, FakeDriver d) {
        ClipVerifier v = new ClipVerifier(t, clip, clip, c);
        for (int i = 0; i < 5000; i++) {
            Verdict r = v.step(d);
            if (r != null) return r;
        }
        fail("never finished");
        return null;
    }

    @Test
    void offscreenCheckUsesReplayFov() throws Exception {
        Trajectory t = Replays.trajectory(tick -> new Pose(2, 64, 4, 0, 0), 100);
        CameraClip k = new CameraClip(0, 90, new CameraKey(0, 0, 65, 0, 0, 0),
            new CameraKey(90, 0, 65, 0, 0, 0), 0, "test");
        CineFileConfig c = cfg(10);
        c.rejectOffscreenPlayer = true;
        FakeDriver d = new FakeDriver();
        d.fov = 90;
        assertEquals(Verdict.OK, run(t, k, c, d));
        d.fov = 20;
        assertEquals(Verdict.PLAYER_OFFSCREEN, run(t, k, c, d));
    }

    @Test
    void visitsStepsAndAlwaysEnd() throws Exception {
        Trajectory t = traj(300);
        FakeDriver d = new FakeDriver();
        assertEquals(Verdict.OK, run(t, clip(t, 10, 210), cfg(30), d));
        assertEquals(List.of(10, 40, 70, 100, 130, 160, 190, 210), d.seeks);
    }

    @Test
    void blockClipHonoursSetting() throws Exception {
        Trajectory t = traj(300);
        CameraClip c = clip(t, 0, 90);
        FakeDriver d = new FakeDriver();
        d.blocked = (a, b) -> d.cur == 60 && a[0] != b[0];
        assertEquals(Verdict.BLOCK_CLIP, run(t, c, cfg(30), d));
        CineFileConfig off = cfg(30);
        off.rejectBlockClipping = false;
        assertEquals(Verdict.OK, run(t, c, off, d));
    }

    @Test
    void clipsRejectsWhenSightLineIsClear() throws Exception {
        Trajectory t = traj(300);
        CameraClip c = clip(t, 0, 90);
        FakeDriver d = new FakeDriver();
        d.blocked = (a, b) -> false;
        d.clips = (a, b) -> d.cur == 60;
        assertEquals(Verdict.BLOCK_CLIP, run(t, c, cfg(10), d));
    }

    @Test
    void clipCheckCanBeDisabled() throws Exception {
        Trajectory t = traj(300);
        CameraClip c = clip(t, 0, 90);
        FakeDriver d = new FakeDriver();
        d.blocked = (a, b) -> false;
        d.clips = (a, b) -> d.cur == 60;
        CineFileConfig off = cfg(10);
        off.rejectBlockClipping = false;
        assertEquals(Verdict.OK, run(t, c, off, d));
    }

    @Test
    void blockedOnlyMeansBlockedViewNotClip() throws Exception {
        Trajectory t = traj(300);
        CameraClip c = clip(t, 0, 90);
        FakeDriver d = new FakeDriver();
        d.clips = (a, b) -> false;
        d.blocked = (a, b) -> true;
        assertEquals(Verdict.VIEW_BLOCKED, run(t, c, cfg(10), d));
    }

    @Test
    void clipsGetsPreviousThenCurrentCameraPosition() throws Exception {
        Trajectory t = traj(300);
        CameraClip c = clip(t, 0, 90);
        FakeDriver d = new FakeDriver();
        d.blocked = (a, b) -> false;
        List<double[]> froms = new ArrayList<>(), tos = new ArrayList<>();
        d.clips = (a, b) -> {
            froms.add(a.clone());
            tos.add(b.clone());
            return false;
        };
        assertEquals(Verdict.OK, run(t, c, cfg(10), d));
        assertEquals(10, froms.size()); // ticks 0, 10, ..., 90
        assertArrayEquals(tos.get(0), froms.get(0));
        for (int i = 1; i < tos.size(); i++) assertArrayEquals(tos.get(i - 1), froms.get(i));
        for (int i = 0; i < tos.size(); i++) {
            double[] cam = d.cameraAt(c, i * 10);
            assertArrayEquals(new double[] {cam[0], cam[1], cam[2]}, tos.get(i));
        }
    }

    @Test
    void unloadedHonoursSetting() throws Exception {
        Trajectory t = traj(300);
        CameraClip c = clip(t, 0, 90);
        FakeDriver d = new FakeDriver();
        d.loaded = p -> false;
        assertEquals(Verdict.BLOCK_CLIP, run(t, c, cfg(30), d));
        CineFileConfig off = cfg(30);
        off.rejectBlockClipping = false;
        assertEquals(Verdict.OK, run(t, c, off, d));
    }

    @Test
    void liveDimensionHonoursSetting() throws Exception {
        Trajectory t = traj(300);
        CameraClip c = clip(t, 0, 90);
        FakeDriver d = new FakeDriver();
        d.dim = tick -> tick >= 60 ? "nether" : "overworld";
        assertEquals(Verdict.DIMENSION_CHANGE, run(t, c, cfg(30), d));
        CineFileConfig off = cfg(30);
        off.rejectDimensionChanges = false;
        assertEquals(Verdict.OK, run(t, c, off, d));
    }

    @Test
    void trajectoryChecksHonourSettings() throws Exception {
        Trajectory t = traj(300);
        CameraClip c = clip(t, 0, 90);
        t.cuts.add(50);
        FakeDriver d = new FakeDriver();
        assertEquals(Verdict.CUT, run(t, c, cfg(30), d));
        assertTrue(d.seeks.isEmpty());
        CineFileConfig off = cfg(30);
        off.rejectCuts = false;
        assertEquals(Verdict.OK, run(t, c, off, d));

        t.cuts.clear();
        t.dimensionChanges.add(90);
        assertEquals(Verdict.DIMENSION_CHANGE, run(t, c, cfg(30), new FakeDriver()));
        off = cfg(30);
        off.rejectDimensionChanges = false;
        assertEquals(Verdict.OK, run(t, c, off, new FakeDriver()));
        // start tick itself is outside (start, end]
        t.dimensionChanges.set(0, 0);
        assertEquals(Verdict.OK, run(t, c, cfg(30), new FakeDriver()));
    }

    @Test
    void blockedViewThreshold() throws Exception {
        Trajectory t = traj(300);
        CameraClip c = clip(t, 0, 90); // visits 0, 30, 60, 90
        CineFileConfig cf = cfg(30);
        cf.rejectBlockClipping = false;
        FakeDriver d = new FakeDriver();
        d.blocked = (a, b) -> true;
        assertEquals(Verdict.VIEW_BLOCKED, run(t, c, cf, d));
        d.blocked = (a, b) -> d.cur == 0;
        assertEquals(Verdict.OK, run(t, c, cf, d)); // exactly a quarter
        d.blocked = (a, b) -> d.cur <= 30;
        assertEquals(Verdict.VIEW_BLOCKED, run(t, c, cf, d));
        d.blocked = (a, b) -> true;
        cf.rejectBlockedView = false;
        assertEquals(Verdict.OK, run(t, c, cf, d));
    }

    @Test
    void seekTimeout() throws Exception {
        Trajectory t = traj(300);
        FakeDriver d = new FakeDriver();
        d.neverAtTick = true;
        ClipVerifier v = new ClipVerifier(t, clip(t, 0, 90), null, cfg(30));
        assertNull(v.step(d)); // seek
        for (int i = 0; i < 600; i++) assertNull(v.step(d), "step " + i);
        assertEquals(Verdict.TIMEOUT, v.step(d));
    }

    /** Player stands at (0,64,0) except at the given ticks, where he is 50 blocks behind the camera. */
    static Trajectory lost(int... awayTicks) throws Exception {
        return Replays.trajectory(t -> {
            for (int a : awayTicks) if (t >= a && t < a + 10) return new Pose(0, 64, -50, 0, 0);
            return new Pose(0, 64, 0, 0, 0);
        }, 300);
    }

    static CameraClip fixed(double yaw) {
        CameraKey k = new CameraKey(0, 0, 65, -10, yaw, 0);
        return new CameraClip(0, 90, k, k, 1, "t");
    }

    static CineFileConfig offCfg() {
        CineFileConfig c = cfg(30);
        c.rejectCuts = false;
        c.rejectBlockClipping = false;
        c.rejectOffscreenPlayer = true;
        return c;
    }

    @Test
    void offscreenHonoursSetting() throws Exception {
        Trajectory t = lost();
        assertEquals(Verdict.OK, run(t, fixed(0), offCfg(), new FakeDriver()));
        assertEquals(Verdict.PLAYER_OFFSCREEN, run(t, fixed(180), offCfg(), new FakeDriver()));
        CineFileConfig off = offCfg();
        off.rejectOffscreenPlayer = false;
        assertEquals(Verdict.OK, run(t, fixed(180), off, new FakeDriver()));
    }

    @Test
    void offscreenThreshold() throws Exception {
        // visits 0, 30, 60, 90
        assertEquals(Verdict.OK, run(lost(90), fixed(0), offCfg(), new FakeDriver()));
        assertEquals(Verdict.PLAYER_OFFSCREEN, run(lost(60, 90), fixed(0), offCfg(), new FakeDriver()));
    }
}
