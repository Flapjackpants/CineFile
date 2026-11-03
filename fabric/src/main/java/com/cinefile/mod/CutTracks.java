package com.cinefile.mod;

import com.cinefile.core.CutKeys;
import com.cinefile.mixin.TimelineWindowAccessor;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.editor.ui.windows.TimelineWindow;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.impl.TimelapseKeyframe;
import com.moulberry.flashback.keyframe.types.CameraKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraOrbitKeyframeType;
import com.moulberry.flashback.keyframe.types.TimelapseKeyframeType;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorSceneHistoryAction;
import com.moulberry.flashback.state.EditorSceneHistoryEntry;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import com.moulberry.flashback.state.KeyframeTrack;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CutTracks {
    public static final String CUT_NAME = "Cut";
    private CutTracks() {}

    /** Adds a track at index 0 so its tickrate wins in EditorState.applyKeyframes. */
    public static boolean add(EditorState state, int t0, int t1, int cutLengthTicks) {
        if (t1 <= t0) return false;
        int[] k = CutKeys.ticksFor(cutLengthTicks);
        push(state, t0, t1, k[0], k[1], CUT_NAME, true);
        return true;
    }

    public static void addTimelapse(EditorState state, int t0, int t1, int outputTicks) {
        if (t1 <= t0) return;
        push(state, t0, t1, 0, Math.max(outputTicks, 1), null, false);
    }

    public static boolean isCut(KeyframeTrack t) {
        return t.keyframeType == TimelapseKeyframeType.INSTANCE && !t.keyframesByTick.isEmpty()
                && (CUT_NAME.equals(t.customName)
                || CutKeys.isCut(((TimelapseKeyframe) t.keyframesByTick.firstEntry().getValue()).ticks));
    }

    public static boolean isMuted(EditorState state, int tick) {
        if (state == null) return false;
        long stamp = state.acquireRead();
        try {
            for (KeyframeTrack track : state.getCurrentScene(stamp).keyframeTracks) {
                if (track.enabled && isCut(track)
                        && track.keyframesByTick.firstKey() <= tick && tick < track.keyframesByTick.lastKey()) return true;
            }
            return false;
        } finally {
            state.release(stamp);
        }
    }

    public static int removeAll(EditorState state) {
        return editCuts(state, null, 0);
    }

    public static int reset(EditorState state, int cutLengthTicks) {
        return editCuts(state, CutKeys.ticksFor(cutLengthTicks), 5);
    }

    private static int editCuts(EditorState state, int[] cutKeys, int minGap) {
        long stamp = state.acquireWrite();
        int added, result;
        try {
            EditorScene scene = state.getCurrentScene(stamp);
            List<EditorSceneHistoryAction> undo = new ArrayList<>(), redo = new ArrayList<>();
            List<int[]> ranges = new ArrayList<>();
            int removed = 0;
            for (int i = scene.keyframeTracks.size() - 1; i >= 0; i--) {
                KeyframeTrack t = scene.keyframeTracks.get(i);
                if (isCut(t)) {
                    redo.add(new EditorSceneHistoryAction.RemoveTrack(t.keyframeType, i));
                    removed++;
                } else if (cutKeys != null && t.enabled && !t.keyframesByTick.isEmpty()
                        && (t.keyframeType == CameraKeyframeType.INSTANCE || t.keyframeType == CameraOrbitKeyframeType.INSTANCE)) {
                    ranges.add(new int[] {t.keyframesByTick.firstKey(), t.keyframesByTick.lastKey()});
                }
            }
            List<int[]> gaps = cutKeys == null ? List.of() : CutKeys.gaps(ranges, minGap);
            added = gaps.size();
            if (removed == 0 && added == 0) return 0;
            for (int i = 0; i < added; i++) undo.add(new EditorSceneHistoryAction.RemoveTrack(TimelapseKeyframeType.INSTANCE, 0));
            for (int i = 0; i < scene.keyframeTracks.size(); i++) {
                KeyframeTrack t = scene.keyframeTracks.get(i);
                if (!isCut(t)) continue;
                undo.add(new EditorSceneHistoryAction.AddTrack(t.keyframeType, i));
                for (Map.Entry<Integer, Keyframe> e : t.keyframesByTick.entrySet()) {
                    undo.add(new EditorSceneHistoryAction.SetKeyframe(t.keyframeType, i, e.getKey(), e.getValue().copy()));
                }
            }
            for (int i = added - 1; i >= 0; i--) {
                int[] gap = gaps.get(i);
                redo.add(new EditorSceneHistoryAction.AddTrack(TimelapseKeyframeType.INSTANCE, 0));
                redo.add(new EditorSceneHistoryAction.SetKeyframe(TimelapseKeyframeType.INSTANCE, 0, gap[0], new TimelapseKeyframe(cutKeys[0])));
                redo.add(new EditorSceneHistoryAction.SetKeyframe(TimelapseKeyframeType.INSTANCE, 0, gap[1], new TimelapseKeyframe(cutKeys[1])));
            }
            scene.push(new EditorSceneHistoryEntry(undo, redo, cutKeys == null ? "Remove all cuts" : "Reset cuts"));
            if (cutKeys != null) {
                for (int i = 0; i < added; i++) scene.keyframeTracks.get(i).customName = CUT_NAME;
            }
            state.markDirty();
            result = cutKeys == null ? removed : added;
        } finally {
            state.release(stamp);
        }
        clearSelection();
        return result;
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
