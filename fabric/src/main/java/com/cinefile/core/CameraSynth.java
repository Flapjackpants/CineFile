package com.cinefile.core;

/** Camera clip synthesis from trajectory + style (port of cinefile/camera.py). */
public final class CameraSynth {
    private CameraSynth() {}

    static double wrapDegrees(double deg) {
        double m = (deg + 180.0) % 360.0;
        if (m < 0) m += 360.0;
        return m - 180.0;
    }

    /** Yaw and pitch looking from one point to another. */
    private static double[] lookYawPitch(double fx, double fy, double fz, double tx, double ty, double tz) {
        double dx = tx - fx, dy = ty - fy, dz = tz - fz;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double yaw = -Math.toDegrees(Math.atan2(dx, dz));
        double pitch = horiz > 1e-6 ? -Math.toDegrees(Math.atan2(dy, horiz)) : 0.0;
        return new double[] {yaw, pitch};
    }

    /** Camera behind and beside the player: x, y, z, yaw, pitch. */
    private static double[] offsetCamera(Pose p, Style s, double side, Double yawOverride, FocusLevel level, double fovScale) {
        double yawRad = Math.toRadians(yawOverride != null ? yawOverride : p.yaw());
        double fx = -Math.sin(yawRad), fz = Math.cos(yawRad);
        double rx = Math.cos(yawRad), rz = Math.sin(yawRad);
        double cx = p.x() - fx * s.distance() + rx * s.lateral() * side;
        double cy = level == null ? p.y() + s.height() + 1.62
                : p.y() + level.aimHeight() + (level.cameraHeight() - level.aimHeight()) * fovScale;
        double cz = p.z() - fz * s.distance() + rz * s.lateral() * side;
        double[] yp = lookYawPitch(cx, cy, cz, p.x() + fx * 2.0, p.y() + (level == null ? 1.4 : level.aimHeight()), p.z() + fz * 2.0);
        return new double[] {cx, cy, cz, yp[0], level == null && s.yawLock() ? s.pitch() : yp[1]};
    }

    /** Two-keyframe clip from t0 to t1; null if the trajectory has no pose there. */
    public static CameraClip synthesize(Trajectory t, int t0, int t1, Style s, double score) {
        return synthesize(t, t0, t1, s, score, null, 1);
    }

    public static CameraClip synthesize(Trajectory t, int t0, int t1, Style s, double score, FocusLevel level, double fovDeg) {
        if (level == FocusLevel.AUTO) throw new IllegalArgumentException("AUTO must be expanded into candidates");
        if (level != null) s = Styles.tuned(s, fovDeg);
        FocusLevel fixed = level == FocusLevel.STYLE ? null : level;
        double fovScale = fixed == null ? 1 : Styles.fovScale(fovDeg);
        Pose p0 = t.sample(t0), p1 = t.sample(t1);
        if (p0 == null || p1 == null) return null;
        double side;
        if (s.thirdsSide().equals("left")) side = -1.0;
        else if (s.thirdsSide().equals("right")) side = 1.0;
        else side = (p1.x() - p0.x()) >= 0 ? 1.0 : -1.0;

        Double shotYaw = s.yawLock() ? p0.yaw() : null;
        double[] k0 = offsetCamera(p0, s, side, shotYaw, fixed, fovScale);
        double[] k1 = offsetCamera(p1, s, side, shotYaw, fixed, fovScale);
        double yaw1, pitch0 = k0[4], pitch1 = k1[4];
        if (s.yawLock()) {
            yaw1 = k0[3];
            pitch0 = k0[4];
            pitch1 = k0[4];
        } else {
            yaw1 = wrapDegrees(k0[3] + s.orbitDegrees() * side);
        }
        double sc = s.dollyScale();
        double x1 = k0[0] + (k1[0] - k0[0]) * sc;
        double y1 = k0[1] + (k1[1] - k0[1]) * sc;
        double z1 = k0[2] + (k1[2] - k0[2]) * sc;
        return new CameraClip(t0, t1,
                new CameraKey(t0, k0[0], k0[1], k0[2], k0[3], pitch0),
                new CameraKey(t1, x1, y1, z1, yaw1, pitch1), score, s.id());
    }
}
