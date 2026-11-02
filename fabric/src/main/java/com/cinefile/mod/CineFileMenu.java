package com.cinefile.mod;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.flag.ImGuiWindowFlags;

public final class CineFileMenu {
    private static boolean openDialog;

    private CineFileMenu() {}

    public static void render() {
        TimelineScroll.rebindOnce();
        EditorState state = EditorStateManager.getCurrent();
        int tracks = 0;
        String sceneName = "";
        boolean hasRange = false;
        if (state != null) {
            long stamp = state.acquireRead();
            try {
                EditorScene scene = state.getCurrentScene(stamp);
                tracks = scene.keyframeTracks.size();
                sceneName = scene.name;
                hasRange = scene.exportStartTicks >= 0 || scene.exportEndTicks >= 0;
            } finally {
                state.release(stamp);
            }
        }

        ImGui.separator();
        boolean running = AutoCreateController.isRunning();
        if (running) {
            if (ImGui.menuItem(AutoCreateController.progressLabel() + "###CineFileAuto")) AutoCreateController.cancel();
            ImGui.textDisabled(AutoCreateController.statusLine());
        } else if (ImGui.menuItem("Auto Create##CineFile", "", false, !Flashback.isExporting())) {
            AutoCreateController.start();
        }
        if (ImGui.menuItem("Cut##CineFile", "", false, !running && !Flashback.isExporting())) {
            CutTracks.addAtPlayhead();
        }
        if (ImGui.menuItem("Delete All##CineFile", "", false, !running && (tracks > 0 || hasRange))) {
            openDialog = true;
        }

        if (openDialog) {
            ImGui.openPopup("###CineFileDeleteAll");
            openDialog = false;
        }
        if (ImGuiHelper.beginPopupModalCloseable("Delete All Elements###CineFileDeleteAll", ImGuiWindowFlags.AlwaysAutoResize)) {
            ImGui.text("Delete all " + tracks + " elements from \"" + sceneName + "\"?");
            ImGui.text("This also clears the export range. Undo restores the elements, but not the range or their custom names.");
            if (ImGui.button("Delete All")) {
                if (state != null) DeleteAll.apply(state);
                ImGui.closeCurrentPopup();
            }
            ImGui.sameLine();
            if (ImGui.button("Cancel")) {
                ImGui.closeCurrentPopup();
            }
            ImGuiHelper.endPopupModalCloseable();
        }
    }
}
