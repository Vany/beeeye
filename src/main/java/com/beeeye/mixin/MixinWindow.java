package com.beeeye.mixin;

import com.beeeye.Beeeye;
import com.beeeye.StereoRenderer;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fake window width to half during stereo rendering phases.
 * getWidth() only faked during EYE_RENDER/HUD_CAPTURE — not during
 * resize or compositing, which need the real framebuffer width.
 * getScreenWidth() and getGuiScaledWidth() faked whenever stereo is enabled
 * (they affect GUI layout, not render target creation).
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
        // Only fake during rendering phases — resizeDisplay() needs real width
        StereoRenderer.RenderPhase phase = StereoRenderer.getPhase();
        if (
            phase == StereoRenderer.RenderPhase.EYE_RENDER ||
            phase == StereoRenderer.RenderPhase.HUD_CAPTURE
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
