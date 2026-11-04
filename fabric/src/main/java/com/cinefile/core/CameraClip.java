package com.cinefile.core;

public record CameraClip(int startTick, int endTick, CameraKey a, CameraKey b, double score, String styleId) {
    public CameraClip withScore(double s) {
        return new CameraClip(startTick, endTick, a, b, s, styleId);
    }

    /** Copy with the end key moved to (x, y, z), keeping its tick, yaw and pitch. */
    public CameraClip withEnd(double x, double y, double z) {
        return new CameraClip(startTick, endTick, a, new CameraKey(b.tick(), x, y, z, b.yaw(), b.pitch()), score, styleId);
    }
}
