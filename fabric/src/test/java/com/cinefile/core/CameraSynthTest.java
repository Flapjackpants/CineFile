package com.cinefile.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CameraSynthTest {
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
}
