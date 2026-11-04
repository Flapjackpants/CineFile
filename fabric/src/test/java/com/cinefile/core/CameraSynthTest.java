package com.cinefile.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class CameraSynthTest {
    static Trajectory stationary() throws Exception {
        return Replays.trajectory(t -> new Pose(0, 64, 0, 0, 0), 31);
    }

    @Test
    void fixedLevelsAimAtTheirOwnHeight() throws Exception {
        Trajectory t = stationary();
        Style dolly = Styles.resolve("locked-dolly").get(0);
        for (FocusLevel level : new FocusLevel[] {FocusLevel.CENTER, FocusLevel.EYE, FocusLevel.HIGH, FocusLevel.LOW}) {
            CameraClip c = CameraSynth.synthesize(t, 0, 30, dolly, 1, level, 53);
            assertEquals(64 + level.cameraHeight(), c.a().y(), 1e-6);
            assertEquals(64 + level.cameraHeight(), c.b().y(), 1e-6);
            assertEquals(c.a().yaw(), c.b().yaw(), 1e-6);
            assertEquals(c.a().pitch(), c.b().pitch(), 1e-6);
            assertEquals(dolly.id(), c.styleId());
            if (level == FocusLevel.HIGH) assertTrue(c.a().pitch() > 0);
            else if (level == FocusLevel.LOW) assertTrue(c.a().pitch() < 0);
            else assertEquals(0, c.a().pitch(), 1e-6);
        }
        CameraClip high70 = CameraSynth.synthesize(t, 0, 30, dolly, 1, FocusLevel.HIGH, 70);
        assertEquals(64 + 0.9 + 2.1 * Styles.fovScale(70), high70.a().y(), 1e-6);
    }

    @Test
    void orbitAndJitterUseFixedLevel() throws Exception {
        Trajectory t = stationary();
        Style orbit = Styles.resolve("orbit-reveal").get(0);
        CameraClip c = CameraSynth.synthesize(t, 0, 30, orbit, 0, FocusLevel.CENTER, 53);
        assertEquals(64.9, c.a().y(), 1e-6);
        assertEquals(64.9, c.b().y(), 1e-6);
        assertEquals(wrap(c.a().yaw() + Styles.tuned(orbit, 53).orbitDegrees()), c.b().yaw(), 1e-6);
        Style jitter = Styles.variant(Styles.resolve("locked-dolly").get(0), 3, 40);
        CameraClip fixed = CameraSynth.synthesize(t, 0, 30, jitter, 0, FocusLevel.CENTER, 53);
        assertEquals(64.9, fixed.a().y(), 1e-6);
        assertEquals(0, fixed.a().pitch(), 1e-6);
    }

    @Test
    void lockedKeysKeepFirstAimDespiteMotion() throws Exception {
        Trajectory t = Replays.trajectory(tick -> new Pose(0, 64 + tick / 10.0, 0, tick, 0), 31);
        Style locked = Styles.resolve("locked-dolly").get(0);
        CameraClip dolly = CameraSynth.synthesize(t, 0, 30, locked, 0, FocusLevel.HIGH, 53);
        assertEquals(dolly.a().yaw(), dolly.b().yaw(), 1e-6);
        assertEquals(dolly.a().pitch(), dolly.b().pitch(), 1e-6);
    }

    private static double wrap(double degrees) {
        return (degrees + 540) % 360 - 180;
    }

    @Test
    void styleMatchesLegacySynthesisAndAutoNeedsExpansion() throws Exception {
        Trajectory t = stationary();
        for (Style s : Styles.all()) {
            assertEquals(CameraSynth.synthesize(t, 0, 30, Styles.tuned(s, 53), 1),
                    CameraSynth.synthesize(t, 0, 30, s, 1, FocusLevel.STYLE, 53));
        }
        Style s = Styles.all().get(0);
        assertThrows(IllegalArgumentException.class, () -> CameraSynth.synthesize(t, 0, 30, s, 0, FocusLevel.AUTO, 53));
        assertNull(CameraSynth.synthesize(t, -1, 30, s, 0, FocusLevel.CENTER, 53));
    }
    static final String[] IDS = {"hero-low", "high-crane", "locked-dolly", "orbit-reveal", "side-track"};

    // cinefile.camera.synthesize_clip on a player at x=+/-0.5*t, y=64, z=0, yaw=30+t, pitch=5, clip 0..30, score 1.5
    static final double[][] PLUS = {
        {5.482050807568877, 64.82000000000001, -5.49519052838329, 41.88865803962797, 12.0, 17.48205080756888, 64.82000000000001, -5.49519052838329, 41.88865803962797, 12.0},
        {8.165063509461095, 71.62, -9.142304845413264, 40.12467165539781, -25.0, 26.165063509461095, 71.62, -9.142304845413264, 40.12467165539781, -25.0},
        {4.558845726811989, 66.62, -4.296152422706632, 42.68038349181982, 0.0, 19.558845726811988, 66.62, -4.296152422706632, 42.68038349181982, 0.0},
        {4.433012701892219, 67.62, -6.67820323027551, 32.86240522611174, 12.50154859206157, 15.080127018922193, 67.62, -4.811473670974873, 67.86240522611175, 12.501548592061575},
        {-0.2810889132455361, 66.82000000000001, -6.513139720814413, 4.9831065218999715, -2.0, 14.718911086754463, 66.82000000000001, -6.513139720814413, 4.9831065218999715, -2.0},
    };
    static final double[][] MINUS = {
        {2.0179491924311224, 64.82000000000001, -7.49519052838329, 18.111341960372027, 12.0, -9.982050807568879, 64.82000000000001, -7.49519052838329, 18.111341960372027, 12.0},
        {3.8349364905389023, 71.62, -11.642304845413264, 19.875328344602178, -25.0, -14.165063509461097, 71.62, -11.642304845413264, 19.875328344602178, -25.0},
        {1.4411542731880098, 66.62, -6.096152422706632, 17.319616508180175, 0.0, -13.55884572681199, 66.62, -6.096152422706632, 17.319616508180175, 0.0},
        {3.5669872981077804, 67.62, -7.17820323027551, 27.13759477388825, 12.50154859206157, -3.5662831425915833, 67.62, -5.5310889132455365, -7.862405226111747, 12.50154859206157},
        {-0.2810889132455361, 66.82000000000001, -6.513139720814413, 4.9831065218999715, -2.0, -15.281088913245537, 66.82000000000001, -6.513139720814413, 4.9831065218999715, -2.0},
    };

    static Trajectory walk(int dir) throws Exception {
        return Replays.trajectory(t -> new Pose(dir * 0.5 * t, 64, 0, 30 + t, 5), 31);
    }

    private void check(int dir, double[][] want) throws Exception {
        Trajectory t = walk(dir);
        for (int i = 0; i < IDS.length; i++) {
            CameraClip c = CameraSynth.synthesize(t, 0, 30, Styles.resolve(IDS[i]).get(0), 1.5);
            double[] w = want[i];
            String m = IDS[i] + " dir " + dir;
            assertEquals(0, c.startTick());
            assertEquals(30, c.endTick());
            assertEquals(1.5, c.score());
            assertEquals(IDS[i], c.styleId());
            double[] got = {c.a().x(), c.a().y(), c.a().z(), c.a().yaw(), c.a().pitch(), c.b().x(), c.b().y(), c.b().z(), c.b().yaw(), c.b().pitch()};
            assertArrayEquals(w, got, 1e-6, m);
            assertEquals(0, c.a().tick());
            assertEquals(30, c.b().tick());
        }
    }

    @Test
    void movingPlusXMatchesPython() throws Exception {
        check(1, PLUS);
    }

    @Test
    void movingMinusXMatchesPython() throws Exception {
        check(-1, MINUS);
    }

    @Test
    void noPoseGivesNull() throws Exception {
        assertNull(CameraSynth.synthesize(walk(1), -5, 10, Styles.all().get(0), 0));
    }

    @Test
    void withScoreKeepsEverythingElse() throws Exception {
        CameraClip c = CameraSynth.synthesize(walk(1), 0, 30, Styles.all().get(0), 1.5);
        CameraClip d = c.withScore(9);
        assertEquals(9, d.score());
        assertEquals(c.a(), d.a());
        assertEquals(c.styleId(), d.styleId());
    }

    private static List<CameraClip> all(Trajectory t, String id, int t0, int t1, FocusLevel level, double fov) {
        return CameraSynth.synthesizeAll(t, t0, t1, Styles.resolve(id).get(0), 0, level, fov);
    }

    @Test
    void straightDollyTriesBothDirections() throws Exception {
        List<CameraClip> clips = all(walk(1), "straight-dolly", 0, 30, null, 1);
        assertEquals(2, clips.size());
        CameraClip fwd = clips.get(0), back = clips.get(1);
        for (CameraClip c : clips) {
            assertEquals(0, c.startTick());
            assertEquals(30, c.endTick());
            assertEquals("straight-dolly", c.styleId());
            assertArrayEquals(new double[] {4.558845726811989, 66.62, -4.296152422706632, 42.68038349181982, 0.0},
                new double[] {c.a().x(), c.a().y(), c.a().z(), c.a().yaw(), c.a().pitch()}, 1e-9);
            assertEquals(c.a().yaw(), c.b().yaw(), 1e-9);
            assertEquals(c.a().pitch(), c.b().pitch(), 1e-9);
        }
        assertArrayEquals(new double[] {3.183846, 66.62, 0.683494},
            new double[] {fwd.b().x(), fwd.b().y(), fwd.b().z()}, 1e-5);
        assertArrayEquals(new double[] {8.183846, 66.62, -7.976760},
            new double[] {back.b().x(), back.b().y(), back.b().z()}, 1e-5);
        assertEquals(fwd, CameraSynth.synthesize(walk(1), 0, 30, Styles.resolve("straight-dolly").get(0), 0, null, 1));
    }

    @Test
    void topDownLooksStraightDownAndDriftsBothWays() throws Exception {
        List<CameraClip> clips = all(walk(1), "top-down", 0, 30, null, 1);
        assertEquals(2, clips.size());
        for (CameraClip c : clips) {
            for (CameraKey k : new CameraKey[] {c.a(), c.b()}) {
                assertEquals(7.5, k.x(), 1e-9);
                assertEquals(0, k.z(), 1e-9);
                assertEquals(30, k.yaw(), 1e-9);
                assertEquals(90, k.pitch(), 1e-9);
            }
            assertEquals(74, c.a().y(), 1e-9);
        }
        assertEquals(72.5, clips.get(0).b().y(), 1e-9);
        assertEquals(75.5, clips.get(1).b().y(), 1e-9);
    }

    @Test
    void straightDollyTravelIsTwoBlocksWhenStationary() throws Exception {
        for (CameraClip c : all(stationary(), "straight-dolly", 0, 30, null, 1)) {
            assertEquals(2, Math.hypot(c.b().x() - c.a().x(), c.b().z() - c.a().z()), 1e-9);
            assertEquals(c.a().y(), c.b().y(), 1e-9);
        }
    }

    @Test
    void straightDollyTravelFollowsMovementAlongTheShot() throws Exception {
        Trajectory t = Replays.trajectory(tick -> new Pose(0, 64, tick * 0.1, 0, 0), 31);
        for (CameraClip c : all(t, "straight-dolly", 0, 30, null, 1)) {
            assertEquals(3, Math.abs(c.b().z() - c.a().z()), 1e-9);
            assertEquals(c.a().x(), c.b().x(), 1e-9);
        }
    }

    @Test
    void straightDollyFollowsTenPercentOfSidewaysMovement() throws Exception {
        Trajectory t = Replays.trajectory(tick -> new Pose(tick * 0.1, 64, 0, 0, 0), 31);
        for (CameraClip c : all(t, "straight-dolly", 0, 30, null, 1)) {
            assertEquals(0.3, c.b().x() - c.a().x(), 1e-9);
            assertEquals(2, Math.abs(c.b().z() - c.a().z()), 1e-9);
        }
    }

    @Test
    void straightDollyKeepsOneLevelEvenWhenThePlayerClimbs() throws Exception {
        Trajectory t = Replays.trajectory(tick -> new Pose(0, 64 + tick / 10.0, 0, 0, 0), 31);
        for (FocusLevel level : new FocusLevel[] {null, FocusLevel.CENTER}) {
            List<CameraClip> clips = all(t, "straight-dolly", 0, 30, level, 53);
            assertEquals(2, clips.size());
            for (CameraClip c : clips) assertEquals(c.a().y(), c.b().y(), 1e-9);
        }
    }

    @Test
    void topDownIgnoresFocusLevelAndJitterPitch() throws Exception {
        Style style = Styles.resolve("top-down").get(0);
        assertEquals(CameraSynth.synthesizeAll(stationary(), 0, 30, style, 0, FocusLevel.CENTER, 53),
            CameraSynth.synthesizeAll(stationary(), 0, 30, style, 0, FocusLevel.HIGH, 53));
        for (CameraClip c : CameraSynth.synthesizeAll(stationary(), 0, 30, Styles.variant(style, 3, 40), 0, null, 1)) {
            assertEquals(90, c.a().pitch(), 1e-9);
            assertEquals(90, c.b().pitch(), 1e-9);
        }
    }

    @Test
    void topDownProjectsThePlayerAtScreenCenter() throws Exception {
        CameraClip c = all(stationary(), "top-down", 0, 30, null, 1).get(0);
        double[] uv = Framing.projectToScreen(
            new double[] {c.a().x(), c.a().y(), c.a().z(), c.a().yaw(), c.a().pitch()}, new double[] {0, 64, 0}, 70);
        assertNotNull(uv);
        assertEquals(0.5, uv[0], 1e-6);
        assertEquals(0.5, uv[1], 1e-6);
    }

    @Test
    void synthesizeAllKeepsSingleClipStylesUnchanged() throws Exception {
        Style locked = Styles.resolve("locked-dolly").get(0);
        assertEquals(List.of(CameraSynth.synthesize(walk(1), 0, 30, locked, 1.5)),
            CameraSynth.synthesizeAll(walk(1), 0, 30, locked, 1.5, null, 1));
    }

    @Test
    void synthesizeAllHandlesMissingPosesAndAuto() throws Exception {
        Style style = Styles.resolve("straight-dolly").get(0);
        assertEquals(List.of(), CameraSynth.synthesizeAll(walk(1), -1, 30, style, 0, null, 1));
        assertThrows(IllegalArgumentException.class,
            () -> CameraSynth.synthesizeAll(walk(1), 0, 30, style, 0, FocusLevel.AUTO, 1));
    }
}
