package com.beeeye.mixin;

import com.beeeye.Beeeye;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fake window width to half when stereo is enabled.
 * All rendering (world, HUD, screens) uses half-width eye FBOs,
 * so all code should see half-width dimensions.
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
        if (Beeeye.isStereoEnabled()) {
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
