package com.cinefile.mod;

import static org.junit.jupiter.api.Assertions.*;

import com.moulberry.flashback.keyframe.impl.CameraKeyframe;
import com.moulberry.flashback.keyframe.impl.TimelapseKeyframe;
import com.moulberry.flashback.keyframe.types.CameraKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraOrbitKeyframeType;
import com.moulberry.flashback.keyframe.types.TimelapseKeyframeType;
import com.moulberry.flashback.keyframe.interpolation.InterpolationType;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.KeyframeTrack;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class EditorActionsTest {
    @Test
    void cutSoundMuteUsesEnabledCutRangesOnly() {
        assertFalse(CutTracks.isMuted(null, 150));
        EditorState s = new EditorState();
        CutTracks.add(s, 100, 200, 0);
        assertFalse(CutTracks.isMuted(s, 99));
        assertTrue(CutTracks.isMuted(s, 100));
        assertTrue(CutTracks.isMuted(s, 199));
        assertFalse(CutTracks.isMuted(s, 200));

        CutTracks.add(s, 300, 305, 5);
        assertTrue(CutTracks.isMuted(s, 150));
        assertTrue(CutTracks.isMuted(s, 300));
        assertTrue(CutTracks.isMuted(s, 304));
        assertFalse(CutTracks.isMuted(s, 305));

        CutTracks.addTimelapse(s, 400, 500, 5);
        long st = s.acquireWrite();
        EditorScene sc = s.getCurrentScene(st);
        sc.keyframeTracks.add(camera(600, 700, false, true));
        sc.keyframeTracks.get(1).enabled = false;
        s.release(st);
        assertFalse(CutTracks.isMuted(s, 150));
        assertFalse(CutTracks.isMuted(s, 450));
        assertFalse(CutTracks.isMuted(s, 650));
        assertTrue(CutTracks.isMuted(s, 302));
    }

    private static KeyframeTrack camera(int a, int b, boolean orbit, boolean enabled) {
        KeyframeTrack t = new KeyframeTrack(orbit ? CameraOrbitKeyframeType.INSTANCE : CameraKeyframeType.INSTANCE);
        t.enabled = enabled;
        t.keyframesByTick.put(a, new CameraKeyframe(new Vector3d(), 0, 0, 0, InterpolationType.SMOOTH));
        t.keyframesByTick.put(b, new CameraKeyframe(new Vector3d(), 0, 0, 0, InterpolationType.SMOOTH));
        return t;
    }

    @Test
    void cutIdentityIncludesSentinelAndNameOnly() {
        EditorState s = new EditorState();
        CutTracks.add(s, 0, 10, 0);
        CutTracks.add(s, 20, 30, 5);
        CutTracks.addTimelapse(s, 40, 50, 5);
        long st = s.acquireWrite();
        s.getCurrentScene(st).keyframeTracks.get(1).customName = null;
        s.release(st);
        assertEquals(1, read(s, sc -> CutTracks.isCut(sc.keyframeTracks.get(0)) ? 1 : 0));
        assertEquals(1, read(s, sc -> CutTracks.isCut(sc.keyframeTracks.get(1)) ? 1 : 0));
        assertEquals(0, read(s, sc -> CutTracks.isCut(sc.keyframeTracks.get(2)) ? 1 : 0));
        assertFalse(CutTracks.isCut(camera(0, 10, false, true)));
    }

    @Test
    void removeAllPreservesOthersRangeAndUndoOrder() {
        EditorState s = new EditorState();
        CutTracks.add(s, 10, 20, 0);
        CutTracks.addTimelapse(s, 30, 40, 5);
        CutTracks.add(s, 50, 60, 5);
        long st = s.acquireWrite();
        EditorScene sc = s.getCurrentScene(st);
        sc.keyframeTracks.add(camera(70, 80, false, true));
        sc.exportStartTicks = 5;
        sc.exportEndTicks = 90;
        s.release(st);
        assertEquals(2, CutTracks.removeAll(s));
        st = s.acquireWrite();
        sc = s.getCurrentScene(st);
        assertEquals(2, sc.keyframeTracks.size());
        assertSame(TimelapseKeyframeType.INSTANCE, sc.keyframeTracks.get(0).keyframeType);
        assertSame(CameraKeyframeType.INSTANCE, sc.keyframeTracks.get(1).keyframeType);
        assertEquals(5, sc.exportStartTicks);
        assertEquals(90, sc.exportEndTicks);
        sc.undo(d -> {});
        assertEquals(4, sc.keyframeTracks.size());
        assertEquals(5, ticksAt(sc.keyframeTracks.get(0), 60));
        assertEquals(1_000_000, ticksAt(sc.keyframeTracks.get(1), 10));
        sc.redo(d -> {});
        assertEquals(2, sc.keyframeTracks.size());
        s.release(st);
        assertEquals(0, CutTracks.removeAll(s));
        st = s.acquireWrite();
        sc = s.getCurrentScene(st);
        sc.undo(d -> {});
        assertEquals(4, sc.keyframeTracks.size());
        s.release(st);
    }

    @Test
    void resetUsesEnabledCameraAndOrbitGapsAndSingleUndo() {
        EditorState s = new EditorState();
        CutTracks.add(s, 50, 60, 0);
        long st = s.acquireWrite();
        EditorScene sc = s.getCurrentScene(st);
        sc.keyframeTracks.add(camera(0, 100, false, true));
        sc.keyframeTracks.add(camera(200, 300, true, true));
        sc.keyframeTracks.add(camera(303, 400, false, true));
        sc.keyframeTracks.add(camera(500, 600, false, false));
        s.release(st);
        assertEquals(1, CutTracks.reset(s, 5));
        st = s.acquireWrite();
        sc = s.getCurrentScene(st);
        assertEquals(5, sc.keyframeTracks.size());
        assertEquals("Cut", sc.keyframeTracks.get(0).customName);
        assertEquals(0, ticksAt(sc.keyframeTracks.get(0), 100));
        assertEquals(5, ticksAt(sc.keyframeTracks.get(0), 200));
        sc.undo(d -> {});
        assertEquals(5, sc.keyframeTracks.size());
        assertEquals(1_000_000, ticksAt(sc.keyframeTracks.get(0), 50));
        sc.redo(d -> {});
        assertEquals(5, sc.keyframeTracks.size());
        s.release(st);
    }

    @Test
    void resetWithoutCamerasOnlyRemovesCuts() {
        EditorState s = new EditorState();
        assertEquals(0, CutTracks.reset(s, 5));
        CutTracks.add(s, 5, 10, 0);
        assertEquals(0, CutTracks.reset(s, 5));
        assertEquals(0, read(s, sc -> sc.keyframeTracks.size()));
        long st = s.acquireWrite();
        EditorScene sc = s.getCurrentScene(st);
        sc.undo(d -> {});
        assertEquals(1, sc.keyframeTracks.size());
        s.release(st);
    }

    @Test
    void resetMultipleGapsUndoRemovesEveryNewCut() {
        EditorState s = new EditorState();
        long st = s.acquireWrite();
        EditorScene sc = s.getCurrentScene(st);
        sc.keyframeTracks.add(camera(0, 10, false, true));
        sc.keyframeTracks.add(camera(20, 30, false, true));
        sc.keyframeTracks.add(camera(40, 50, false, true));
        s.release(st);
        assertEquals(2, CutTracks.reset(s, 0));
        assertEquals(5, read(s, scene -> scene.keyframeTracks.size()));
        st = s.acquireWrite();
        sc = s.getCurrentScene(st);
        assertEquals(1_000_000, ticksAt(sc.keyframeTracks.get(0), 10));
        assertEquals(1_000_000, ticksAt(sc.keyframeTracks.get(1), 30));
        sc.undo(d -> {});
        assertEquals(3, sc.keyframeTracks.size());
        sc.redo(d -> {});
        assertEquals(5, sc.keyframeTracks.size());
        s.release(st);
    }

    private static int ticksAt(KeyframeTrack t, int tick) {
        return ((com.moulberry.flashback.keyframe.impl.TimelapseKeyframe) t.keyframesByTick.get(tick)).ticks;
    }

    private static int read(EditorState s, java.util.function.ToIntFunction<EditorScene> f) {
        long st = s.acquireRead();
        try { return f.applyAsInt(s.getCurrentScene(st)); } finally { s.release(st); }
    }

    @Test
    void cutAddUndoRedo() throws Exception {
        EditorState s = new EditorState();
        assertFalse(CutTracks.add(s, 50, 50, 0));
        assertTrue(CutTracks.add(s, 10, 110, 0));
        assertTrue(CutTracks.add(s, 200, 300, 5));
        // the newest cut sits at index 0
        assertEquals(2, read(s, sc -> sc.keyframeTracks.size()));
        assertEquals(5, read(s, sc -> ticksAt(sc.keyframeTracks.get(0), 300)));
        assertEquals(0, read(s, sc -> ticksAt(sc.keyframeTracks.get(0), 200)));
        assertEquals(1_000_000, read(s, sc -> ticksAt(sc.keyframeTracks.get(1), 10)));
        long st = s.acquireWrite();
        EditorScene sc = s.getCurrentScene(st);
        assertEquals("Cut", sc.keyframeTracks.get(0).customName);
        assertSame(TimelapseKeyframeType.INSTANCE, sc.keyframeTracks.get(0).keyframeType);
        sc.undo(d -> {});
        assertEquals(1, sc.keyframeTracks.size());
        sc.redo(d -> {});
        assertEquals(2, sc.keyframeTracks.size());
        assertEquals(5, ticksAt(sc.keyframeTracks.get(0), 300));
        s.release(st);
    }

    @Test
    void deleteAllUndo() throws Exception {
        EditorState s = new EditorState();
        assertEquals(0, DeleteAll.apply(s));
        CutTracks.add(s, 0, 10, 0);
        CutTracks.add(s, 20, 30, 3);
        CutTracks.add(s, 40, 50, 7);
        long st = s.acquireWrite();
        EditorScene sc = s.getCurrentScene(st);
        sc.exportStartTicks = 5;
        sc.exportEndTicks = 15;
        s.release(st);
        assertEquals(3, DeleteAll.apply(s));
        st = s.acquireWrite();
        sc = s.getCurrentScene(st);
        assertEquals(0, sc.keyframeTracks.size());
        assertEquals(-1, sc.exportStartTicks);
        assertEquals(-1, sc.exportEndTicks);
        sc.undo(d -> {});
        assertEquals(3, sc.keyframeTracks.size());
        assertEquals(7, ticksAt(sc.keyframeTracks.get(0), 50));
        assertEquals(3, ticksAt(sc.keyframeTracks.get(1), 30));
        assertEquals(1_000_000, ticksAt(sc.keyframeTracks.get(2), 0));
        s.release(st);
    }
}
