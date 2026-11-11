package com.cinefile.mixin;

import com.cinefile.mod.CutSoundMute;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SoundEngine.class)
public class SoundEngineMixin {
    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void cinefile$blockCutSound(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (CutSoundMute.onPlay(sound)) cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
    }

    @Inject(method = "playDelayed", at = @At("HEAD"), cancellable = true)
    private void cinefile$blockDelayedCutSound(SoundInstance sound, int delay, CallbackInfo ci) {
        if (CutSoundMute.shouldBlock(sound)) ci.cancel();
    }

    @Inject(method = "stop(Lnet/minecraft/client/resources/sounds/SoundInstance;)V", at = @At("HEAD"))
    private void cinefile$forgetCutSound(SoundInstance sound, CallbackInfo ci) {
        CutSoundMute.forget(sound);
    }
}
