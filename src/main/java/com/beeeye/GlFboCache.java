package com.beeeye;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Cached GL framebuffer objects for compositing.
 * Each slot wraps a texture into a GL FBO, auto-recreating when the texture ID changes.
 * Uses primitive int arrays — zero boxing overhead per frame.
 */
public class GlFboCache {

    public enum Slot {
        MAIN,
        LEFT,
        RIGHT,
        HUD,
        COMPOSITE,
    }

    private static final int COUNT = Slot.values().length;

    private final int[] fbos = new int[COUNT];
    private final int[] textures = new int[COUNT];
    private boolean statusErrorLogged = false;

    /**
     * Get a valid GL FBO for the given slot, bound to the given texture.
     * Recreates the FBO if the texture changed since last call.
     */
    public int fbo(Slot slot, int textureId) {
        int i = slot.ordinal();
        if (fbos[i] != 0 && textures[i] == textureId) {
            return fbos[i];
        }

        if (fbos[i] != 0) {
            GL30.glDeleteFramebuffers(fbos[i]);
        }
        int fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER,
            GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D,
            textureId,
            0
        );

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE && !statusErrorLogged) {
            statusErrorLogged = true;
            Beeeye.LOGGER.error(
                "FBO {} incomplete (status 0x{}), compositing may fail",
                slot,
                Integer.toHexString(status)
            );
        }

        fbos[i] = fbo;
        textures[i] = textureId;
        return fbo;
    }

    /** Delete all cached GL FBOs. */
    public void cleanup() {
        for (int i = 0; i < COUNT; i++) {
            if (fbos[i] != 0) {
                GL30.glDeleteFramebuffers(fbos[i]);
                fbos[i] = 0;
                textures[i] = 0;
            }
        }
        statusErrorLogged = false;
    }
}
