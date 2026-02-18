package com.beeeye.mixin;

import com.beeeye.StereoState;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppress vanilla crosshair during stereo HUD capture.
 * The crosshair is drawn separately per eye with convergence offset
 * during the compositing phase (see BodyCrosshair).
 */
@Mixin(Gui.class)
public class MixinGui {

    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void beeeye$suppressCrosshair(
        GuiGraphics guiGraphics,
        DeltaTracker deltaTracker,
        CallbackInfo ci
    ) {
        if (StereoState.isHudPhase()) {
            ci.cancel();
        }
    }
}
