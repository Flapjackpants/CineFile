package com.cinefile.mod;

import static org.junit.jupiter.api.Assertions.*;

import com.cinefile.core.CutKeys;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.change.KeyframeChangeTickrate;
import com.moulberry.flashback.keyframe.impl.TimelapseKeyframe;
import com.moulberry.flashback.keyframe.types.TimelapseKeyframeType;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.KeyframeTrack;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class CutExportFramesTest {
    static final int START = 100;

    static Keyframe key(int ticks) throws Exception {
        var f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        TimelapseKeyframe k = (TimelapseKeyframe) ((Unsafe) f.get(null)).allocateInstance(TimelapseKeyframe.class);
        k.ticks = ticks;
        return k;
    }

    /** Mirrors KeyframeTrackMixin: a huge tickrate between the two keyframes. */
    static class CutType implements KeyframeType<TimelapseKeyframe> {
        public String name() { return "cut"; }
        public String id() { return "CUT_TEST"; }
        public @Nullable TimelapseKeyframe createDirect() { return null; }
        public @Nullable KeyframeType.KeyframeCreatePopup<TimelapseKeyframe> createPopup() { return null; }
        public Class<? extends KeyframeChange> keyframeChangeType() { return KeyframeChangeTickrate.class; }
        public boolean neverApplyLastKeyframe() { return true; }
        public boolean hasCustomKeyframeChangeCalculation() { return true; }
        public KeyframeChange customKeyframeChange(TreeMap<Integer, Keyframe> keyframes, float tick) {
            Map.Entry<Integer, Keyframe> l = keyframes.floorEntry((int) tick), r = keyframes.ceilingEntry((int) tick + 1);
            return l != null && r != null ? new KeyframeChangeTickrate(CutKeys.CUT_TICKRATE) : null;
        }
    }

    @SuppressWarnings("unchecked")
    static List<Double> frames(KeyframeType<?> type, int span, int endTick, double fps, int k0, int k1) throws Exception {
        EditorState state = new EditorState();
        if (type != null) {
            KeyframeTrack t = new KeyframeTrack(type);
            t.keyframesByTick.put(START, key(k0));
            t.keyframesByTick.put(START + span, key(k1));
            long stamp = state.acquireWrite();
            state.getCurrentScene(stamp).keyframeTracks.add(t);
            state.release(stamp);
            state.markDirty();
        }
        Method m = Class.forName("com.moulberry.flashback.exporting.ExportJob").getDeclaredMethod("calculateTicks", EditorState.class, int.class, int.class, double.class);
        m.setAccessible(true);
        List<Object> infos = (List<Object>) m.invoke(null, state, 0, endTick, fps);
        List<Double> out = new ArrayList<>();
        for (Object o : infos) {
            Method s = o.getClass().getDeclaredMethod("serverTick");
            s.setAccessible(true);
            out.add((Double) s.invoke(o));
        }
        return out;
    }

    @Test
    void cutRateLeavesNoFramesInRange() throws Exception {
        for (int span : new int[] {1, 20, 6000, 72000}) {
            for (double fps : new double[] {24, 60, 240}) {
                int end = START + span + 200;
                var cut = frames(new CutType(), span, end, fps, 0, 1);
                var base = frames(null, span, end, fps, 0, 0);
                String msg = "span " + span + " fps " + fps;
                assertEquals(0, cut.stream().filter(t -> t > START && t < START + span).count(), msg);
                long after = cut.stream().filter(t -> t >= START + span).count();
                long baseAfter = base.stream().filter(t -> t >= START + span).count();
                assertTrue(after >= baseAfter && after <= baseAfter + 1, msg + ": " + after + " vs " + baseAfter);
            }
        }
    }

    @Test
    void oneTickTimelapseLeavesStrayFrames() throws Exception {
        var f = frames(TimelapseKeyframeType.INSTANCE, 6000, START + 6200, 60, 0, 1);
        assertTrue(f.stream().filter(t -> t > START && t < START + 6000).count() >= 1);
    }
}
