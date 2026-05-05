package com.beeeye.mixin;

import com.beeeye.Beeeye;
import com.beeeye.BinocularPicker;
import com.beeeye.Convergence;
import com.beeeye.HeadTracker;
import com.beeeye.StereoRenderer;
import com.beeeye.StereoState;
import com.beeeye.StereoState.Eye;
import com.beeeye.StereoState.RenderPhase;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stereo rendering pipeline: each eye renders to its own half-width FBO,
 * then composited to main target. HUD alpha-composited onto both eyes.
 */
@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private Camera mainCamera;

    @Shadow
    @Final
    private GlobalSettingsUniform globalSettingsUniform;

    @Shadow
    public abstract void renderLevel(DeltaTracker deltaTracker);

    @Shadow
    public abstract GameRenderState getGameRenderState();

    @Shadow private Identifier postEffectId;
    @Shadow private boolean effectActive;
    @Shadow @Final private CrossFrameResourcePool resourcePool;

    @Unique private boolean beeeye$inStereoRender = false;
    @Unique private boolean beeeye$stereoReady = false;
    @Unique private int beeeye$dbgGuiFrame = 0;
    @Unique private static final int DBG_GUI_FIRST = 3;   // always log first N GUI renders
    @Unique private static final int DBG_GUI_INTERVAL = 60;

    // =========================================================================
    // Render pipeline
    // =========================================================================

    /**
     * Covers the entire extract() call with HUD_EXTRACT so that window.getWidth() returns
     * half-width for ALL mod extraction code, not just inside extractGui().
     *
     * extractWindow() inside extract() will now capture windowRenderState.width = 960 (half).
     * A @Redirect on the resize() call in render() doubles it back for mainRenderTarget resize.
     * beeeye$redirectGuiRender no longer needs to halve windowState.width (already 960).
     */
    @Inject(method = "extract", at = @At("HEAD"))
    private void beeeye$onExtractHead(
        DeltaTracker deltaTracker,
        boolean advanceGameTime,
        CallbackInfo ci
    ) {
        if (Beeeye.isStereoEnabled()) {
            StereoState.setPhase(RenderPhase.HUD_EXTRACT);
        }
    }

    @Inject(method = "extract", at = @At("TAIL"))
    private void beeeye$onExtractTail(
        DeltaTracker deltaTracker,
        boolean advanceGameTime,
        CallbackInfo ci
    ) {
        // Phase-based check, not stereo-enabled check: if stereo is toggled off between
        // HEAD and TAIL of extract(), isStereoEnabled() would be false and phase would
        // stay stuck at HUD_EXTRACT. Always reset if we set it.
        if (StereoState.getPhase() == RenderPhase.HUD_EXTRACT) {
            StereoState.setPhase(RenderPhase.INACTIVE);
        }
    }

    /**
     * When stereo is active, windowRenderState.width is 960 (halved by extractWindow via
     * getWidth() during HUD_EXTRACT). The resize path in render() must use the real
     * framebuffer width (1920), so double it back here.
     */
    @Redirect(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;resize(II)V"
        )
    )
    private void beeeye$redirectResize(
        net.minecraft.client.renderer.GameRenderer gameRenderer,
        int width,
        int height
    ) {
        gameRenderer.resize(Beeeye.isStereoEnabled() ? width * 2 : width, height);
    }

    @Inject(method = "renderLevel", at = @At("HEAD"), cancellable = true)
    private void beeeye$onRenderLevel(
        DeltaTracker deltaTracker,
        CallbackInfo ci
    ) {
        if (!Beeeye.isStereoEnabled() || beeeye$inStereoRender) return;
        if (minecraft.level == null) return;

        ci.cancel();
        beeeye$inStereoRender = true;
        try {
            beeeye$renderStereoFrame(deltaTracker);
        } catch (Exception e) {
            Beeeye.LOGGER.error("Stereo render error", e);
            StereoState.setPhase(RenderPhase.INACTIVE);
            StereoState.setCurrentEye(null);
        } finally {
            beeeye$inStereoRender = false;
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void beeeye$onRenderTail(
        DeltaTracker deltaTracker,
        boolean doRenderLevel,
        CallbackInfo ci
    ) {
        if (!beeeye$stereoReady) return;
        beeeye$stereoReady = false;
        StereoState.setPhase(RenderPhase.COMPOSITING);
        try {
            StereoRenderer.composite(minecraft.getMainRenderTarget());
        } finally {
            StereoState.setPhase(RenderPhase.INACTIVE);
        }
    }

    /**
     * Redirect the vanilla postEffect call in render(). When stereo is active, we've already
     * applied the effect per-eye on the eye FBOs during EYE_RENDER. The vanilla call runs
     * after renderLevel() at which point getMainRenderTarget()=hudFbo, so it would corrupt
     * hudFbo with opaque pixels. Skip it when stereoReady (= stereo frame was processed).
     */
    @Redirect(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/PostChain;process(Lcom/mojang/blaze3d/pipeline/RenderTarget;Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;)V"
        )
    )
    private void beeeye$redirectPostEffect(
        PostChain postchain, RenderTarget target, GraphicsResourceAllocator allocator
    ) {
        if (!beeeye$stereoReady) {
            // Non-stereo: run normally
            postchain.process(target, allocator);
        }
        // Stereo: already processed per-eye — skip to avoid corrupting hudFbo
    }

    /**
     * Wrap GuiRenderer.render() with two stereo-specific GL fixups when active.
     *
     * windowRenderState.width — already set to half-width by extractWindow() during
     * HUD_EXTRACT, so GuiRenderer builds a 240-unit ortho matching the per-eye canvas.
     * No manual override needed here.
     *
     * hudFbo has leftEye as opaque background (initHudBackground), so GuiRenderer's
     * fog/vignette pass composites correctly onto real world content, just as it would
     * in vanilla. No pre-clear or blend-equation fixups are needed.
     */
    @Redirect(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"
        )
    )
    private void beeeye$redirectGuiRender(GuiRenderer guiRenderer, GpuBufferSlice fogBuffer) {
        if (!beeeye$stereoReady) {
            guiRenderer.render(fogBuffer);
            return;
        }
        int dbgN = beeeye$dbgGuiFrame++;
        boolean doDbg = (dbgN < DBG_GUI_FIRST) || (dbgN % DBG_GUI_INTERVAL == 0);

        // GuiRenderer renders to hudFbo (leftEye bg). MixinGuiRenderer injects after
        // draw() and replays the same draws to rightHudFbo before the draw list is cleared.
        if (doDbg) StereoRenderer.debugLogHud("hud-left[pre-GUI]");
        guiRenderer.render(fogBuffer);
        if (doDbg) StereoRenderer.debugLogHud("hud-left[post-GUI]");
    }

    // =========================================================================
    // Stereo frame orchestration
    // =========================================================================

    @Unique
    private void beeeye$renderStereoFrame(DeltaTracker deltaTracker) {
        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        StereoRenderer.ensureFramebuffers(mainTarget.width, mainTarget.height);
        GameRenderState grs = getGameRenderState();

        for (Eye eye : Eye.values()) {
            StereoState.setCurrentEye(eye);
            StereoState.setPhase(RenderPhase.EYE_RENDER);
            StereoRenderer.clearFbo(StereoState.getCurrentEyeFbo());
            mainCamera.update(deltaTracker);
            float partial = mainCamera.getCameraEntityPartialTicks(deltaTracker);
            CameraRenderState cameraState = grs.levelRenderState.cameraRenderState;
            mainCamera.extractRenderState(cameraState, partial);

            // Update convergence after LEFT camera setup — mainCamera now
            // includes head tracking, so rays match actual view direction.
            if (
                eye == Eye.LEFT &&
                Convergence.isDynamic() &&
                minecraft.player != null
            ) {
                float targetDistance = BinocularPicker.pick(
                    mainCamera,
                    minecraft.level,
                    minecraft.player
                );
                Convergence.update(targetDistance);
            }

            beeeye$refreshGlobalUniforms(deltaTracker);
            // Reset accumulated render state before per-eye extraction.
            // Both vanilla's center-camera extractLevel and previous eye extractions append
            // (not replace) to entityRenderStates, blockEntityRenderStates, and
            // particlesRenderState. Without resetting first, LEFT eye doubles and RIGHT
            // eye triples their counts, with mixed camera positions causing visible offsets.
            // reset() clears entities/blockEntities but NOT particlesRenderState — reset both.
            grs.levelRenderState.reset();
            grs.levelRenderState.particlesRenderState.reset();
            minecraft.levelRenderer.extractLevel(deltaTracker, mainCamera, partial);
            renderLevel(deltaTracker);
            minecraft.levelRenderer.doEntityOutline();
            // Post-processing effects (night vision, nausea, creeper outline, etc.) per eye.
            // Vanilla GameRenderer runs this after renderLevel() returns, at which point we're
            // in HUD_CAPTURE and getMainRenderTarget()=hudFbo — the effect would corrupt hudFbo
            // with opaque scene-derived pixels. We run it here per-eye on the correct eye FBO,
            // and cancel the vanilla call via @Redirect below.
            if (postEffectId != null && effectActive) {
                PostChain postchain = minecraft.getShaderManager().getPostChain(
                    postEffectId, LevelTargetBundle.MAIN_TARGETS
                );
                if (postchain != null) {
                    postchain.process(StereoState.getCurrentEyeFbo(), resourcePool);
                }
            }
        }

        StereoState.setCurrentEye(null);
        // Pre-composite both eyes to main NOW, before GuiRenderer runs.
        // GuiRenderer's fog/vignette pass needs an opaque background; it must render on top
        // of an already-rendered world, exactly as vanilla expects. We give it that by
        // pre-populating main AND hudFbo with eye content before entering HUD_CAPTURE.
        StereoRenderer.compositeEyes(mainTarget);
        // Copy left eye → hudFbo as opaque background for GuiRenderer.
        // GuiRenderer renders to hudFbo (redirected by MixinMinecraft during HUD_CAPTURE).
        // Result: hudFbo = leftEye + HUD correctly blended.
        StereoRenderer.initHudBackground();
        // Reset scissor: EYE_RENDER or a mod may have left GL_SCISSOR_TEST enabled.
        if (GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        beeeye$stereoReady = true;
        StereoState.setPhase(RenderPhase.HUD_CAPTURE);
    }

    // =========================================================================
    // Uniform refresh
    // =========================================================================

    @Unique
    private void beeeye$refreshGlobalUniforms(DeltaTracker deltaTracker) {
        globalSettingsUniform.update(
            minecraft.getWindow().getWidth(),
            minecraft.getWindow().getHeight(),
            minecraft.options.glintStrength().get(),
            minecraft.level == null ? 0L : minecraft.level.getGameTime(),
            deltaTracker,
            minecraft.options.getMenuBackgroundBlurriness(),
            mainCamera.position(),
            minecraft.options.textureFiltering().get() ==
                net.minecraft.client.TextureFilteringMethod.RGSS
        );
    }
}
