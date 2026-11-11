package com.cinefile.mixin;

import com.cinefile.core.CutKeys;
import com.cinefile.mod.CutSoundMute;
import com.cinefile.mod.CutTracks;
import com.moulberry.flashback.playback.ReplayPlayer;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.state.EditorStateManager;
import java.util.Collection;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ReplayServer.class)
public abstract class ReplayServerMixin {
    @Shadow private int currentTick;
    @Shadow public abstract Collection<ReplayPlayer> getReplayViewers();

    @Unique private List<int[]> cinefile$cuts = List.of();
    @Unique private boolean cinefile$muted;
    @Unique private int cinefile$sentTick;

    @Inject(method = "handleActions", at = @At("HEAD"))
    private void cinefile$readCutRanges(CallbackInfo ci) {
        // handleActions can hold the scene read lock across ticks, and taking it again behind a queued writer would deadlock, so ranges are read here only.
        cinefile$cuts = CutTracks.cutRanges(EditorStateManager.getCurrent());
        cinefile$syncCutMute();
    }

    @Inject(method = "handleNextTick", at = @At("RETURN"))
    private void cinefile$syncAfterTick(CallbackInfo ci) {
        // Export can cross a whole cut inside one handleActions.
        cinefile$syncCutMute();
    }

    @Unique
    private void cinefile$syncCutMute() {
        if (getReplayViewers().isEmpty()) return;
        boolean muted = CutKeys.contains(cinefile$cuts, currentTick);
        if (!CutKeys.markerDue(cinefile$muted, muted, cinefile$sentTick, currentTick)) return;
        cinefile$muted = muted;
        cinefile$sentTick = currentTick;
        for (ReplayPlayer viewer : getReplayViewers()) {
            ServerPlayNetworking.send(viewer, new CutSoundMute.Payload(muted, currentTick));
        }
    }
}
