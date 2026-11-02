package com.cinefile.mixin;

import com.cinefile.core.CutKeys;
import com.moulberry.flashback.state.RealTimeMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RealTimeMapping.class)
public class RealTimeMappingMixin {
    // The cut speed would otherwise persist past the track and wreck later camera interpolation.
    @Inject(method = "addMapping", at = @At("HEAD"), cancellable = true)
    private void cinefile$skipCut(int tick, float speed, CallbackInfo ci) {
        if (CutKeys.isCutSpeed(speed)) ci.cancel();
    }
}
