package com.cinefile.mod;

import com.cinefile.core.CutKeys;
import com.cinefile.mixin.TimelineWindowAccessor;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.editor.ui.windows.TimelineWindow;
import com.moulberry.flashback.keyframe.impl.TimelapseKeyframe;
import com.moulberry.flashback.keyframe.types.TimelapseKeyframeType;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorSceneHistoryAction;
import com.moulberry.flashback.state.EditorSceneHistoryEntry;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import java.util.List;

public final class CutTracks {
    private CutTracks() {}

    /** Adds a track at index 0 so its tickrate wins in EditorState.applyKeyframes. */
    public static boolean add(EditorState state, int t0, int t1, int cutLengthTicks) {
        if (t1 <= t0) return false;
        int[] k = CutKeys.ticksFor(cutLengthTicks);
        push(state, t0, t1, k[0], k[1], "Cut", true);
        return true;
    }

    public static void addTimelapse(EditorState state, int t0, int t1, int outputTicks) {
        if (t1 <= t0) return;
        push(state, t0, t1, 0, Math.max(outputTicks, 1), null, false);
    }

    private static void push(EditorState state, int t0, int t1, int a, int b, String name, boolean first) {
        long stamp = state.acquireWrite();
        try {
            EditorScene scene = state.getCurrentScene(stamp);
            var type = TimelapseKeyframeType.INSTANCE;
            int index = first ? 0 : scene.keyframeTracks.size();
            scene.push(new EditorSceneHistoryEntry(
                    List.of(new EditorSceneHistoryAction.RemoveTrack(type, index)),
                    List.of(new EditorSceneHistoryAction.AddTrack(type, index),
                            new EditorSceneHistoryAction.SetKeyframe(type, index, t0, new TimelapseKeyframe(a)),
                            new EditorSceneHistoryAction.SetKeyframe(type, index, t1, new TimelapseKeyframe(b))),
                    name == null ? "Add timelapse" : "Add " + name.toLowerCase()));
            if (name != null) scene.keyframeTracks.get(index).customName = name;
            state.markDirty();
        } finally {
            state.release(stamp);
        }
        clearSelection();
    }

    private static void clearSelection() {
        TimelineWindowAccessor.cinefile$selected().clear();
    }

    public static void addAtPlayhead() {
        EditorState state = EditorStateManager.getCurrent();
        ReplayServer replay = Flashback.getReplayServer();
        if (state == null || replay == null) return;
        int[] r = CutKeys.rangeFromPlayhead(TimelineWindow.getCursorTick(), replay.getTotalReplayTicks());
        if (r == null) {
            ReplayUI.setInfoOverlayShort("No room for a cut here");
            return;
        }
        add(state, r[0], r[1], CineFileClient.config().cutLengthTicks);
    }
}
