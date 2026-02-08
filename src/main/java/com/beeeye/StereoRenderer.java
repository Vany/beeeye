package com.beeeye;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;

/**
 * Stereo FBO lifecycle — create, clear, and destroy framebuffers
 * for the stereo rendering pipeline.
 *
 * Layout:
 *   leftEyeFbo / rightEyeFbo — half-width, with depth (world capture)
 *   hudFbo — half-width, with depth (HUD capture, transparent bg)
 *   compositeTarget — full-width, no depth (alpha blend intermediary)
 */
public class StereoRenderer {

    /** Rectangle for glBlitFramebuffer — replaces 8 loose int parameters. */
    public record BlitRect(int x0, int y0, int x1, int y1) {
        public static BlitRect region(int x, int y, int w, int h) {
            return new BlitRect(x, y, x + w, y + h);
        }
    }

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

    /** True physical framebuffer width (not faked by MixinWindow). */
    public static int getFullWidth() {
        return lastWidth;
    }

    public static int getFullHeight() {
        return lastHeight;
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
