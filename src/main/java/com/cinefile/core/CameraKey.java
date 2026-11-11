package com.cinefile.core;

public record CameraKey(int tick, double x, double y, double z, double yaw, double pitch) {
    /** Flashback treats keyframe Y as the player's feet and adds this when rendering. */
    public static final double EYE_HEIGHT = 1.62;

    /** Keyframe Y for Flashback: the eye position lowered to the player's feet. */
    public double keyframeY() {
        return y - EYE_HEIGHT;
    }

    /** Eye position for a keyframe Y read back from Flashback. */
    public static double eyeY(double keyframeY) {
        return keyframeY + EYE_HEIGHT;
    }
}
