package com.beeeye.mixin;

import com.beeeye.Beeeye;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Suppress the vanilla crosshair sprite whenever stereo is active.
 * In MC 26.1.2, extractCrosshair() runs during GameRenderer.extract() —
 * before our HUD_CAPTURE phase begins — so a phase-based check never fires.
 * The crosshair is replaced by BodyCrosshair.drawConvergenceCrosshair().
 * The attack cooldown indicator (ordinals 1-3) is not affected.
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
        if (!Beeeye.isStereoEnabled()) {
            guiGraphics.blitSprite(pipeline, sprite, x, y, w, h);
        }
    }
}
