package com.cinefile.core;

import java.util.List;
import java.util.function.Consumer;

/** What the auto-create engine needs from the replay editor; implemented against Flashback elsewhere. */
public interface ReplayDriver {
    void seek(int tick);                      // non-blocking
    boolean atTick(int tick);                 // true once the client shows that tick
    String dimension();
    double fovDegrees(); // vertical FOV the replay renders with
    boolean isLoaded(double[] pos);           // {x,y,z}
    boolean blocked(double[] from, double[] to); // true if a block is hit along the segment, or `from` is inside one
    boolean clips(double[] from, double[] to); // true if a box around `to` overlaps a block's collision shape, or a block's collision shape is hit along the segment
    Object showCandidate(CameraClip clip);    // tentative track: the screen follows it, not undoable
    void discardCandidate(Object handle);
    void commitCandidate(Object handle, CameraClip clip); // permanent, one undo entry
    double[] cameraAt(Object handle, int tick); // {x,y,z,yaw,pitch} as Flashback will render it
    void capture(Consumer<byte[]> png);       // called later with a PNG, or null on failure
    void addGap(int t0, int t1);              // fill mode is the driver's concern
    void setExportRange(int t0, int t1);      // applied only if the scene has no range set
    List<int[]> occupiedRanges();             // {min,max} per existing track
    void status(String text);
}
