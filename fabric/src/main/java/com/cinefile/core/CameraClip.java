package com.cinefile.core;

public record CameraClip(int startTick, int endTick, CameraKey a, CameraKey b, double score, String styleId) {
    public CameraClip withScore(double s) {
        return new CameraClip(startTick, endTick, a, b, s, styleId);
    }
}
