package com.cinefile.core;

import java.util.List;
import java.util.Random;

/** Built-in style presets (values of cinefile/styles/*.yaml). */
public final class Styles {
    public static final String AUTO = "auto";
    public static final String STRAIGHT_DOLLY = "straight-dolly", TOP_DOWN = "top-down";

    private static final List<Style> ALL = List.of(
            new Style("hero-low", "Low camera looking up for scale on tall builds.", 7.5, -0.8, 2.0, 12.0, true, 0.0, 0.8, "auto"),
            new Style("high-crane", "Elevated overview of large projects.", 12.0, 6.0, 2.5, -25.0, true, 0.0, 1.2, "auto"),
            new Style("locked-dolly", "Ep34-style locked-yaw over-the-shoulder dolly with rule-of-thirds framing.", 6.0, 1.0, 1.8, 0.0, true, 0.0, 1.0, "auto"),
            new Style("orbit-reveal", "Gentle yaw arc across the two keyframes around the subject.", 8.0, 2.0, 0.5, -8.0, false, 35.0, 0.6, "auto"),
            new Style("side-track", "Strong lateral thirds framing, parallel tracking.", 5.5, 1.2, 3.5, -2.0, true, 0.0, 1.0, "left"),
            new Style(STRAIGHT_DOLLY, "Level straight-line move forward or back, with little side-to-side motion.", 6.0, 1.0, 1.8, 0.0, true, 0.0, 1.0, "auto"),
            new Style(TOP_DOWN, "Looks straight down at the player while moving slightly up or down.", 0.0, 10.0, 0.0, 90.0, true, 0.0, 1.0, "auto"));

    private Styles() {}

    public static List<Style> all() {
        return ALL;
    }

    public static List<Style> resolve(String id) {
        if (AUTO.equals(id)) return ALL;
        for (Style s : ALL) if (s.id().equals(id)) return List.of(s);
        throw new IllegalArgumentException("Unknown style '" + id + "'");
    }

    /** Deterministic jittered copy of a style; index 0 is the unchanged preset. */
    public static Style variant(Style s, int index, int t0) {
        if (index == 0) return s;
        Random rng = new Random((s.id() + ":" + t0 + ":" + index).hashCode());
        return new Style(s.id(), s.description(),
                s.distance() * uniform(rng, 0.8, 1.25),
                s.height() + uniform(rng, -1.0, 1.0),
                s.lateral() * uniform(rng, 0.6, 1.4),
                s.pitch() + uniform(rng, -6.0, 6.0),
                s.yawLock(),
                s.orbitDegrees() * uniform(rng, 0.7, 1.3),
                s.dollyScale() * uniform(rng, 0.85, 1.15),
                s.thirdsSide());
    }

    public static double fovScale(double fovDeg) {
        return Math.clamp(Math.tan(Math.toRadians(53) / 2) / Math.tan(Math.toRadians(fovDeg) / 2), 0.4, 2.5);
    }

    public static Style tuned(Style s, double fovDeg) {
        double scale = fovScale(fovDeg);
        double orbit = Math.toDegrees(Math.atan(Math.tan(Math.toRadians(s.orbitDegrees()))
                * Math.tan(Math.toRadians(fovDeg) / 2) / Math.tan(Math.toRadians(70) / 2)));
        return new Style(s.id(), s.description(), s.distance() * scale, s.height() * scale,
                s.lateral() * scale, s.pitch(), s.yawLock(), orbit, s.dollyScale(), s.thirdsSide());
    }

    private static double uniform(Random rng, double lo, double hi) {
        return lo + (hi - lo) * rng.nextDouble();
    }

    /** Distinct clips one try of a style makes across this many focus levels. */
    public static int clipsPerTry(Style s, int levels) {
        if (TOP_DOWN.equals(s.id())) return 2; // focus levels don't change it
        return STRAIGHT_DOLLY.equals(s.id()) ? 2 * levels : levels;
    }

    public static int triesPerStyle(int tries, int nStyles) {
        return Math.max(1, (tries + nStyles / 2) / nStyles);
    }
}
