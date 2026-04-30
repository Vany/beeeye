package com.beeeye.mixin;

import com.beeeye.StereoState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Suppress only the vanilla crosshair sprite during stereo HUD capture.
 * The crosshair is drawn separately per eye with convergence offset
 * during the compositing phase (see BodyCrosshair).
 * The attack cooldown indicator (also inside extractCrosshair) is allowed
 * through so it appears in the HUD.
 */
@Mixin(Gui.class)
public class MixinGui {

    // Redirect only the crosshair sprite blit (ordinal=0) to a no-op in stereo HUD phase,
    // letting the attack cooldown indicator blits (ordinal=1,2,3) render normally.
    @Redirect(
        method = "extractCrosshair",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V",
            ordinal = 0
        )
    )
    private void beeeye$suppressCrosshairSprite(
        GuiGraphicsExtractor guiGraphics,
        RenderPipeline pipeline,
        Identifier sprite,
        int x, int y, int w, int h
    ) {
        if (!StereoState.isHudPhase()) {
            guiGraphics.blitSprite(pipeline, sprite, x, y, w, h);
        }
    }
}
