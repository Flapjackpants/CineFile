package com.cinefile.core;

import java.util.List;

/** Camera and aim heights relative to the player's feet. */
public enum FocusLevel {
    AUTO(0, 0, 1), CENTER(0.9, 0.9, 1), EYE(1.62, 1.62, 0.95),
    HIGH(3, 0.9, 0.85), LOW(0.2, 0.9, 0.85), STYLE(0, 0, 1);

    private final double cameraHeight, aimHeight, weight;

    FocusLevel(double cameraHeight, double aimHeight, double weight) {
        this.cameraHeight = cameraHeight;
        this.aimHeight = aimHeight;
        this.weight = weight;
    }

    public double cameraHeight() { return cameraHeight; }
    public double aimHeight() { return aimHeight; }
    public double weight() { return weight; }

    public List<FocusLevel> candidates() {
        return this == AUTO ? List.of(CENTER, EYE, HIGH, LOW) : List.of(this);
    }
}
