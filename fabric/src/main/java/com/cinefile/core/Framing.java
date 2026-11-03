package com.cinefile.core;

/** Screen-space framing evaluation of synthesized clips (port of cinefile/framing.py, without the world branch). */
public final class Framing {
    private static final double ASPECT = 16.0 / 9.0;
    private static final double SUBJECT_HEIGHT = 1.0;
    private static final double CENTER_SIGMA = 0.15;
    private static final double THIRDS_SIGMA = 0.10;
    private static final double THIRDS_WEIGHT = 0.85;
    private static final double[][] THIRDS_POINTS = {{1.0 / 3, 1.0 / 3}, {2.0 / 3, 1.0 / 3}, {1.0 / 3, 2.0 / 3}, {2.0 / 3, 2.0 / 3}};

    private Framing() {}

    /** {u, v} of a world point for camera {x, y, z, yaw, pitch}; null if behind the camera. */
    public static double[] projectToScreen(double[] cam, double[] p) {
        return projectToScreen(cam, p, 70.0);
    }

    public static double[] projectToScreen(double[] cam, double[] p, double fovDeg) {
        double sy = Math.sin(Math.toRadians(cam[3])), cyw = Math.cos(Math.toRadians(cam[3]));
        double sp = Math.sin(Math.toRadians(cam[4])), cp = Math.cos(Math.toRadians(cam[4]));
        double[] f = {-sy * cp, -sp, cyw * cp};
        double[] r = {cyw, 0.0, sy};
        double[] up = {-sp * sy, cp, sp * cyw};
        double[] rel = {p[0] - cam[0], p[1] - cam[1], p[2] - cam[2]};
        double depth = dot(rel, f);
        if (depth <= 0.05) return null;
        double xc = dot(rel, r), yc = dot(rel, up);
        double th = Math.tan(Math.toRadians(fovDeg) / 2);
        return new double[] {0.5 + 0.5 * xc / (depth * th * ASPECT), 0.5 - 0.5 * yc / (depth * th)};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /** Smoothstep-interpolated camera {x, y, z, yaw, pitch} at a tick, clamped to the clip. */
    public static double[] cameraAt(CameraClip c, int tick) {
        int span = c.endTick() - c.startTick();
        double a = span != 0 ? (double) (tick - c.startTick()) / span : 0.0;
        a = Math.min(1.0, Math.max(0.0, a));
        double s = a * a * (3 - 2 * a);
        CameraKey k0 = c.a(), k1 = c.b();
        return new double[] {
            k0.x() + (k1.x() - k0.x()) * s, k0.y() + (k1.y() - k0.y()) * s, k0.z() + (k1.z() - k0.z()) * s,
            k0.yaw() + (k1.yaw() - k0.yaw()) * s, k0.pitch() + (k1.pitch() - k0.pitch()) * s};
    }

    public static FramingStats stats(Trajectory t, CameraClip c, int samples) {
        return stats(t, c, samples, 70.0);
    }

    public static FramingStats stats(Trajectory t, CameraClip c, int samples, double fovDeg) {
        int span = c.endTick() - c.startTick();
        int n = 0, on = 0, cen = 0, thi = 0;
        double total = 0;
        for (int i = 0; i < samples; i++) {
            int tick = (int) Math.rint(c.startTick() + (double) i * span / (samples - 1));
            Pose p = t.sample(tick);
            if (p == null) continue;
            n++;
            double[] uv = projectToScreen(cameraAt(c, tick), new double[] {p.x(), p.y() + SUBJECT_HEIGHT, p.z()}, fovDeg);
            if (uv == null || !(0 <= uv[0] && uv[0] <= 1 && 0 <= uv[1] && uv[1] <= 1)) continue;
            on++;
            double dc = Math.hypot(uv[0] - 0.5, uv[1] - 0.5);
            double dt = Double.MAX_VALUE;
            for (double[] q : THIRDS_POINTS) dt = Math.min(dt, Math.hypot(uv[0] - q[0], uv[1] - q[1]));
            if (dc <= CENTER_SIGMA) cen++;
            else if (dt <= THIRDS_SIGMA) thi++;
            total += Math.max(Math.exp(-Math.pow(dc / CENTER_SIGMA, 2)), THIRDS_WEIGHT * Math.exp(-Math.pow(dt / THIRDS_SIGMA, 2)));
        }
        if (n == 0) return new FramingStats(0, 0, 0, 0, 0, 0);
        return new FramingStats((double) on / n, (double) cen / n, (double) thi / n, total / n, 0, 0);
    }

    /** Mean normalized subject height over on-screen samples. */
    public static double sizeScore(Trajectory t, CameraClip c, int samples, double fovDeg) {
        int span = c.endTick() - c.startTick();
        int on = 0;
        double total = 0;
        for (int i = 0; i < samples; i++) {
            int tick = (int) Math.rint(c.startTick() + (double) i * span / (samples - 1));
            Pose p = t.sample(tick);
            if (p == null) continue;
            double[] cam = cameraAt(c, tick);
            double[] uv = projectToScreen(cam, new double[] {p.x(), p.y() + SUBJECT_HEIGHT, p.z()}, fovDeg);
            if (uv == null || !(0 <= uv[0] && uv[0] <= 1 && 0 <= uv[1] && uv[1] <= 1)) continue;
            on++;
            double[] feet = projectToScreen(cam, new double[] {p.x(), p.y(), p.z()}, fovDeg);
            double[] head = projectToScreen(cam, new double[] {p.x(), p.y() + 1.8, p.z()}, fovDeg);
            if (feet != null && head != null) total += Math.min(1, Math.max(0, feet[1] - head[1]) / 0.25);
        }
        return on == 0 ? 0 : total / on;
    }

    public static double multiplier(FramingStats s) {
        double m = 0.3 + 0.7 * s.score();
        if (s.nearFrac() < 0.5) m *= 0.5;
        return m * Math.max(0.05, Math.pow(1 - s.insideFrac(), 3)) * (1 - 0.6 * s.occludedFrac());
    }
}
