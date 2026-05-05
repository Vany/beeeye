package com.beeeye.mixin;

import com.beeeye.StereoRenderer;
import com.beeeye.StereoState;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * After GuiRenderer.draw() finishes (draws list still populated, vertex buffers valid),
 * replay the same draws to rightHudFbo for right-eye HUD.
 *
 * GuiRenderer.render() clears its draw list and resets renderState after draw() returns,
 * so a second render() call would find nothing to draw — we must hook inside render()
 * AFTER draw() but BEFORE draws.clear().
 */
@Mixin(GuiRenderer.class)
public abstract class MixinGuiRenderer {

    @Shadow
    private void draw(GpuBufferSlice fogBuffer) {}

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;draw(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
            shift = At.Shift.AFTER
        )
    )
    private void beeeye$drawRightHud(GpuBufferSlice fogBuffer, CallbackInfo ci) {
        if (!StereoState.isHudPhase()) return;
        StereoRenderer.setHudTargetOverride(StereoRenderer.getRightHudFbo());
        try {
            this.draw(fogBuffer);
        } finally {
            StereoRenderer.setHudTargetOverride(null);
        }
    }
}
