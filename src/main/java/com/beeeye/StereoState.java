package com.beeeye;

import com.mojang.blaze3d.pipeline.RenderTarget;

/**
 * Stereo rendering state machine — phase and current eye only.
 * No physics math here; see {@link StereoPerspective} for projection/eye offset.
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
    // IPD accessor — used by Convergence for minimum clamp, and StereoPerspective
    // =========================================================================

    public static float getIPD() {
        return BeeeyeConfig.eyeDistance();
    }
}
