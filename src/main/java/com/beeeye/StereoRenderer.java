package com.beeeye;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.RenderPipelines;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import java.nio.ByteBuffer;
import java.util.OptionalInt;

/**
 * Stereo FBO lifecycle — create, clear, and destroy framebuffers
 * for the stereo rendering pipeline.
 *
 * Layout:
 *   leftEyeFbo / rightEyeFbo — half-width, with depth (world capture)
 *   hudFbo — half-width, with depth (HUD capture, transparent bg)
 */
public class StereoRenderer {

    // --- FBOs ---
    private static RenderTarget leftEyeFbo  = null;
    private static RenderTarget rightEyeFbo = null;
    private static RenderTarget hudFbo      = null;   // left  eye + HUD
    private static RenderTarget rightHudFbo = null;   // right eye + HUD

    // Override: when set, getActiveHudFbo() returns this instead of hudFbo.
    // Used by MixinGameRenderer to redirect the second GuiRenderer call to rightHudFbo.
    private static RenderTarget hudTargetOverride = null;

    // --- Cached dimensions (true physical size, not faked) ---
    private static int lastWidth = 0;
    private static int lastHeight = 0;

    // --- GL FBO cache for compositing ---
    private static final GlFboCache glFboCache = new GlFboCache();

    // --- Debug: pixel sampling ---
    private static int debugCallCount = 0;
    private static final int DEBUG_ALWAYS_FIRST = 3;  // always sample first N composite calls
    private static final int DEBUG_INTERVAL = 60;     // then every N frames

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

    public static RenderTarget getRightHudFbo() {
        return rightHudFbo;
    }

    /** MixinMinecraft calls this during HUD_CAPTURE to get the active HUD render target. */
    public static RenderTarget getActiveHudFbo() {
        return hudTargetOverride != null ? hudTargetOverride : hudFbo;
    }

    /** Set during second GuiRenderer call so getMainRenderTarget() returns rightHudFbo. */
    public static void setHudTargetOverride(RenderTarget target) {
        hudTargetOverride = target;
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
     * Eye + HUD FBOs: half width, with depth.
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

        leftEyeFbo  = new TextureTarget("Beeeye Left Eye",  halfWidth, fullHeight, true);
        rightEyeFbo = new TextureTarget("Beeeye Right Eye", halfWidth, fullHeight, true);
        hudFbo      = new TextureTarget("Beeeye HUD Left",  halfWidth, fullHeight, true);
        rightHudFbo = new TextureTarget("Beeeye HUD Right", halfWidth, fullHeight, true);

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
        destroyFbo(leftEyeFbo);  leftEyeFbo  = null;
        destroyFbo(rightEyeFbo); rightEyeFbo = null;
        destroyFbo(hudFbo);      hudFbo      = null;
        destroyFbo(rightHudFbo); rightHudFbo = null;
        hudTargetOverride = null;
        glFboCache.cleanup();
        lastWidth = 0;
        lastHeight = 0;
    }

    private static void destroyFbo(RenderTarget fbo) {
        if (fbo != null) fbo.destroyBuffers();
    }

    // =========================================================================
    // Compositing
    // =========================================================================

    /**
     * Blit both eye FBOs to main target's left/right halves.
     * Called from renderStereoFrame() BEFORE HUD_CAPTURE — main target gets the
     * complete stereo world before GuiRenderer runs on top of it.
     *
     * Uses raw glBlitFramebuffer (not RenderPass) to avoid:
     *   - GlCommandEncoder.copyTextureToTexture bug: passes width as dstX1 instead of
     *     destX+width → non-zero destX produces zero-width blit → black right half.
     *   - ENTITY_OUTLINE_BLIT alpha blend (ZERO, ONE) which preserves dst_alpha=0.
     */
    public static void compositeEyes(RenderTarget mainTarget) {
        if (leftEyeFbo == null || rightEyeFbo == null) return;
        int fullW = mainTarget.width;
        int fullH = mainTarget.height;
        int halfW = fullW / 2;
        int leftTexId  = GlTextureUtil.textureId(leftEyeFbo.getColorTexture());
        int rightTexId = GlTextureUtil.textureId(rightEyeFbo.getColorTexture());
        int mainTexId  = GlTextureUtil.textureId(mainTarget.getColorTexture());
        if (leftTexId <= 0 || rightTexId <= 0 || mainTexId <= 0) {
            Beeeye.LOGGER.warn("compositeEyes: texture id missing left={} right={} main={}", leftTexId, rightTexId, mainTexId);
            return;
        }
        int leftGl  = glFboCache.fbo(GlFboCache.Slot.LEFT,  leftTexId);
        int rightGl = glFboCache.fbo(GlFboCache.Slot.RIGHT, rightTexId);
        int mainGl  = glFboCache.fbo(GlFboCache.Slot.MAIN,  mainTexId);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, mainGl);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, leftGl);
        GL30.glBlitFramebuffer(0, 0, halfW, fullH, 0,     0, halfW, fullH, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, rightGl);
        GL30.glBlitFramebuffer(0, 0, halfW, fullH, halfW, 0, fullW, fullH, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /**
     * Copy left eye content into hudFbo as an opaque background before GuiRenderer runs.
     *
     * GuiRenderer.render(fogBuffer) always renders a full-screen fog/vignette pass that
     * assumes an already-opaque world background. On a transparent hudFbo this pass
     * outputs 0,0,0,255 for every non-HUD pixel, wiping the stereo world when composited.
     * Providing leftEye as background makes the fog pass behave correctly, just as it
     * would in vanilla when rendering on top of the fully-rendered world.
     */
    public static void initHudBackground() {
        if (leftEyeFbo == null || rightEyeFbo == null || hudFbo == null || rightHudFbo == null) return;
        int halfW = leftEyeFbo.width;
        int fullH = leftEyeFbo.height;
        int leftTexId     = GlTextureUtil.textureId(leftEyeFbo.getColorTexture());
        int rightTexId    = GlTextureUtil.textureId(rightEyeFbo.getColorTexture());
        int hudTexId      = GlTextureUtil.textureId(hudFbo.getColorTexture());
        int rightHudTexId = GlTextureUtil.textureId(rightHudFbo.getColorTexture());
        if (leftTexId <= 0 || rightTexId <= 0 || hudTexId <= 0 || rightHudTexId <= 0) {
            Beeeye.LOGGER.warn("initHudBackground: texture id missing left={} right={} hud={} rightHud={}",
                leftTexId, rightTexId, hudTexId, rightHudTexId);
            return;
        }
        int leftGl     = glFboCache.fbo(GlFboCache.Slot.LEFT,      leftTexId);
        int rightGl    = glFboCache.fbo(GlFboCache.Slot.RIGHT,     rightTexId);
        int hudGl      = glFboCache.fbo(GlFboCache.Slot.HUD,       hudTexId);
        int rightHudGl = glFboCache.fbo(GlFboCache.Slot.HUD_RIGHT, rightHudTexId);
        // left eye → hudFbo background
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, hudGl);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, leftGl);
        GL30.glBlitFramebuffer(0, 0, halfW, fullH, 0, 0, halfW, fullH, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        // right eye → rightHudFbo background
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, rightHudGl);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, rightGl);
        GL30.glBlitFramebuffer(0, 0, halfW, fullH, 0, 0, halfW, fullH, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /**
     * Overlay hudFbo (leftEye + HUD) onto main left half, then draw crosshairs.
     * Called from MixinGameRenderer during COMPOSITING phase.
     *
     * Eyes are already in main from compositeEyes() called inside renderStereoFrame().
     * GuiRenderer has rendered HUD onto hudFbo (opaque leftEye background), so
     * hudFbo = leftEye + HUD correctly blended. We replace main left half with hudFbo.
     * Right half keeps rightEye from compositeEyes() — no HUD overlay (future work).
     */
    public static void composite(RenderTarget mainTarget) {
        RenderTarget hudFboRt      = hudFbo;
        RenderTarget rightHudFboRt = rightHudFbo;
        if (hudFboRt == null || rightHudFboRt == null) {
            Beeeye.LOGGER.warn("composite() called but hud FBOs not ready: hud={} rightHud={}", hudFboRt, rightHudFboRt);
            return;
        }
        int fullW = mainTarget.width;
        int fullH = mainTarget.height;
        int halfW = fullW / 2;
        GpuTexture mainTex = mainTarget.getColorTexture();
        if (mainTex == null) return;

        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        int n = debugCallCount++;
        boolean doDebug = (n < DEBUG_ALWAYS_FIRST) || (n % DEBUG_INTERVAL == 0);

        int mainTexId = GlTextureUtil.textureId(mainTex);
        int hudTexId  = GlTextureUtil.textureId(hudFboRt.getColorTexture());

        if (doDebug) {
            Beeeye.LOGGER.info("=== composite frame#{} {}x{} ===", n, fullW, fullH);
            if (hudTexId  > 0) Beeeye.LOGGER.info("  SRC hud   center={} tl={} tr={} bl={} br={}",
                samplePixel(glFboCache.fbo(GlFboCache.Slot.HUD,  hudTexId),  halfW/2,   fullH/2),
                samplePixel(glFboCache.fbo(GlFboCache.Slot.HUD,  hudTexId),  4,         fullH-4),
                samplePixel(glFboCache.fbo(GlFboCache.Slot.HUD,  hudTexId),  halfW-4,   fullH-4),
                samplePixel(glFboCache.fbo(GlFboCache.Slot.HUD,  hudTexId),  4,         4),
                samplePixel(glFboCache.fbo(GlFboCache.Slot.HUD,  hudTexId),  halfW-4,   4));
            if (mainTexId > 0) Beeeye.LOGGER.info("  DST main pre  left={} right={}",
                samplePixel(glFboCache.fbo(GlFboCache.Slot.MAIN, mainTexId), halfW/2,       fullH/2),
                samplePixel(glFboCache.fbo(GlFboCache.Slot.MAIN, mainTexId), halfW+halfW/2, fullH/2));
        }

        // Blit hudFbo (leftEye + HUD) over main left half. hudFbo is opaque (all alpha=255
        // from leftEye background), so ENTITY_OUTLINE_BLIT fully replaces the left half.
        // Disable GL_SCISSOR_TEST: mods/MC may leave it enabled and clip the triangle.
        boolean scissorOn = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (scissorOn) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        try {
            GpuTextureView mainView      = mainTarget.getColorTextureView();
            GpuTextureView hudView       = hudFboRt.getColorTextureView();
            GpuTextureView rightHudView  = rightHudFboRt.getColorTextureView();
            blitWithViewport(mainView, hudView,      nearest, 0,     0, halfW, fullH);
            blitWithViewport(mainView, rightHudView, nearest, halfW, 0, halfW, fullH);
            if (doDebug && mainTexId > 0) Beeeye.LOGGER.info("  DST main after hudBlit left={} right={}",
                samplePixel(glFboCache.fbo(GlFboCache.Slot.MAIN, mainTexId), halfW/2,       fullH/2),
                samplePixel(glFboCache.fbo(GlFboCache.Slot.MAIN, mainTexId), halfW+halfW/2, fullH/2));
        } finally {
            if (scissorOn) GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }

        // Crosshairs drawn on both halves via raw GL.
        if (mainTexId > 0) {
            int mainGl = glFboCache.fbo(GlFboCache.Slot.MAIN, mainTexId);
            BodyCrosshair.drawConvergenceCrosshair(mainGl, fullW, fullH);
            if (HeadTracker.isActive()) {
                BodyCrosshair.draw(mainGl, fullW, fullH);
            }
        }
    }

    /**
     * Clear an FBO via a RenderPass (not CommandEncoder direct clear).
     * On macOS Metal-GL, CommandEncoder clears don't register as Metal render passes,
     * so a subsequent render pass created with OptionalInt.empty() may use
     * MTLLoadActionDontCare instead of MTLLoadActionLoad, losing the transparent clear.
     * Using a RenderPass clear (MTLLoadActionClear) ensures the next render pass sees
     * the cleared transparent content via MTLLoadActionLoad.
     */
    public static void clearFboViaPass(RenderTarget fbo) {
        if (fbo == null) return;
        GpuTextureView colorView = fbo.getColorTextureView();
        if (colorView == null) return;
        GpuTextureView depthView = fbo.getDepthTextureView();
        CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = enc.createRenderPass(
            () -> "Beeeye HUD clear",
            colorView,
            OptionalInt.of(0),  // ARGB 0 = transparent black
            depthView,
            depthView != null ? java.util.OptionalDouble.of(1.0) : java.util.OptionalDouble.empty()
        )) {
            // Close immediately — the Metal render pass load action clears the texture.
        }
    }

    /** Called from MixinGameRenderer to log HUD FBO state at key moments. */
    public static void debugLogHud(String tag) {
        RenderTarget hud = hudFbo;
        if (hud == null) { Beeeye.LOGGER.info("DBG {} hudFbo=null", tag); return; }
        GpuTexture tex = hud.getColorTexture();
        if (tex == null) { Beeeye.LOGGER.info("DBG {} tex=null", tag); return; }
        int texId = GlTextureUtil.textureId(tex);
        if (texId <= 0) { Beeeye.LOGGER.info("DBG {} texId={}", tag, texId); return; }
        int fbo = glFboCache.fbo(GlFboCache.Slot.HUD, texId);
        int w = hud.width, h = hud.height;
        Beeeye.LOGGER.info("DBG {} center={} tl={} tr={} bl={} br={}",
            tag,
            samplePixel(fbo, w/2,   h/2),
            samplePixel(fbo, 4,     h-4),
            samplePixel(fbo, w-4,   h-4),
            samplePixel(fbo, 4,     4),
            samplePixel(fbo, w-4,   4));
    }

    /**
     * Read one RGBA pixel from an already-bound GL FBO (via GL_READ_FRAMEBUFFER).
     * Only binds GL_READ_FRAMEBUFFER — does not touch GL_DRAW_FRAMEBUFFER.
     */
    private static String samplePixel(int fbo, int x, int y) {
        int save = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
        ByteBuffer buf = BufferUtils.createByteBuffer(4);
        GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, save);
        return (buf.get(0)&0xFF)+","+(buf.get(1)&0xFF)+","+(buf.get(2)&0xFF)+","+(buf.get(3)&0xFF);
    }

    /**
     * Alpha-blend src onto dst restricted to viewport (x, y, w, h).
     * Uses ENTITY_OUTLINE_BLIT + screenquad: UVs are NDC-derived so the full src
     * texture maps to the viewport region regardless of x/y offset.
     */
    private static void blitWithViewport(
        GpuTextureView dst, GpuTextureView src, GpuSampler sampler,
        int x, int y, int w, int h
    ) {
        CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = enc.createRenderPass(() -> "Beeeye blit", dst, OptionalInt.empty())) {
            pass.setViewport(x, y, w, h);
            pass.setPipeline(RenderPipelines.ENTITY_OUTLINE_BLIT);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("InSampler", src, sampler);
            pass.draw(0, 3);
        }
    }
}
