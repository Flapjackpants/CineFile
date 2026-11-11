package com.cinefile.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FramingTest {
    @Test
    void fovOverloadsAndSizeScore() throws Exception {
        double[] cam = {0, 64, 0, 0, 0}, point = {1, 64, 5};
        assertArrayEquals(Framing.projectToScreen(cam, point), Framing.projectToScreen(cam, point, 70), 1e-12);
        Trajectory t = CameraSynthTest.stationary();
        CameraClip center = CameraSynth.synthesize(t, 0, 30, Styles.resolve("locked-dolly").get(0), 0, FocusLevel.CENTER, 53);
        assertEquals(1, Framing.stats(t, center, 20, 53).onscreenFrac(), 1e-12);
        double[] uv = Framing.projectToScreen(Framing.cameraAt(center, 0), new double[] {0, 65, 0}, 53);
        assertEquals(0.5, uv[1], 0.05);
        assertEquals(1, Framing.sizeScore(t, center, 20, 53), 1e-6);
        assertEquals(Framing.stats(t, center, 20), Framing.stats(t, center, 20, 70));

        CameraClip far = CameraSynth.synthesize(t, 0, 30, Styles.resolve("high-crane").get(0), 0, FocusLevel.CENTER, 53);
        assertEquals(0.6, Framing.sizeScore(t, far, 20, 53), 0.05);
        CameraClip offscreen = CameraSynth.synthesize(t, 0, 30, Styles.resolve("high-crane").get(0), 0);
        assertEquals(0, Framing.sizeScore(t, offscreen, 20, 70), 1e-12);
    }
    @Test
    void projectToScreenMatchesPython() {
        // cinefile.framing.project_to_screen
        assertArrayEquals(new double[] {0.580333325379244, 0.5}, Framing.projectToScreen(new double[] {0, 64, 0, 0, 0}, new double[] {1, 64, 5}), 1e-6);
        assertArrayEquals(new double[] {1.8247121847104981, 1.6664993965442145}, Framing.projectToScreen(new double[] {10, 70, 3, 45, -10}, new double[] {14, 66, 12}), 1e-6);
        assertNull(Framing.projectToScreen(new double[] {0, 64, 0, 0, 0}, new double[] {0, 64, -5}));
        assertNull(Framing.projectToScreen(new double[] {0, 64, 0, 0, 0}, new double[] {0, 64, 0.01}));
    }

    @Test
    void cameraAtMatchesPython() throws Exception {
        // cinefile.framing.camera_at on locked-dolly (+X) and orbit-reveal (-X) clips
        CameraClip dolly = CameraSynth.synthesize(CameraSynthTest.walk(1), 0, 30, Styles.resolve("locked-dolly").get(0), 0);
        assertArrayEquals(new double[] {4.558845726811989, 66.62, -4.296152422706632, 42.68038349181982, 0.0}, Framing.cameraAt(dolly, 0), 1e-6);
        assertArrayEquals(new double[] {12.058845726811988, 66.62, -4.296152422706632, 42.68038349181982, 0.0}, Framing.cameraAt(dolly, 15), 1e-6);
        assertArrayEquals(new double[] {19.558845726811988, 66.62, -4.296152422706632, 42.68038349181982, 0.0}, Framing.cameraAt(dolly, 40), 1e-6);
        CameraClip orbit = CameraSynth.synthesize(CameraSynthTest.walk(-1), 0, 30, Styles.resolve("orbit-reveal").get(0), 0);
        assertArrayEquals(new double[] {0.0003520777580985346, 67.62, -6.3546460717605235, 9.63759477388825, 12.50154859206157}, Framing.cameraAt(orbit, 15), 1e-6);
    }

    @Test
    void statsAndMultiplierMatchPython() throws Exception {
        // cinefile.framing.framing_stats / framing_multiplier
        FramingStats a = Framing.stats(CameraSynthTest.walk(1), CameraSynth.synthesize(CameraSynthTest.walk(1), 0, 30, Styles.resolve("locked-dolly").get(0), 0), 20);
        assertEquals(1.0, a.onscreenFrac(), 1e-6);
        assertEquals(0.0, a.centerFrac(), 1e-6);
        assertEquals(0.25, a.thirdsFrac(), 1e-6);
        assertEquals(0.24027542005488162, a.score(), 1e-6);
        assertEquals(0.25, a.nearFrac(), 1e-6);
        assertEquals(0.23409639701920854, Framing.multiplier(a), 1e-6);
        FramingStats b = Framing.stats(CameraSynthTest.walk(-1), CameraSynth.synthesize(CameraSynthTest.walk(-1), 0, 30, Styles.resolve("orbit-reveal").get(0), 0), 20);
        assertEquals(0.65, b.onscreenFrac(), 1e-6);
        assertEquals(0.25, b.centerFrac(), 1e-6);
        assertEquals(0.22356442294724851, b.score(), 1e-6);
        assertEquals(0.22824754803153696, Framing.multiplier(b), 1e-6);
        FramingStats c = Framing.stats(CameraSynthTest.walk(1), CameraSynth.synthesize(CameraSynthTest.walk(1), 0, 30, Styles.resolve("high-crane").get(0), 0), 20);
        assertEquals(0.0, c.score(), 1e-6);
        assertEquals(0.15, Framing.multiplier(c), 1e-6);
        assertEquals(0.0, a.insideFrac());
        assertEquals(0.0, a.occludedFrac());
    }

    @Test
    void multiplierUsesWorldFactors() {
        // framing_multiplier(FramingStats(0.9, 0.4, 0.2, 0.8, ...))
        FramingStats plain = new FramingStats(0.9, 0.4, 0.2, 0.8, 0, 0);
        assertEquals(0.86, Framing.multiplier(plain), 1e-6);
        FramingStats w = new FramingStats(0.9, 0.3, 0.1, 0.8, 0, 0).withWorld(0.5, 0.5);
        assertEquals(0.5, w.insideFrac());
        assertEquals(0.5, w.occludedFrac());
        assertEquals(0.03762499999999999, Framing.multiplier(w), 1e-6);
    }
}
