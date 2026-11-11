package com.cinefile.core;

public record Style(String id, String description, double distance, double height, double lateral, double pitch,
                    boolean yawLock, double orbitDegrees, double dollyScale, String thirdsSide) {}
