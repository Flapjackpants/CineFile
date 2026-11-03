package com.cinefile.mixin;

import com.cinefile.mod.CutSoundMute;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SoundBufferLibrary.class)
public class SoundBufferLibraryMixin {
    // shortcut: a sound without stream: true starts from its beginning, seek it with AL_SEC_OFFSET if a disc ever needs it
    @Inject(method = "getStream", at = @At("RETURN"), cancellable = true)
    private void cinefile$seekCutSound(Identifier id, boolean stream, CallbackInfoReturnable<CompletableFuture<AudioStream>> cir) {
        double seconds = CutSoundMute.seekSeconds();
        if (seconds > 0) {
            cir.setReturnValue(cir.getReturnValue().thenApplyAsync(s -> CutSoundMute.skip(s, seconds), Util.nonCriticalIoPool()));
        }
    }
}
