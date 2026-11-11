package com.cinefile.mod;

import com.cinefile.core.CineFileConfig;
import com.cinefile.core.CostEstimate;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.flag.ImGuiWindowFlags;
import imgui.moulberry90.type.ImDouble;
import java.util.Locale;

public final class CineFileMenu {
    private static boolean openDialog;
    private static boolean openAutoDialog;
    private static boolean openRemoveCuts;
    private static boolean openResetCuts;
    private static boolean cutRequested;
    private static CostEstimate costEstimate;
    private static final ImDouble budget = new ImDouble();

    private CineFileMenu() {}

    public static boolean cutEnabled() {
        return !AutoCreateController.isRunning() && !Flashback.isExporting();
    }

    public static void requestCut() {
        cutRequested = true;
    }

    public static void render() {
        if (cutRequested) {
            cutRequested = false;
            if (cutEnabled()) CutTracks.addAtPlayhead();
        }
        TimelineScroll.rebindOnce();
        EditorState state = EditorStateManager.getCurrent();
        int tracks = 0;
        int cuts = 0;
        String sceneName = "";
        boolean hasRange = false;
        if (state != null) {
            long stamp = state.acquireRead();
            try {
                EditorScene scene = state.getCurrentScene(stamp);
                tracks = scene.keyframeTracks.size();
                cuts = (int) scene.keyframeTracks.stream().filter(CutTracks::isCut).count();
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
            CineFileConfig cfg = CineFileClient.config();
            costEstimate = CostEstimate.of(cfg);
            budget.set(cfg.maxAiUsd);
            openAutoDialog = true;
        }
        if (ImGui.menuItem("Cut##CineFile", "", false, cutEnabled())) ImGui.openPopup("###CineFileCutMenu");
        float x0 = ImGui.getItemRectMinX(), y1 = ImGui.getItemRectMaxY();
        ImGui.setNextWindowPos(x0, y1);
        if (ImGui.beginPopup("###CineFileCutMenu", ImGuiWindowFlags.NoMove
                | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.AlwaysAutoResize)) {
            if (ImGui.menuItem("Remove all cuts", "", false, cuts > 0)) {
                openRemoveCuts = true;
                ImGui.closeCurrentPopup();
            }
            if (ImGui.menuItem("Reset cuts", "", false, cuts > 0 || tracks > 0)) {
                openResetCuts = true;
                ImGui.closeCurrentPopup();
            }
            if (!cutEnabled()) ImGui.closeCurrentPopup();
            ImGui.endPopup();
        }
        if (ImGui.menuItem("Delete All##CineFile", "", false, !running && (tracks > 0 || hasRange))) {
            openDialog = true;
        }

        if (openAutoDialog) {
            ImGui.openPopup("###CineFileAutoCreate");
            openAutoDialog = false;
        }
        if (ImGuiHelper.beginPopupModalCloseable("Auto Create###CineFileAutoCreate", ImGuiWindowFlags.AlwaysAutoResize)) {
            CineFileConfig cfg = CineFileClient.config();
            ImGui.text(String.format(Locale.ROOT, "Clips: %d at %ss each", costEstimate.clips(), cfg.clipLengthS));
            if (cfg.apiKey.isBlank()) {
                ImGui.text("No API key set: heuristics only, no AI cost.");
            } else {
                ImGui.text("Model: " + cfg.model);
                ImGui.text("Screenshot review: " + (cfg.visualReview ? "on" : "off"));
                ImGui.text(String.format(Locale.ROOT, "Estimated cost: ~$%.4f", costEstimate.typicalUsd()));
                if (cfg.visualReview) ImGui.text(String.format(Locale.ROOT,
                    "Worst case: ~$%.4f, stopped by your budget", costEstimate.worstUsd()));
                ImGui.inputDouble("Budget (USD)##CineFileBudget", budget, 0.01, 0.1, "%.4f");
                ImGui.text("Estimated at DeepSeek prices. Think harder can cost more.");
                if (budget.get() < costEstimate.typicalUsd()) {
                    ImGui.text("Below the estimate: AI stops at the budget, and remaining clips use heuristics.");
                }
            }
            if (ImGui.button("Start")) {
                AutoCreateController.start(budget.get());
                ImGui.closeCurrentPopup();
            }
            ImGui.sameLine();
            if (ImGui.button("Cancel")) ImGui.closeCurrentPopup();
            ImGuiHelper.endPopupModalCloseable();
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

        if (openRemoveCuts) {
            ImGui.openPopup("###CineFileRemoveCuts");
            openRemoveCuts = false;
        }
        if (ImGuiHelper.beginPopupModalCloseable("Remove All Cuts###CineFileRemoveCuts", ImGuiWindowFlags.AlwaysAutoResize)) {
            ImGui.text("Remove all " + cuts + " cuts from \"" + sceneName + "\"?");
            ImGui.text("Undo restores the cuts, but not their names.");
            if (ImGui.button("Remove All")) {
                if (state != null) {
                    int removed = CutTracks.removeAll(state);
                    ReplayUI.setInfoOverlayShort("Removed " + removed + " cuts");
                }
                ImGui.closeCurrentPopup();
            }
            ImGui.sameLine();
            if (ImGui.button("Cancel")) ImGui.closeCurrentPopup();
            ImGuiHelper.endPopupModalCloseable();
        }

        if (openResetCuts) {
            ImGui.openPopup("###CineFileResetCuts");
            openResetCuts = false;
        }
        if (ImGuiHelper.beginPopupModalCloseable("Reset Cuts###CineFileResetCuts", ImGuiWindowFlags.AlwaysAutoResize)) {
            ImGui.text("Remove all " + cuts + " cuts and add one in each gap of 5+ ticks between camera tracks?");
            ImGui.text("Undo restores the cuts, but not their names.");
            if (ImGui.button("Reset")) {
                if (state != null) {
                    int added = CutTracks.reset(state, CineFileClient.config().cutLengthTicks);
                    ReplayUI.setInfoOverlayShort("Reset: " + added + " cuts");
                }
                ImGui.closeCurrentPopup();
            }
            ImGui.sameLine();
            if (ImGui.button("Cancel")) ImGui.closeCurrentPopup();
            ImGuiHelper.endPopupModalCloseable();
        }
    }
}
