package com.cinefile.core;

import java.util.List;

/** Camera clip synthesis from trajectory + style (port of cinefile/camera.py). */
public final class CameraSynth {
    /** Shortest and longest straight-dolly travel, in blocks. */
    private static final double STRAIGHT_MIN_TRAVEL = 2.0, STRAIGHT_MAX_TRAVEL = 5.0;
    /** Share of the player's sideways movement a straight dolly follows. */
    private static final double STRAIGHT_SIDEWAYS_SHARE = 0.1;
    /** How far a top-down camera drifts vertically between its two keys. */
    private static final double TOP_DOWN_DRIFT = 1.5;
    private static final double TOP_DOWN_PITCH = 90.0;

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
        List<CameraClip> all = synthesizeAll(t, t0, t1, s, score, level, fovDeg);
        return all.isEmpty() ? null : all.get(0);
    }

    /** Every clip a style makes for this window: forward then backward for the two directional styles. */
    public static List<CameraClip> synthesizeAll(Trajectory t, int t0, int t1, Style s, double score, FocusLevel level, double fovDeg) {
        if (level == FocusLevel.AUTO) throw new IllegalArgumentException("AUTO must be expanded into candidates");
        if (level != null) s = Styles.tuned(s, fovDeg);
        FocusLevel fixed = level == FocusLevel.STYLE ? null : level;
        double fovScale = fixed == null ? 1 : Styles.fovScale(fovDeg);
        Pose p0 = t.sample(t0), p1 = t.sample(t1);
        if (p0 == null || p1 == null) return List.of();
        double side;
        if (s.thirdsSide().equals("left")) side = -1.0;
        else if (s.thirdsSide().equals("right")) side = 1.0;
        else side = (p1.x() - p0.x()) >= 0 ? 1.0 : -1.0;

        Double shotYaw = s.yawLock() ? p0.yaw() : null;
        if (Styles.STRAIGHT_DOLLY.equals(s.id())) {
            double[] k0 = offsetCamera(p0, s, side, shotYaw, fixed, fovScale);
            return List.of(straightDolly(p0, p1, t0, t1, s, score, k0, 1),
                    straightDolly(p0, p1, t0, t1, s, score, k0, -1));
        }
        if (Styles.TOP_DOWN.equals(s.id())) {
            return List.of(topDown(p0, p1, t0, t1, s, score, 1), topDown(p0, p1, t0, t1, s, score, -1));
        }
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
        return List.of(new CameraClip(t0, t1,
                new CameraKey(t0, k0[0], k0[1], k0[2], k0[3], pitch0),
                new CameraKey(t1, x1, y1, z1, yaw1, pitch1), score, s.id()));
    }

    /** Level, straight move along the camera's own view from the same start key, forward (dir 1) or back (dir -1). */
    private static CameraClip straightDolly(Pose p0, Pose p1, int t0, int t1, Style s, double score, double[] k0, int dir) {
        double yawRad = Math.toRadians(k0[3]);
        double fx = -Math.sin(yawRad), fz = Math.cos(yawRad);
        double rx = Math.cos(yawRad), rz = Math.sin(yawRad);
        double dx = p1.x() - p0.x(), dz = p1.z() - p0.z();
        double along = dx * fx + dz * fz;
        double sideways = dx * rx + dz * rz;
        double travel = Math.clamp(Math.abs(along) * s.dollyScale(), STRAIGHT_MIN_TRAVEL, STRAIGHT_MAX_TRAVEL);
        double x1 = k0[0] + fx * dir * travel + rx * sideways * STRAIGHT_SIDEWAYS_SHARE;
        double z1 = k0[2] + fz * dir * travel + rz * sideways * STRAIGHT_SIDEWAYS_SHARE;
        return new CameraClip(t0, t1,
                new CameraKey(t0, k0[0], k0[1], k0[2], k0[3], k0[4]),
                new CameraKey(t1, x1, k0[1], z1, k0[3], k0[4]), score, s.id());
    }

    /** Straight down at the player's midpoint, drifting down (dir 1) or up (dir -1). */
    private static CameraClip topDown(Pose p0, Pose p1, int t0, int t1, Style s, double score, int dir) {
        double x = (p0.x() + p1.x()) / 2;
        double z = (p0.z() + p1.z()) / 2;
        double startY = Math.max(p0.y(), p1.y()) + s.height();
        double endY = startY - dir * TOP_DOWN_DRIFT;
        return new CameraClip(t0, t1,
                new CameraKey(t0, x, startY, z, p0.yaw(), TOP_DOWN_PITCH),
                new CameraKey(t1, x, endY, z, p0.yaw(), TOP_DOWN_PITCH), score, s.id());
    }
}
