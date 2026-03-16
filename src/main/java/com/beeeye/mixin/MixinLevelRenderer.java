package com.beeeye.mixin;

import com.beeeye.Beeeye;
import com.beeeye.StereoState;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancel doEntityOutline() during HUD_CAPTURE phase.
 *
 * Vanilla GameRenderer calls doEntityOutline() after renderLevel() returns. During
 * HUD_CAPTURE, getMainRenderTarget() redirects to hudFbo, so the entity outline
 * PostChain would blit its opaque-background output onto hudFbo, wiping the
 * transparent HUD layer. We call doEntityOutline() ourselves per-eye in
 * MixinGameRenderer during EYE_RENDER, where getMainRenderTarget() = eye FBO.
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {

    @Inject(method = "doEntityOutline", at = @At("HEAD"), cancellable = true)
    private void beeeye$cancelOutlineInHudPhase(CallbackInfo ci) {
        if (Beeeye.isStereoEnabled() && StereoState.isHudPhase()) {
            ci.cancel();
        }
    }
}
