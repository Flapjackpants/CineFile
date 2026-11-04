package com.cinefile.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CameraKeyTest {
    @Test
    void keyframeYIsBelowEye() {
        assertEquals(64.0, new CameraKey(0, 1, 65.62, 3, 0, 0).keyframeY(), 1e-9);
    }

    @Test
    void eyeYInvertsKeyframeY() {
        CameraKey k = new CameraKey(5, 1, 65.62, 3, 0, 0);
        assertEquals(k.y(), CameraKey.eyeY(k.keyframeY()), 1e-9);
    }

    @Test
    void centerLevelRendersAtPlayerCenter() throws Exception {
        Trajectory t = Replays.trajectory(tick -> new Pose(0, 64, 0, 0, 0), 31);
        CameraClip c = CameraSynth.synthesize(t, 0, 30, Styles.resolve("locked-dolly").get(0), 0, FocusLevel.CENTER, 53);
        assertEquals(64.9, c.a().keyframeY() + CameraKey.EYE_HEIGHT, 1e-6);
        assertEquals(63.28, c.a().keyframeY(), 1e-6);
    }
}
