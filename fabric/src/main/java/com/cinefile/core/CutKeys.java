package com.cinefile.core;

public final class CutKeys {
    public static final int CUT_BASE = 1_000_000;
    public static final float CUT_TICKRATE = 1e9f;

    private CutKeys() {}

    public static int[] ticksFor(int cutLengthTicks) {
        return cutLengthTicks <= 0 ? new int[] {CUT_BASE, CUT_BASE + 1} : new int[] {0, cutLengthTicks};
    }

    public static boolean isCut(int leftKeyframeTicks) {
        return leftKeyframeTicks >= CUT_BASE;
    }

    public static boolean isCutSpeed(float speed) {
        return speed >= CUT_TICKRATE / 20f;
    }

    public static int[] rangeFromPlayhead(int playhead, int totalTicks) {
        int end = Math.min(playhead + 100, totalTicks);
        return end - playhead < 1 ? null : new int[] {playhead, end};
    }
}
