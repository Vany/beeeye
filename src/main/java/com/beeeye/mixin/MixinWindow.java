package com.beeeye.mixin;

import com.beeeye.Beeeye;
import com.beeeye.StereoState;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Halve window width accessors so mods see per-eye dimensions during stereo.
 *
 * getWidth() — halved during HUD_EXTRACT (all of extract()), EYE_RENDER, and HUD_CAPTURE.
 *   Not halved during COMPOSITING or INACTIVE, which need the real framebuffer size.
 *   During resize, GameRenderer.resize() is @Redirected in MixinGameRenderer to double
 *   windowRenderState.width back to full before calling mainRenderTarget.resize().
 *
 * getScreenWidth() / getGuiScaledWidth() — always halved while stereo is enabled.
 *   These drive GUI layout and are never used for render target creation.
 */
@Mixin(Window.class)
public class MixinWindow {

    @Shadow
    private int framebufferWidth;

    @Shadow
    private int guiScaledWidth;

    @Shadow
    private int width;

    @Inject(method = "getWidth", at = @At("HEAD"), cancellable = true)
    private void beeeye$fakeWidth(CallbackInfoReturnable<Integer> cir) {
        if (!Beeeye.isStereoEnabled()) return;
        StereoState.RenderPhase phase = StereoState.getPhase();
        if (
            phase == StereoState.RenderPhase.HUD_EXTRACT ||
            phase == StereoState.RenderPhase.EYE_RENDER ||
            phase == StereoState.RenderPhase.HUD_CAPTURE
        ) {
            cir.setReturnValue(framebufferWidth / 2);
        }
    }

    @Inject(method = "getScreenWidth", at = @At("HEAD"), cancellable = true)
    private void beeeye$fakeScreenWidth(CallbackInfoReturnable<Integer> cir) {
        if (Beeeye.isStereoEnabled()) {
            cir.setReturnValue(width / 2);
        }
    }

    @Inject(method = "getGuiScaledWidth", at = @At("HEAD"), cancellable = true)
    private void beeeye$fakeGuiScaledWidth(
        CallbackInfoReturnable<Integer> cir
    ) {
        if (Beeeye.isStereoEnabled()) {
            cir.setReturnValue(guiScaledWidth / 2);
        }
    }
}
