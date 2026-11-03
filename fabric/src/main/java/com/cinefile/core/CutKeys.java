package com.cinefile.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    public static boolean contains(List<int[]> ranges, int tick) {
        for (int[] range : ranges) {
            if (range[0] <= tick && tick < range[1]) return true;
        }
        return false;
    }

    public static boolean markerDue(boolean wasMuted, boolean muted, int sentTick, int tick) {
        return muted != wasMuted || (muted && tick != sentTick);
    }

    public static List<int[]> gaps(List<int[]> ranges, int minGap) {
        List<int[]> sorted = new ArrayList<>();
        for (int[] range : ranges) sorted.add(range.clone());
        sorted.sort(Comparator.comparingInt(r -> r[0]));
        List<int[]> gaps = new ArrayList<>();
        if (sorted.isEmpty()) return gaps;
        int end = sorted.get(0)[1];
        for (int i = 1; i < sorted.size(); i++) {
            int[] range = sorted.get(i);
            if (range[0] > end && range[0] - end >= minGap) gaps.add(new int[] {end, range[0]});
            end = Math.max(end, range[1]);
        }
        return gaps;
    }

    public static int[] rangeFromPlayhead(int playhead, int totalTicks) {
        int end = Math.min(playhead + 100, totalTicks);
        return end - playhead < 1 ? null : new int[] {playhead, end};
    }
}
