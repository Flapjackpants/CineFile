package com.cinefile.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CutKeysTest {
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
