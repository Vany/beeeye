package com.beeeye;

import com.beeeye.StereoState.Eye;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Draws body direction crosshair as {@code < >} brackets on the main framebuffer,
 * and convergence-offset {@code +} crosshair per eye in stereo mode.
 * Uses glScissor + glClear — guaranteed to work on macOS core profile (no legacy GL).
 * Perspective-correct projection accounts for vertical FOV and aspect ratio.
 */
public class BodyCrosshair {

    /** Crosshair arm length in pixels (matches vanilla 15px sprite: 7px arm + 1px center). */
    private static final int CROSS_ARM = 7;

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

        HeadTracker.Quat delta = HeadTracker.getFrameDelta();
        float deltaYaw = delta.toYaw();
        float deltaPitch = delta.toPitch();

        // Body position in eye-half coordinates (halfW x fullH)
        int halfW = fullW / 2;
        float halfHW = halfW / 2.0f,
            halfHH = fullH / 2.0f;
        // MC fov() is vertical FOV — derive horizontal from aspect ratio
        float tanHalfHFov = tanHalfFov * ((float) halfW / fullH);
        float bodyXRaw =
            halfHW -
            (float) (Math.tan(Math.toRadians(deltaYaw)) / tanHalfHFov) * halfHW;
        float bodyYRaw =
            halfHH -
            (float) (Math.tan(Math.toRadians(deltaPitch)) / tanHalfFov) *
            halfHH;
        // Clamp to viewport edges — crosshair sticks to border, half-visible
        float bodyX = Math.clamp(bodyXRaw, 0, halfW);
        float bodyY = Math.clamp(bodyYRaw, 0, fullH);

        float guiScale = (float) mc.getWindow().getGuiScale();
        float s = BRACKET_SIZE * guiScale;
        int t = Math.max((int) (1.0f * guiScale), 1);

        boolean aligned =
            Math.abs(halfHW - bodyX) < s && Math.abs(halfHH - bodyY) < s;
        float[] color = aligned ? COLOR_ALIGNED : COLOR_TURNED;

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, mainFbo);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glClearColor(color[0], color[1], color[2], color[3]);

        boolean outside =
            bodyXRaw < 0 ||
            bodyXRaw > halfW ||
            bodyYRaw < 0 ||
            bodyYRaw > fullH;

        // Draw on left half (xOffset=0) and right half (xOffset=halfW)
        for (int xOffset : new int[] { 0, halfW }) {
            if (outside) {
                // Single chevron (>) rotated to point from clamped pos toward actual pos.
                float dx = bodyXRaw - bodyX;
                float dy = bodyYRaw - bodyY;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 0.001f) continue;
                // Unit direction toward actual crosshair
                float dirX = dx / len;
                float dirY = dy / len;
                // Perpendicular (rotated 90 degrees)
                float perpX = -dirY;
                float perpY = dirX;
                // Chevron tip = clamped pos + direction * s
                float tipX = bodyX + dirX * 2 * s;
                float tipY = bodyY + dirY * 2 * s;
                // Two arms from tip, spread by perpendicular
                float armX = bodyX + perpX * s;
                float armY = bodyY + perpY * s;
                float arm2X = bodyX - perpX * s;
                float arm2Y = bodyY - perpY * s;
                scissorLine(tipX, tipY, armX, armY, t, xOffset, fullH);
                scissorLine(tipX, tipY, arm2X, arm2Y, t, xOffset, fullH);
            } else {
                // Normal < > brackets
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
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /**
     * Draw convergence-offset {@code +} crosshair per eye on the main FBO.
     * Each eye's crosshair is shifted horizontally so it appears at the
     * convergence distance (zero parallax plane) in stereo.
     *
     * Shift formula: shiftPx = sign * (IPD/2) / convergence * fullH / (2 * tan(vFov/2))
     */
    public static void drawConvergenceCrosshair(
        int mainFbo,
        int fullW,
        int fullH
    ) {
        Minecraft mc = Minecraft.getInstance();
        float guiScale = (float) mc.getWindow().getGuiScale();
        int t = Math.max((int) (1.0f * guiScale), 1);
        int arm = (int) (CROSS_ARM * guiScale);

        int halfW = fullW / 2;
        float centerX = halfW / 2.0f;
        float centerY = fullH / 2.0f;

        // No pixel shift: objects at convergence distance have zero parallax,
        // so the aiming crosshair at screen center already converges correctly
        // through the off-axis projection + camera eye offset.

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, mainFbo);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glClearColor(1.0f, 1.0f, 1.0f, 0.8f);

        for (Eye eye : Eye.values()) {
            int xOffset = eye == Eye.LEFT ? 0 : halfW;
            float cx = centerX;
            int halfT = Math.max(t / 2, 1);

            // Horizontal bar
            int hx0 = (int) (cx - arm) + xOffset;
            int hy0 = fullH - (int) centerY - halfT;
            GL11.glScissor(hx0, hy0, arm * 2 + t, t);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            // Vertical bar
            int vx0 = (int) cx - halfT + xOffset;
            int vy0 = fullH - (int) centerY - arm;
            GL11.glScissor(vx0, vy0, t, arm * 2 + t);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
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
