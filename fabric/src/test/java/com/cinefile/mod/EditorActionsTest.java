package com.cinefile.mod;

import static org.junit.jupiter.api.Assertions.*;

import com.moulberry.flashback.keyframe.types.TimelapseKeyframeType;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.KeyframeTrack;
import org.junit.jupiter.api.Test;

class EditorActionsTest {
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
