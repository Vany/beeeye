package com.beeeye;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;

/**
 * Stereo 3D renderer — state management and FBO lifecycle.
 *
 * Rendering phases (controlled by MixinGameRenderer):
 *   1. Eye rendering (renderingEye=true): each eye renders to its own
 *      half-width FBO via getMainRenderTarget() redirect + width faking.
 *      Off-axis projection (MixinProjectionMatrix) creates per-eye parallax.
 *   2. HUD phase (hudPhase=true): Minecraft + mods draw HUD into half-width
 *      hudFbo. Width faking active so HUD layout uses eye dimensions.
 *   3. Compositing (render TAIL): eye FBOs → main target halves,
 *      then copy hudFbo identically onto both halves via alpha blend.
 *      Same pixels → same crosshair position on both eyes.
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

    // --- State flags ---
    private static Eye currentEye = null;
    private static boolean inStereoPass = false;
    private static boolean renderingEye = false;
    private static boolean hudPhase = false;
    private static boolean useCameraOffset = false;

    // --- FBOs ---
    private static RenderTarget leftEyeFbo = null;
    private static RenderTarget rightEyeFbo = null;
    private static RenderTarget hudFbo = null;
    private static RenderTarget compositeTarget = null;

    // --- Cached dimensions ---
    private static int lastWidth = 0;
    private static int lastHeight = 0;

    // =========================================================================
    // State accessors
    // =========================================================================

    public static Eye getCurrentEye() {
        return currentEye;
    }

    public static void setCurrentEye(Eye eye) {
        currentEye = eye;
    }

    public static boolean isInStereoPass() {
        return inStereoPass;
    }

    public static void setInStereoPass(boolean value) {
        inStereoPass = value;
    }

    public static boolean isRenderingEye() {
        return renderingEye;
    }

    public static void setRenderingEye(boolean value) {
        renderingEye = value;
    }

    public static boolean isHudPhase() {
        return hudPhase;
    }

    public static void setHudPhase(boolean value) {
        hudPhase = value;
    }

    public static boolean useCameraOffset() {
        return useCameraOffset;
    }

    public static void setUseCameraOffset(boolean value) {
        useCameraOffset = value;
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

    /** Current eye's FBO — used by MixinMinecraft to redirect getMainRenderTarget(). */
    public static RenderTarget getCurrentEyeFbo() {
        if (currentEye == null) return null;
        return currentEye == Eye.LEFT ? leftEyeFbo : rightEyeFbo;
    }

    // =========================================================================
    // Off-axis projection
    // =========================================================================

    /**
     * Projection matrix m20 offset for asymmetric frustum stereo.
     * Camera stays in place; frustum shifts left/right per eye.
     * Objects at convergence distance have zero parallax.
     *
     * Derivation: eye offset in near plane = (IPD/2) * near / convergence.
     * In projection matrix: m20_shift = eyeOffset_nearPlane / halfWidth_nearPlane
     *                                 = (IPD/2) / convergence * m00
     * where m00 = near / halfWidth_nearPlane = 1 / (aspect * tan(fovY/2)).
     *
     * @param m00 the projection matrix m00 element (focal length / aspect)
     */
    public static float getProjectionOffset(float m00) {
        if (currentEye == null) return 0;
        // TODO: config returns wrong value, hardcode for now
        float halfIPD = 0.25f / 2.0f; // BeeeyeConfig.EYE_DISTANCE.get().floatValue() / 2.0f;
        float convergence = 5.0f;
        // Negative sign: shift frustum opposite to eye offset so both eyes
        // converge at convergenceDistance. Objects there have zero parallax.
        return -(currentEye.sign * halfIPD * m00) / convergence;
    }

    /**
     * Camera X offset for camera-offset stereo (disabled by default).
     * Only used by MixinCamera when useCameraOffset=true.
     */
    public static float getEyeOffset() {
        if (currentEye == null) return 0;
        // TODO: config returns wrong value (0.01 instead of 0.25), hardcode for now
        float ipd = 0.25f; // BeeeyeConfig.EYE_DISTANCE.get().floatValue();
        return (currentEye.sign * (ipd / 2.0f));
    }

    // =========================================================================
    // FBO lifecycle
    // =========================================================================

    /**
     * Create/recreate FBOs if dimensions changed.
     * Eye FBOs + HUD FBO: half width, with depth.
     * Composite: full width, no depth (alpha blend intermediary).
     */
    public static void ensureFramebuffers(int fullWidth, int fullHeight) {
        if (
            leftEyeFbo != null &&
            lastWidth == fullWidth &&
            lastHeight == fullHeight
        ) {
            return;
        }

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
        if (leftEyeFbo != null) {
            leftEyeFbo.destroyBuffers();
            leftEyeFbo = null;
        }
        if (rightEyeFbo != null) {
            rightEyeFbo.destroyBuffers();
            rightEyeFbo = null;
        }
        if (hudFbo != null) {
            hudFbo.destroyBuffers();
            hudFbo = null;
        }
        if (compositeTarget != null) {
            compositeTarget.destroyBuffers();
            compositeTarget = null;
        }
        lastWidth = 0;
        lastHeight = 0;
    }
}
