package com.cinefile.mod;

import com.cinefile.mixin.TimelineWindowAccessor;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorSceneHistoryAction;
import com.moulberry.flashback.state.EditorSceneHistoryEntry;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.KeyframeTrack;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class DeleteAll {
    private DeleteAll() {}

    public static int apply(EditorState state) {
        long stamp = state.acquireWrite();
        int n;
        try {
            EditorScene scene = state.getCurrentScene(stamp);
            n = scene.keyframeTracks.size();
            if (n == 0) return 0;
            List<EditorSceneHistoryAction> undo = new ArrayList<>(), redo = new ArrayList<>();
            for (int i = n - 1; i >= 0; i--) {
                KeyframeTrack t = scene.keyframeTracks.get(i);
                redo.add(new EditorSceneHistoryAction.RemoveTrack(t.keyframeType, i));
            }
            for (int i = 0; i < n; i++) {
                KeyframeTrack t = scene.keyframeTracks.get(i);
                undo.add(new EditorSceneHistoryAction.AddTrack(t.keyframeType, i));
                for (Map.Entry<Integer, Keyframe> e : t.keyframesByTick.entrySet()) {
                    undo.add(new EditorSceneHistoryAction.SetKeyframe(t.keyframeType, i, e.getKey(), e.getValue().copy()));
                }
            }
            scene.push(new EditorSceneHistoryEntry(undo, redo, "Delete all elements"));
            scene.exportStartTicks = scene.exportEndTicks = -1;
            state.markDirty();
        } finally {
            state.release(stamp);
        }
        TimelineWindowAccessor.cinefile$selected().clear();
        return n;
    }
}
