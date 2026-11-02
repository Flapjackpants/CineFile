package com.cinefile.core;

import static org.junit.jupiter.api.Assertions.*;

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
    void blockedViewNeedsMajority() throws Exception {
        Trajectory t = traj(300);
        CameraClip c = clip(t, 0, 90); // visits 0, 30, 60, 90
        CineFileConfig cf = cfg(30);
        cf.rejectBlockClipping = false;
        FakeDriver d = new FakeDriver();
        d.blocked = (a, b) -> true;
        assertEquals(Verdict.VIEW_BLOCKED, run(t, c, cf, d));
        d.blocked = (a, b) -> d.cur <= 30;
        assertEquals(Verdict.OK, run(t, c, cf, d));
        d.blocked = (a, b) -> d.cur <= 60;
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
        assertEquals(Verdict.TIMEOUT, run(t, clip(t, 0, 90), cfg(30), d));
    }
}
