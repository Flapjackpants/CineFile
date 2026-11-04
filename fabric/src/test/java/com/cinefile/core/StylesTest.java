package com.cinefile.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class StylesTest {
    @Test
    void fovTuningPreservesMetadataAndScalesGeometry() {
        assertEquals(1, Styles.fovScale(53), 1e-12);
        assertEquals(0.712, Styles.fovScale(70), 0.002);
        assertEquals(0.4, Styles.fovScale(120));
        assertEquals(2.5, Styles.fovScale(5));
        Style dolly = Styles.resolve("locked-dolly").get(0);
        Style orbit = Styles.resolve("orbit-reveal").get(0);
        Style at53 = Styles.tuned(orbit, 53);
        assertEquals(orbit.id(), at53.id());
        assertEquals(orbit.description(), at53.description());
        assertEquals(orbit.distance(), at53.distance(), 1e-12);
        assertEquals(orbit.height(), at53.height(), 1e-12);
        assertEquals(orbit.lateral(), at53.lateral(), 1e-12);
        assertEquals(orbit.pitch(), at53.pitch());
        assertEquals(orbit.yawLock(), at53.yawLock());
        assertEquals(orbit.dollyScale(), at53.dollyScale());
        assertEquals(orbit.thirdsSide(), at53.thirdsSide());
        assertEquals(26.5, at53.orbitDegrees(), 0.1);
        assertEquals(6 * Styles.fovScale(70), Styles.tuned(dolly, 70).distance(), 1e-12);
        assertEquals(4.27, Styles.tuned(dolly, 70).distance(), 0.02);
        assertEquals(35, Styles.tuned(orbit, 70).orbitDegrees(), 1e-12);
        assertEquals(0, Styles.tuned(dolly, 53).orbitDegrees());
    }
    @Test
    void allIsSortedWithYamlValues() {
        List<Style> all = Styles.all();
        assertEquals(List.of("hero-low", "high-crane", "locked-dolly", "orbit-reveal", "side-track", "straight-dolly", "top-down"),
            all.stream().map(Style::id).toList());
        Style hero = all.get(0), orbit = all.get(3), side = all.get(4);
        assertEquals("Low camera looking up for scale on tall builds.", hero.description());
        assertEquals(7.5, hero.distance());
        assertEquals(-0.8, hero.height());
        assertEquals(2.0, hero.lateral());
        assertEquals(12.0, hero.pitch());
        assertTrue(hero.yawLock());
        assertEquals(0.8, hero.dollyScale());
        assertEquals("auto", hero.thirdsSide());
        Style crane = all.get(1);
        assertEquals(new Style("high-crane", "Elevated overview of large projects.", 12.0, 6.0, 2.5, -25.0, true, 0.0, 1.2, "auto"), crane);
        Style dolly = all.get(2);
        assertEquals(new Style("locked-dolly", "Ep34-style locked-yaw over-the-shoulder dolly with rule-of-thirds framing.", 6.0, 1.0, 1.8, 0.0, true, 0.0, 1.0, "auto"), dolly);
        assertEquals(new Style("orbit-reveal", "Gentle yaw arc across the two keyframes around the subject.", 8.0, 2.0, 0.5, -8.0, false, 35.0, 0.6, "auto"), orbit);
        assertEquals(new Style("side-track", "Strong lateral thirds framing, parallel tracking.", 5.5, 1.2, 3.5, -2.0, true, 0.0, 1.0, "left"), side);
        Style straight = all.get(5);
        assertEquals(new Style("straight-dolly", "Level straight-line move forward or back, with little side-to-side motion.",
            6.0, 1.0, 1.8, 0.0, true, 0.0, 1.0, "auto"), straight);
        Style top = all.get(6);
        assertEquals(new Style("top-down", "Looks straight down at the player while moving slightly up or down.",
            0.0, 10.0, 0.0, 90.0, true, 0.0, 1.0, "auto"), top);
        assertEquals("straight-dolly", Styles.STRAIGHT_DOLLY);
        assertEquals("top-down", Styles.TOP_DOWN);
    }

    @Test
    void resolveHandlesAutoSingleAndUnknown() {
        assertEquals(Styles.all(), Styles.resolve(Styles.AUTO));
        assertEquals(List.of(Styles.all().get(2)), Styles.resolve("locked-dolly"));
        assertThrows(IllegalArgumentException.class, () -> Styles.resolve("nope"));
    }

    @Test
    void variantIsDeterministicAndBounded() {
        for (Style s : Styles.all()) {
            assertSame(s, Styles.variant(s, 0, 40));
            for (int i = 1; i <= 200; i++) {
                Style v = Styles.variant(s, i, 40);
                assertEquals(v, Styles.variant(s, i, 40));
                assertEquals(s.id(), v.id());
                assertEquals(s.yawLock(), v.yawLock());
                assertEquals(s.thirdsSide(), v.thirdsSide());
                assertTrue(v.distance() >= s.distance() * 0.8 - 1e-9 && v.distance() <= s.distance() * 1.25 + 1e-9);
                assertTrue(Math.abs(v.height() - s.height()) <= 1.0 + 1e-9);
                assertTrue(v.lateral() >= s.lateral() * 0.6 - 1e-9 && v.lateral() <= s.lateral() * 1.4 + 1e-9);
                assertTrue(Math.abs(v.pitch() - s.pitch()) <= 6.0 + 1e-9);
                assertTrue(v.orbitDegrees() >= s.orbitDegrees() * 0.7 - 1e-9 && v.orbitDegrees() <= s.orbitDegrees() * 1.3 + 1e-9);
                assertTrue(v.dollyScale() >= s.dollyScale() * 0.85 - 1e-9 && v.dollyScale() <= s.dollyScale() * 1.15 + 1e-9);
            }
        }
        Style s = Styles.all().get(0);
        assertNotEquals(Styles.variant(s, 1, 40), Styles.variant(s, 2, 40));
        assertNotEquals(Styles.variant(s, 1, 40), Styles.variant(s, 1, 41));
    }

    @Test
    void triesPerStyleMatchesPython() {
        // cinefile.styles_loader.tries_per_style
        assertEquals(1, Styles.triesPerStyle(5, 5));
        assertEquals(1, Styles.triesPerStyle(1, 5));
        assertEquals(2, Styles.triesPerStyle(12, 5));
    }
}
