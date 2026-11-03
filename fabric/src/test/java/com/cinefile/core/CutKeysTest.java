package com.cinefile.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class CutKeysTest {
    @Test
    void gapsMergeUnorderedOverlappingAndTouchingRanges() {
        List<int[]> ranges = List.of(new int[] {20, 30}, new int[] {0, 10},
                new int[] {8, 20}, new int[] {35, 40}, new int[] {44, 50}, new int[] {55, 60});
        List<int[]> gaps = CutKeys.gaps(ranges, 5);
        assertEquals(2, gaps.size());
        assertArrayEquals(new int[] {30, 35}, gaps.get(0));
        assertArrayEquals(new int[] {50, 55}, gaps.get(1));
        assertArrayEquals(new int[] {20, 30}, ranges.get(0));
        assertArrayEquals(new int[] {0, 10}, ranges.get(1));
        assertTrue(CutKeys.gaps(List.of(), 5).isEmpty());
        assertTrue(CutKeys.gaps(List.of(new int[] {0, 10}), 5).isEmpty());
    }

    @Test
    void cutSpeedOnlyMatchesCutRate() {
        assertTrue(CutKeys.isCutSpeed(CutKeys.CUT_TICKRATE / 20f));
        assertFalse(CutKeys.isCutSpeed(1f));
        assertFalse(CutKeys.isCutSpeed(100000f));
    }

    @Test
    void zeroLengthIsSentinel() {
        assertArrayEquals(new int[] {1000000, 1000001}, CutKeys.ticksFor(0));
        assertTrue(CutKeys.isCut(1000000));
    }

    @Test
    void setLengthIsPlainTimelapse() {
        assertArrayEquals(new int[] {0, 1}, CutKeys.ticksFor(1));
        assertArrayEquals(new int[] {0, 20}, CutKeys.ticksFor(20));
        assertFalse(CutKeys.isCut(0));
    }

    @Test
    void rangeFromPlayhead() {
        assertArrayEquals(new int[] {50, 150}, CutKeys.rangeFromPlayhead(50, 1000));
        assertArrayEquals(new int[] {950, 1000}, CutKeys.rangeFromPlayhead(950, 1000));
        assertNull(CutKeys.rangeFromPlayhead(1000, 1000));
    }
}
