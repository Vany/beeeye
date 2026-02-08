package com.beeeye;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;

/**
 * Stereo 3D renderer — state machine and FBO lifecycle.
 *
 * Render phases (driven by MixinGameRenderer):
 *   {@link RenderPhase#EYE_RENDER} — each eye renders to half-width FBO.
 *   {@link RenderPhase#HUD_CAPTURE} — HUD draws into half-width hudFbo.
 *   {@link RenderPhase#COMPOSITING} — eye FBOs blitted to main target halves,
 *       HUD alpha-composited onto both halves identically.
 *   {@link RenderPhase#INACTIVE} — stereo pipeline idle.
 */
public class StereoRenderer {

    public enum Eye {
        LEFT(-1),
        RIGHT(1);

        public final int sign;

        Eye(int sign) {
            this.sign = sign;
        }
    }

    /** Render pipeline phase — replaces fragile independent boolean flags. */
    public enum RenderPhase {
        INACTIVE,
        EYE_RENDER,
        HUD_CAPTURE,
        COMPOSITING,
    }

    private static final float MIN_CONVERGENCE = 1.0f;
    private static final float MAX_CONVERGENCE = 50.0f;

    // --- State ---
    private static volatile RenderPhase phase = RenderPhase.INACTIVE;
    private static Eye currentEye = null;

    // --- Dynamic convergence ---
    private static float dynamicConvergence =
        (float) BeeeyeConfig.DEFAULT_CONVERGENCE;

    // --- FBOs ---
    private static RenderTarget leftEyeFbo = null;
    private static RenderTarget rightEyeFbo = null;
    private static RenderTarget hudFbo = null;
    private static RenderTarget compositeTarget = null;

    // --- Cached dimensions (true physical size, not faked) ---
    private static int lastWidth = 0;
    private static int lastHeight = 0;

    // --- GL FBO cache for compositing ---
    private static final GlFboCache glFboCache = new GlFboCache();

    // =========================================================================
    // Phase state machine
    // =========================================================================

    public static RenderPhase getPhase() {
        return phase;
    }

    public static void setPhase(RenderPhase p) {
        phase = p;
    }

    /** Backward-compatible queries — derived from single phase enum. */
    public static boolean isInStereoPass() {
        return phase == RenderPhase.EYE_RENDER;
    }

    public static boolean isRenderingEye() {
        return phase == RenderPhase.EYE_RENDER;
    }

    public static boolean isHudPhase() {
        return phase == RenderPhase.HUD_CAPTURE;
    }

    // =========================================================================
    // Eye state
    // =========================================================================

    public static Eye getCurrentEye() {
        return currentEye;
    }

    public static void setCurrentEye(Eye eye) {
        currentEye = eye;
    }

    // =========================================================================
    // FBO accessors
    // =========================================================================

    public static RenderTarget getLeftEyeFbo() {
        return leftEyeFbo;
    }

    public static RenderTarget getRightEyeFbo() {
        return rightEyeFbo;
    }

    public static RenderTarget getHudFbo() {
        return hudFbo;
    }

    public static RenderTarget getCompositeTarget() {
        return compositeTarget;
    }

    public static GlFboCache getGlFboCache() {
        return glFboCache;
    }

    /** Current eye's FBO — used by MixinMinecraft to redirect getMainRenderTarget(). */
    public static RenderTarget getCurrentEyeFbo() {
        return currentEye == null
            ? null
            : currentEye == Eye.LEFT
                ? leftEyeFbo
                : rightEyeFbo;
    }

    /** True physical framebuffer width (not faked by MixinWindow). */
    public static int getFullWidth() {
        return lastWidth;
    }

    public static int getFullHeight() {
        return lastHeight;
    }

    // =========================================================================
    // Stereo parameters
    // =========================================================================

    public static float getIPD() {
        return BeeeyeConfig.get(
            BeeeyeConfig.EYE_DISTANCE,
            BeeeyeConfig.DEFAULT_EYE_DISTANCE
        ).floatValue();
    }

    /** Convergence distance — dynamic (raycast-driven) or static from config. */
    public static float getConvergence() {
        return isDynamicConvergenceEnabled()
            ? dynamicConvergence
            : getStaticConvergence();
    }

    /** Static convergence from config (fallback for sky/no-hit). */
    public static float getStaticConvergence() {
        return BeeeyeConfig.get(
            BeeeyeConfig.CONVERGENCE,
            BeeeyeConfig.DEFAULT_CONVERGENCE
        ).floatValue();
    }

    public static boolean isDynamicConvergenceEnabled() {
        return BeeeyeConfig.get(BeeeyeConfig.DYNAMIC_CONVERGENCE, false);
    }

    /**
     * Smoothly update dynamic convergence toward target distance.
     * Speed in ticks → exponential lerp: 1 - exp(-2.2 / speed).
     */
    public static void updateDynamicConvergence(float targetDistance) {
        float clamped = Math.clamp(
            targetDistance,
            MIN_CONVERGENCE,
            MAX_CONVERGENCE
        );
        int speed = BeeeyeConfig.get(
            BeeeyeConfig.CONVERGENCE_SPEED,
            BeeeyeConfig.DEFAULT_CONVERGENCE_SPEED
        );
        float smoothing = (float) (1.0 - Math.exp(-2.2 / speed));
        dynamicConvergence += (clamped - dynamicConvergence) * smoothing;
    }

    public static float getDynamicConvergence() {
        return dynamicConvergence;
    }

    /**
     * Projection matrix m20 offset for asymmetric frustum stereo.
     * Objects at convergence distance have zero parallax.
     */
    public static float getProjectionOffset(float m00) {
        if (currentEye == null) return 0;
        return (
            -(((currentEye.sign * getIPD()) / 2.0f) * m00) / getConvergence()
        );
    }

    /** Camera X offset: +/- IPD/2 along local X axis per eye. */
    public static float getEyeOffset() {
        if (currentEye == null) return 0;
        return currentEye.sign * (getIPD() / 2.0f);
    }

    // =========================================================================
    // FBO lifecycle
    // =========================================================================

    /**
     * Create/recreate FBOs if dimensions changed.
     * Eye + HUD FBOs: half width, with depth. Composite: full width, no depth.
     */
    public static void ensureFramebuffers(int fullWidth, int fullHeight) {
        if (
            leftEyeFbo != null &&
            lastWidth == fullWidth &&
            lastHeight == fullHeight
        ) return;

        int halfWidth = fullWidth / 2;
        Beeeye.LOGGER.info(
            "Creating stereo FBOs: {}x{} (eye/hud: {}x{})",
            fullWidth,
            fullHeight,
            halfWidth,
            fullHeight
        );

        cleanup();

        leftEyeFbo = new TextureTarget(
            "Beeeye Left Eye",
            halfWidth,
            fullHeight,
            true
        );
        rightEyeFbo = new TextureTarget(
            "Beeeye Right Eye",
            halfWidth,
            fullHeight,
            true
        );
        hudFbo = new TextureTarget("Beeeye HUD", halfWidth, fullHeight, true);
        compositeTarget = new TextureTarget(
            "Beeeye Composite",
            fullWidth,
            fullHeight,
            false
        );

        lastWidth = fullWidth;
        lastHeight = fullHeight;
    }

    /** Clear FBO to transparent black (color + depth if present). */
    public static void clearFbo(RenderTarget fbo) {
        if (fbo == null) return;
        GpuTexture color = fbo.getColorTexture();
        if (color == null) return;
        CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
        GpuTexture depth = fbo.getDepthTexture();
        if (depth != null) {
            enc.clearColorAndDepthTextures(color, 0, depth, 1.0);
        } else {
            enc.clearColorTexture(color, 0);
        }
    }

    public static void cleanup() {
        destroyFbo(leftEyeFbo);
        leftEyeFbo = null;
        destroyFbo(rightEyeFbo);
        rightEyeFbo = null;
        destroyFbo(hudFbo);
        hudFbo = null;
        destroyFbo(compositeTarget);
        compositeTarget = null;
        glFboCache.cleanup();
        lastWidth = 0;
        lastHeight = 0;
    }

    private static void destroyFbo(RenderTarget fbo) {
        if (fbo != null) fbo.destroyBuffers();
    }
}
