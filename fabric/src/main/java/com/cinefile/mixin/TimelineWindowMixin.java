package com.cinefile.mixin;

import com.cinefile.mod.TimelineScroll;
import com.moulberry.flashback.editor.keybinds.Keybinds;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.editor.ui.windows.TimelineWindow;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.flag.ImGuiPopupFlags;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TimelineWindow.class)
public class TimelineWindowMixin {
    @Inject(method = "renderInner", at = @At(value = "INVOKE", target = "Limgui/moulberry90/ImGui;getScrollY()F", ordinal = 0))
    private static void cinefile$wheelScroll(CallbackInfo ci) {
        float wheel = ReplayUI.getIO().getMouseWheel();
        if (wheel == 0 || !ImGui.isWindowHovered() || ImGui.isPopupOpen("", ImGuiPopupFlags.AnyPopup)
                || ReplayUI.getIO().getWantTextInput()
                || TimelineScroll.mod(Keybinds.TIMELINE_ZOOM_SCROLL, "areAllModifiersDown")
                || TimelineScroll.mod(Keybinds.TIMELINE_MOVE_SCROLL, "areAllModifiersDown")) {
            return;
        }
        ImGui.setScrollY(TimelineScroll.newScroll(ImGui.getScrollY(), wheel, ImGui.getScrollMaxY(), ImGui.getFrameHeightWithSpacing()));
    }
}
