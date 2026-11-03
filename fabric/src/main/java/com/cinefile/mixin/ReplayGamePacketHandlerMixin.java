package com.cinefile.mixin;

import com.cinefile.mod.CutTracks;
import com.moulberry.flashback.playback.ReplayGamePacketHandler;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.state.EditorStateManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ReplayGamePacketHandler.class)
public class ReplayGamePacketHandlerMixin {
    @Shadow @Final private ReplayServer replayServer;

    @Inject(method = {"handleSoundEvent", "handleSoundEntityEvent", "handleLevelEvent", "handleExplosion"}, at = @At("HEAD"), cancellable = true)
    private void cinefile$muteCutSounds(CallbackInfo ci) {
        // The client receives these only after the server has passed the cut, so it cannot filter them there.
        if (CutTracks.isMuted(EditorStateManager.getCurrent(), ((ReplayServerAccessor) replayServer).cinefile$currentTick())) ci.cancel();
    }
}
