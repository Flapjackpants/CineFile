package com.cinefile.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/** Candidate clip windows and their activity scores (port of cinefile/camera.py). */
public final class Candidates {
    /** Slices whose best window is below this times the median score count as idle. */
    public static final double SPREAD_FLOOR_RATIO = 0.25;

    private Candidates() {}

    public static double activityScore(Trajectory t, int t0, int t1) {
        if (t1 < t0) return 0.0;
        List<Pose> poses = new ArrayList<>(t.poses().subMap(t0, true, t1, true).values());
        if (poses.size() < 2) return 0.0;
        double dist = 0, yawDelta = 0;
        for (int i = 0; i < poses.size() - 1; i++) {
            dist += poses.get(i).dist(poses.get(i + 1));
            yawDelta += Math.abs(CameraSynth.wrapDegrees(poses.get(i + 1).yaw() - poses.get(i).yaw()));
        }
        // prefer moderate movement (building) over standing still or sprinting endlessly
        double move = Math.min(dist, 40.0);
        double turn = Math.min(yawDelta, 180.0);
        double dwell = 1.0 / (1.0 + Math.abs(dist - 8.0));
        return move * 0.5 + turn * 0.05 + dwell * 5.0;
    }

    private static TreeSet<Integer> boundaries(Trajectory t, boolean cuts, boolean dims) {
        TreeSet<Integer> b = new TreeSet<>();
        if (cuts) b.addAll(t.cuts());
        if (dims) b.addAll(t.dimensionChanges());
        return b;
    }

    /** Inclusive tick ranges that do not cross a boundary. */
    public static List<int[]> segments(Trajectory t, boolean splitOnCuts, boolean splitOnDimensions) {
        List<int[]> out = new ArrayList<>();
        if (t.poses().isEmpty()) return out;
        TreeSet<Integer> bounds = boundaries(t, splitOnCuts, splitOnDimensions);
        int start = t.poses().firstKey(), end = t.poses().lastKey();
        int segStart = start;
        for (int x = start + 1; x <= end; x++) {
            if (bounds.contains(x)) {
                if (segStart < x) out.add(new int[] {segStart, x - 1});
                segStart = x;
            }
        }
        out.add(new int[] {segStart, end});
        out.removeIf(s -> s[1] - s[0] < 1);
        return out;
    }

    public static List<Candidate> windows(Trajectory t, int clipTicks, int maxCandidates, boolean splitOnCuts, boolean splitOnDimensions) {
        int stride = Math.max(10, clipTicks / 4);
        TreeSet<Integer> bounds = boundaries(t, splitOnCuts, splitOnDimensions);
        List<int[]> segs = segments(t, splitOnCuts, splitOnDimensions);
        segs.removeIf(s -> s[1] - s[0] < clipTicks);
        // thinning by score drops whole stretches of the timeline, so widen the stride instead
        if (maxCandidates > segs.size()) {
            long usable = 0;
            for (int[] s : segs) usable += s[1] - s[0] - clipTicks;
            stride = Math.max(stride, (int) Math.ceil((double) usable / (maxCandidates - segs.size())));
        }
        List<Candidate> out = new ArrayList<>();
        for (int[] s : segs) {
            for (int a = s[0]; a + clipTicks <= s[1]; a += stride) {
                int b = a + clipTicks;
                Integer hit = bounds.higher(a);
                if (hit != null && hit <= b) continue;
                out.add(new Candidate(a, b, activityScore(t, a, b)));
            }
        }
        out.sort(Comparator.comparingDouble(Candidate::score).reversed());
        return out.size() > maxCandidates ? new ArrayList<>(out.subList(0, maxCandidates)) : out;
    }

    /** n equal time slices over the candidates' start ticks; each holds indices, best score first. */
    public static List<List<Integer>> slices(List<Candidate> c, int n) {
        List<List<Integer>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new ArrayList<>());
        if (c.isEmpty()) return out;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (Candidate x : c) {
            lo = Math.min(lo, x.start());
            hi = Math.max(hi, x.start());
        }
        for (int i = 0; i < c.size(); i++) {
            out.get((int) ((long) (c.get(i).start() - lo) * n / (hi - lo + 1))).add(i);
        }
        for (List<Integer> sl : out) sl.sort((x, y) -> Double.compare(c.get(y).score(), c.get(x).score()));
        return out;
    }
}
