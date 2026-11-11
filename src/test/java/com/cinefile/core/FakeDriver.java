package com.cinefile.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** atTick is true on the call after seek; everything is recorded. */
final class FakeDriver implements ReplayDriver {
    int cur = -1;
    double fov = 70;
    boolean neverAtTick;
    BiPredicate<double[], double[]> blocked = (a, b) -> false;
    BiPredicate<double[], double[]> clips = (a, b) -> blocked.test(a, b);
    Predicate<double[]> loaded = p -> true;
    Function<Integer, String> dim = t -> "overworld";
    Function<Integer, byte[]> png = n -> new byte[] {1};
    final List<int[]> occupied = new ArrayList<>();
    final List<String> calls = new ArrayList<>();
    final List<Integer> seeks = new ArrayList<>();
    final List<CameraClip> shown = new ArrayList<>(), committed = new ArrayList<>(), discarded = new ArrayList<>();
    final List<int[]> gaps = new ArrayList<>();
    final List<int[]> exportRanges = new ArrayList<>();
    int captures;

    public void seek(int tick) {
        cur = tick;
        seeks.add(tick);
        calls.add("seek:" + tick);
    }

    public boolean atTick(int tick) { return !neverAtTick && cur == tick; }
    public String dimension() { return dim.apply(cur); }
    public double fovDegrees() { return fov; }
    public boolean isLoaded(double[] pos) { return loaded.test(pos); }
    public boolean blocked(double[] from, double[] to) { return blocked.test(from, to); }
    public boolean clips(double[] from, double[] to) { return clips.test(from, to); }

    public Object showCandidate(CameraClip clip) {
        shown.add(clip);
        calls.add("show");
        return clip;
    }

    public void discardCandidate(Object handle) {
        discarded.add((CameraClip) handle);
        calls.add("discard");
    }

    public void commitCandidate(Object handle, CameraClip clip) {
        committed.add(clip);
        calls.add("commit");
    }

    public double[] cameraAt(Object handle, int tick) { return Framing.cameraAt((CameraClip) handle, tick); }

    public void capture(Consumer<byte[]> cb) {
        captures++;
        cb.accept(png.apply(captures));
    }

    public void addGap(int t0, int t1) {
        gaps.add(new int[] {t0, t1});
        calls.add("gap");
    }

    public void setExportRange(int t0, int t1) {
        exportRanges.add(new int[] {t0, t1});
        calls.add("export:" + t0 + "-" + t1);
    }

    public List<int[]> occupiedRanges() { return occupied; }
    public void status(String text) {}
}
