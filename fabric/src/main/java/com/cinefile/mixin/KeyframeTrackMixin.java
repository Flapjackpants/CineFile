package com.cinefile.mixin;

import com.cinefile.core.CutKeys;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.change.KeyframeChangeTickrate;
import com.moulberry.flashback.keyframe.impl.TimelapseKeyframe;
import com.moulberry.flashback.state.KeyframeTrack;
import java.util.Map;
import java.util.TreeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KeyframeTrack.class)
public class KeyframeTrackMixin {
    @Inject(method = "tryApplyKeyframesTimelapse", at = @At("HEAD"), cancellable = true)
    private void cinefile$cut(float tick, CallbackInfoReturnable<KeyframeChangeTickrate> cir) {
        TreeMap<Integer, Keyframe> times = ((KeyframeTrack) (Object) this).keyframesByTick;
        if (times.isEmpty()) return;
        Map.Entry<Integer, Keyframe> left = times.floorEntry((int) tick);
        Map.Entry<Integer, Keyframe> right = times.ceilingEntry(((int) tick) + 1);
        if (left != null && right != null && CutKeys.isCut(((TimelapseKeyframe) left.getValue()).ticks)) {
            cir.setReturnValue(new KeyframeChangeTickrate(CutKeys.CUT_TICKRATE));
        }
    }
}
