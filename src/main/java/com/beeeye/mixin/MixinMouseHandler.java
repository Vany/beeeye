package com.beeeye.mixin;

import com.beeeye.Beeeye;
import com.beeeye.StereoRenderer;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Translate mouse X from full-screen coords to half-width eye coords.
 * GUI code reads mouse via getScaledXPos(Window) which reads the xpos field
 * directly — so we intercept there, not at xpos().
 */
@Mixin(MouseHandler.class)
public abstract class MixinMouseHandler {

    @Shadow
    private double xpos;

    /**
     * Intercept the instance getScaledXPos — called by onButton, onMove, and all GUI paths.
     * If mouse is on right eye half, wrap xpos into left half before scaling.
     */
    @Inject(
        method = "getScaledXPos(Lcom/mojang/blaze3d/platform/Window;)D",
        at = @At("HEAD"),
        cancellable = true
    )
    private void beeeye$translateScaledXPos(
        Window window,
        CallbackInfoReturnable<Double> cir
    ) {
        if (!Beeeye.isStereoEnabled()) return;

        int fullWidth = StereoRenderer.getFullWidth();
        if (fullWidth <= 0) return;
        int halfScreenWidth = fullWidth / 2;

        double x = this.xpos;
        if (x >= halfScreenWidth) {
            x -= halfScreenWidth;
        }

        // Replicate the static getScaledXPos logic: x * guiScaledWidth / screenWidth
        // guiScaledWidth and screenWidth are already halved by MixinWindow
        cir.setReturnValue(
            (x * window.getGuiScaledWidth()) / window.getScreenWidth()
        );
    }

    /**
     * xpos() is used by some code paths outside GUI (e.g. camera turning).
     * Translate here too for completeness.
     */
    @Inject(method = "xpos", at = @At("RETURN"), cancellable = true)
    private void beeeye$translateXpos(CallbackInfoReturnable<Double> cir) {
        if (!Beeeye.isStereoEnabled()) return;

        int fullWidth = StereoRenderer.getFullWidth();
        if (fullWidth <= 0) return;
        int halfWidth = fullWidth / 2;

        double x = cir.getReturnValue();
        if (x >= halfWidth) {
            cir.setReturnValue(x - halfWidth);
        }
    }
}
