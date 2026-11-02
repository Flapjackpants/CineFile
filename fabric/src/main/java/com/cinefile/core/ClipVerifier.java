package com.cinefile.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Steps through one clip's sample ticks on the live replay, one driver interaction per call. */
public final class ClipVerifier {
    private static final int SEEK_TIMEOUT_STEPS = 200;

    private final Trajectory t;
    private final Object handle;
    private final CineFileConfig cfg;
    private final int[] ticks;
    private final Verdict early;
    private int i, waits, blockedView;
    private boolean seeked;
    private String firstDim;
    private double[] prev;

    public ClipVerifier(Trajectory t, CameraClip clip, Object handle, CineFileConfig cfg) {
        this.t = t;
        this.handle = handle;
        this.cfg = cfg;
        List<Integer> l = new ArrayList<>();
        for (int k = clip.startTick(); k < clip.endTick(); k += cfg.checkEveryTicks) l.add(k);
        l.add(clip.endTick());
        ticks = l.stream().mapToInt(Integer::intValue).toArray();
        if (cfg.rejectCuts && inside(t.cuts(), clip)) early = Verdict.CUT;
        else if (cfg.rejectDimensionChanges && inside(t.dimensionChanges(), clip)) early = Verdict.DIMENSION_CHANGE;
        else early = null;
    }

    private static boolean inside(List<Integer> ticks, CameraClip c) {
        return ticks.stream().anyMatch(x -> x > c.startTick() && x <= c.endTick());
    }

    public Verdict step(ReplayDriver d) {
        if (early != null) return early;
        int tick = ticks[i];
        if (!seeked) {
            d.seek(tick);
            seeked = true;
            waits = 0;
            return null;
        }
        if (!d.atTick(tick)) return ++waits > SEEK_TIMEOUT_STEPS ? Verdict.TIMEOUT : null;
        seeked = false;
        if (cfg.rejectDimensionChanges) {
            String dim = d.dimension();
            if (i == 0) firstDim = dim;
            else if (!Objects.equals(dim, firstDim)) return Verdict.DIMENSION_CHANGE;
        }
        double[] c = d.cameraAt(handle, tick);
        double[] pos = {c[0], c[1], c[2]};
        if (cfg.rejectBlockClipping && (!d.isLoaded(pos) || d.blocked(i == 0 ? pos : prev, pos))) return Verdict.BLOCK_CLIP;
        prev = pos;
        if (cfg.rejectBlockedView) {
            Pose p = t.sample(tick);
            if (p != null && d.blocked(pos, new double[] {p.x(), p.y() + 1.0, p.z()})) blockedView++;
        }
        if (++i < ticks.length) return null;
        return blockedView * 2 > ticks.length ? Verdict.VIEW_BLOCKED : Verdict.OK;
    }
}
