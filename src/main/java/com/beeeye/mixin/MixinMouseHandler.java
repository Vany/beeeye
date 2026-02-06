package com.beeeye.mixin;

import com.beeeye.Beeeye;
import com.beeeye.StereoRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Translate mouse X from full-screen coords to half-width eye coords.
 * Screen renders at half width (one eye). Mouse on right half should
 * map to same coords as left half (subtract left eye width).
 */
@Mixin(MouseHandler.class)
public abstract class MixinMouseHandler {

    @Inject(method = "xpos", at = @At("RETURN"), cancellable = true)
    private void beeeye$translateXpos(CallbackInfoReturnable<Double> cir) {
        if (!Beeeye.isStereoEnabled()) return;
        if (!StereoRenderer.isHudPhase()) return;

        Minecraft mc = Minecraft.getInstance();
        // Get real full width (bypass faking)
        int fullWidth = mc.getMainRenderTarget().width;
        int halfWidth = fullWidth / 2;

        double x = cir.getReturnValue();
        // If mouse is on right eye, translate to left eye coords
        if (x >= halfWidth) {
            cir.setReturnValue(x - halfWidth);
        }
    }
}
