package com.cinefile.mixin;

import com.cinefile.mod.CineFileMenu;
import com.moulberry.flashback.editor.ui.windows.MainMenuBar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MainMenuBar.class)
public class MainMenuBarMixin {
    @Inject(method = "renderInner", at = @At("TAIL"))
    private static void cinefile$items(CallbackInfo ci) {
        CineFileMenu.render();
    }
}
