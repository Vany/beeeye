package com.beeeye;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import org.lwjgl.opengl.GL30;

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

    // --- Constants ---
    private static final float DEFAULT_CONVERGENCE = 5.0f;

    // --- State flags ---
    private static Eye currentEye = null;
    private static boolean inStereoPass = false;
    private static boolean renderingEye = false;
    private static boolean hudPhase = false;

    // --- FBOs ---
    private static RenderTarget leftEyeFbo = null;
    private static RenderTarget rightEyeFbo = null;
    private static RenderTarget hudFbo = null;
    private static RenderTarget compositeTarget = null;

    // --- Cached dimensions ---
    private static int lastWidth = 0;
    private static int lastHeight = 0;

    // --- Cached GL FBOs for compositing (managed by MixinGameRenderer, cleaned here) ---
    private static int mainGlFbo = 0;
    private static int leftGlFbo = 0;
    private static int rightGlFbo = 0;
    private static int hudGlFbo = 0;
    private static int compositeGlFbo = 0;
    private static int cachedMainTex = 0;
    private static int cachedLeftTex = 0;
    private static int cachedRightTex = 0;
    private static int cachedHudTex = 0;
    private static int cachedCompositeTex = 0;

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
    // Stereo parameters
    // =========================================================================

    /** Get IPD from config (with fallback if config not loaded). */
    public static float getIPD() {
        try {
            return BeeeyeConfig.EYE_DISTANCE.get().floatValue();
        } catch (Exception e) {
            return 0.25f; // Default fallback
        }
    }

    /** Get convergence distance from config (with fallback if config not loaded). */
    public static float getConvergence() {
        try {
            return BeeeyeConfig.CONVERGENCE.get().floatValue();
        } catch (Exception e) {
            return DEFAULT_CONVERGENCE; // Default fallback
        }
    }

    /**
     * Projection matrix m20 offset for asymmetric frustum stereo.
     * Frustum shifts left/right per eye; objects at convergence distance have zero parallax.
     *
     * @param m00 the projection matrix m00 element (focal length / aspect)
     */
    public static float getProjectionOffset(float m00) {
        if (currentEye == null) return 0;
        float halfIPD = getIPD() / 2.0f;
        return -(currentEye.sign * halfIPD * m00) / getConvergence();
    }

    /**
     * Camera X offset: move camera ±IPD/2 along local X axis per eye.
     */
    public static float getEyeOffset() {
        if (currentEye == null) return 0;
        return currentEye.sign * (getIPD() / 2.0f);
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

    // =========================================================================
    // GL FBO management (for MixinGameRenderer compositing)
    // =========================================================================

    public static int getMainGlFbo() {
        return mainGlFbo;
    }

    public static void setMainGlFbo(int fbo) {
        mainGlFbo = fbo;
    }

    public static int getLeftGlFbo() {
        return leftGlFbo;
    }

    public static void setLeftGlFbo(int fbo) {
        leftGlFbo = fbo;
    }

    public static int getRightGlFbo() {
        return rightGlFbo;
    }

    public static void setRightGlFbo(int fbo) {
        rightGlFbo = fbo;
    }

    public static int getHudGlFbo() {
        return hudGlFbo;
    }

    public static void setHudGlFbo(int fbo) {
        hudGlFbo = fbo;
    }

    public static int getCompositeGlFbo() {
        return compositeGlFbo;
    }

    public static void setCompositeGlFbo(int fbo) {
        compositeGlFbo = fbo;
    }

    public static int getCachedMainTex() {
        return cachedMainTex;
    }

    public static void setCachedMainTex(int tex) {
        cachedMainTex = tex;
    }

    public static int getCachedLeftTex() {
        return cachedLeftTex;
    }

    public static void setCachedLeftTex(int tex) {
        cachedLeftTex = tex;
    }

    public static int getCachedRightTex() {
        return cachedRightTex;
    }

    public static void setCachedRightTex(int tex) {
        cachedRightTex = tex;
    }

    public static int getCachedHudTex() {
        return cachedHudTex;
    }

    public static void setCachedHudTex(int tex) {
        cachedHudTex = tex;
    }

    public static int getCachedCompositeTex() {
        return cachedCompositeTex;
    }

    public static void setCachedCompositeTex(int tex) {
        cachedCompositeTex = tex;
    }

    /** Cleanup cached GL FBOs used for compositing. */
    public static void cleanupGlFbos() {
        if (mainGlFbo != 0) {
            GL30.glDeleteFramebuffers(mainGlFbo);
            mainGlFbo = 0;
        }
        if (leftGlFbo != 0) {
            GL30.glDeleteFramebuffers(leftGlFbo);
            leftGlFbo = 0;
        }
        if (rightGlFbo != 0) {
            GL30.glDeleteFramebuffers(rightGlFbo);
            rightGlFbo = 0;
        }
        if (hudGlFbo != 0) {
            GL30.glDeleteFramebuffers(hudGlFbo);
            hudGlFbo = 0;
        }
        if (compositeGlFbo != 0) {
            GL30.glDeleteFramebuffers(compositeGlFbo);
            compositeGlFbo = 0;
        }
        cachedMainTex = 0;
        cachedLeftTex = 0;
        cachedRightTex = 0;
        cachedHudTex = 0;
        cachedCompositeTex = 0;
    }
}
