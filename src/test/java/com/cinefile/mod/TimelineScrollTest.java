package com.cinefile.mod;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TimelineScrollTest {
    @Test
    void newScrollClamps() {
        assertEquals(0f, TimelineScroll.newScroll(0f, 1f, 500f, 20f));
        assertEquals(500f, TimelineScroll.newScroll(500f, -1f, 500f, 20f));
        assertEquals(40f, TimelineScroll.newScroll(100f, 1f, 500f, 20f));
        assertEquals(160f, TimelineScroll.newScroll(100f, -1f, 500f, 20f));
    }

    @Test
    void rebindOnlyFromDefaults() {
        assertTrue(TimelineScroll.shouldRebind(false, false, false, false, false, true, false, false));
        assertTrue(TimelineScroll.shouldRebind(false, false, false, false, false, false, false, false));
        assertFalse(TimelineScroll.shouldRebind(false, false, true, false, false, true, false, false));
        assertFalse(TimelineScroll.shouldRebind(false, false, false, false, false, false, true, false));
    }
}
