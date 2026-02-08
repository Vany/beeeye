package com.beeeye;

import com.mojang.blaze3d.pipeline.RenderTarget;

/**
 * Stereo rendering state machine — phase, current eye, and stereo parameter queries.
 *
 * Render phases (driven by MixinGameRenderer):
 *   {@link RenderPhase#EYE_RENDER} — each eye renders to half-width FBO.
 *   {@link RenderPhase#HUD_CAPTURE} — HUD draws into half-width hudFbo.
 *   {@link RenderPhase#COMPOSITING} — eye FBOs blitted to main target halves,
 *       HUD alpha-composited onto both halves identically.
 *   {@link RenderPhase#INACTIVE} — stereo pipeline idle.
 */
public class StereoState {

    public enum Eye {
        LEFT(-1),
        RIGHT(1);

        public final int sign;

        Eye(int sign) {
            this.sign = sign;
        }
    }

    public enum RenderPhase {
        INACTIVE,
        EYE_RENDER,
        HUD_CAPTURE,
        COMPOSITING,
    }

    // --- State ---
    private static volatile RenderPhase phase = RenderPhase.INACTIVE;
    private static Eye currentEye = null;

    // =========================================================================
    // Phase state machine
    // =========================================================================

    public static RenderPhase getPhase() {
        return phase;
    }

    public static void setPhase(RenderPhase p) {
        phase = p;
    }

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

    /** Current eye's FBO — used by MixinMinecraft to redirect getMainRenderTarget(). */
    public static RenderTarget getCurrentEyeFbo() {
        return currentEye == null
            ? null
            : currentEye == Eye.LEFT
                ? StereoRenderer.getLeftEyeFbo()
                : StereoRenderer.getRightEyeFbo();
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

    /**
     * Projection matrix m20 offset for asymmetric frustum stereo.
     * Objects at convergence distance have zero parallax.
     */
    public static float getProjectionOffset(float m00) {
        if (currentEye == null) return 0;
        return -(((currentEye.sign * getIPD()) / 2.0f) * m00)
            / Convergence.get();
    }

    /** Camera X offset: +/- IPD/2 along local X axis per eye. */
    public static float getEyeOffset() {
        if (currentEye == null) return 0;
        return currentEye.sign * (getIPD() / 2.0f);
    }
}
