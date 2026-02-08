package com.beeeye;

import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Draws body direction crosshair as {@code < >} brackets on the main framebuffer.
 * Uses glScissor + glClear — guaranteed to work on macOS core profile (no legacy GL).
 * Perspective-correct projection accounts for vertical FOV and aspect ratio.
 */
public class BodyCrosshair {

    /** Bracket size in GUI-scaled units (pixel size = this * guiScale). */
    private static final float BRACKET_SIZE = 8.0f;

    /** RGBA when head is aligned with body direction (soft green). */
    private static final float[] COLOR_ALIGNED = { 0.5f, 1.0f, 0.5f, 0.9f };

    /** RGBA when head is turned away from body (warm yellow). */
    private static final float[] COLOR_TURNED = { 1.0f, 1.0f, 0.3f, 0.9f };

    /**
     * Draw body crosshair on both eye halves of the main FBO.
     * Color shifts from green (aligned) to yellow (head turned away).
     */
    public static void draw(int mainFbo, int fullW, int fullH) {
        Minecraft mc = Minecraft.getInstance();
        float fovDeg = mc.options.fov().get();
        float tanHalfFov = (float) Math.tan(Math.toRadians(fovDeg / 2.0));

        HeadTracker.Quat delta = HeadTracker.getDelta();
        float deltaYaw = delta.toYaw();
        float deltaPitch = delta.toPitch();

        // Body position in eye-half coordinates (halfW x fullH)
        int halfW = fullW / 2;
        float halfHW = halfW / 2.0f,
            halfHH = fullH / 2.0f;
        // MC fov() is vertical FOV — derive horizontal from aspect ratio
        float tanHalfHFov = tanHalfFov * ((float) halfW / fullH);
        float bodyX =
            halfHW -
            (float) (Math.tan(Math.toRadians(deltaYaw)) / tanHalfHFov) * halfHW;
        float bodyY =
            halfHH -
            (float) (Math.tan(Math.toRadians(deltaPitch)) / tanHalfFov) *
            halfHH;

        float guiScale = (float) mc.getWindow().getGuiScale();
        float s = BRACKET_SIZE * guiScale;
        int t = Math.max((int) (1.0f * guiScale), 1);

        boolean aligned =
            Math.abs(halfHW - bodyX) < s && Math.abs(halfHH - bodyY) < s;
        float[] color = aligned ? COLOR_ALIGNED : COLOR_TURNED;

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, mainFbo);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glClearColor(color[0], color[1], color[2], color[3]);

        // Draw < > on left half (xOffset=0) and right half (xOffset=halfW)
        for (int xOffset : new int[] { 0, halfW }) {
            // Left bracket <
            scissorLine(
                bodyX - 2 * s,
                bodyY,
                bodyX - s,
                bodyY - s,
                t,
                xOffset,
                fullH
            );
            scissorLine(
                bodyX - 2 * s,
                bodyY,
                bodyX - s,
                bodyY + s,
                t,
                xOffset,
                fullH
            );
            // Right bracket >
            scissorLine(
                bodyX + 2 * s,
                bodyY,
                bodyX + s,
                bodyY - s,
                t,
                xOffset,
                fullH
            );
            scissorLine(
                bodyX + 2 * s,
                bodyY,
                bodyX + s,
                bodyY + s,
                t,
                xOffset,
                fullH
            );
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /** Rasterize a line as scissored pixel clears (Bresenham-style stepping). */
    private static void scissorLine(
        float x0,
        float y0,
        float x1,
        float y1,
        int thickness,
        int xOffset,
        int fbHeight
    ) {
        int steps = (int) Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        if (steps == 0) return;
        float dx = (x1 - x0) / steps,
            dy = (y1 - y0) / steps;
        int halfT = Math.max(thickness / 2, 1);

        for (int i = 0; i <= steps; i++) {
            int px = (int) (x0 + dx * i) + xOffset;
            int py = (int) (y0 + dy * i);
            GL11.glScissor(
                px - halfT,
                fbHeight - py - halfT,
                thickness,
                thickness
            );
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        }
    }
}
