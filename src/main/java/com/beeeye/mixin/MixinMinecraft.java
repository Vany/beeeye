package com.beeeye.mixin;

import com.beeeye.Beeeye;
import com.beeeye.StereoRenderer;
import com.beeeye.StereoState;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Redirect getMainRenderTarget() during stereo rendering:
 * - During eye rendering: redirect to current eye's half-width FBO
 * - During HUD phase: redirect to hudFbo for HUD/screen rendering
 */
@Mixin(Minecraft.class)
public class MixinMinecraft {

    @Inject(
        method = "getMainRenderTarget",
        at = @At("HEAD"),
        cancellable = true
    )
    private void beeeye$overrideRenderTarget(
        CallbackInfoReturnable<RenderTarget> cir
    ) {
        if (!Beeeye.isStereoEnabled()) return;

        if (StereoState.isRenderingEye()) {
            RenderTarget eyeFbo = StereoState.getCurrentEyeFbo();
            if (eyeFbo != null) {
                cir.setReturnValue(eyeFbo);
            }
            return;
        }

        if (StereoState.isHudPhase()) {
            RenderTarget hudFbo = StereoRenderer.getHudFbo();
            if (hudFbo != null) {
                cir.setReturnValue(hudFbo);
            }
        }
    }
}
