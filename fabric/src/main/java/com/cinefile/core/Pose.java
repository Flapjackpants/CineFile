package com.cinefile.core;

public record Pose(double x, double y, double z, double yaw, double pitch) {
    public double dist(Pose o) {
        double dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
