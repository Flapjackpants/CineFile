package com.cinefile.core;

public record FramingStats(double onscreenFrac, double centerFrac, double thirdsFrac, double score, double insideFrac, double occludedFrac) {
    public double nearFrac() {
        return centerFrac + thirdsFrac;
    }

    public FramingStats withWorld(double insideFrac, double occludedFrac) {
        return new FramingStats(onscreenFrac, centerFrac, thirdsFrac, score, insideFrac, occludedFrac);
    }
}
