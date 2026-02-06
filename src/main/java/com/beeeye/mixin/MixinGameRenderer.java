package com.beeeye.mixin;

import com.beeeye.Beeeye;
import com.beeeye.StereoRenderer;
import com.beeeye.StereoRenderer.Eye;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stereo rendering: each eye renders to its own half-width FBO,
 * then composited to main target. HUD alpha-composited onto both eyes.
 *
 * Eye rendering: redirect getMainRenderTarget() to eye FBO during renderLevel(),
 * so Minecraft renders directly into half-width buffer with correct aspect ratio.
 * Off-axis projection (MixinProjectionMatrix) provides per-eye parallax.
 *
 * HUD: renders to half-width hudFbo (width faking active). In compositing,
 * the same pixels are placed in both eye halves.
 */
@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private Camera mainCamera;

    @Shadow
    @Final
    private GlobalSettingsUniform globalSettingsUniform;

    @Shadow
    public abstract void renderLevel(DeltaTracker deltaTracker);

    @Shadow
    public abstract void updateCamera(DeltaTracker deltaTracker);

    @Unique
    private boolean beeeye$inStereoRender = false;

    @Unique
    private boolean beeeye$stereoReady = false;

    /**
     * Intercept renderLevel() — render both eyes into separate half-width FBOs.
     */
    @Inject(method = "renderLevel", at = @At("HEAD"), cancellable = true)
    private void beeeye$onRenderLevel(
        DeltaTracker deltaTracker,
        CallbackInfo ci
    ) {
        if (!Beeeye.isStereoEnabled() || beeeye$inStereoRender) return;
        if (minecraft.level == null) return;

        ci.cancel();
        beeeye$inStereoRender = true;
        try {
            beeeye$renderStereoFrame(deltaTracker);
        } catch (Exception e) {
            Beeeye.LOGGER.error("Stereo render error", e);
        } finally {
            beeeye$inStereoRender = false;
            StereoRenderer.setInStereoPass(false);
            StereoRenderer.setRenderingEye(false);
            StereoRenderer.setCurrentEye(null);
        }
    }

    /**
     * After render() completes, HUD is in hudFbo. Restore stereo world,
     * alpha-composite HUD onto both halves.
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void beeeye$onRenderTail(
        DeltaTracker deltaTracker,
        boolean doRenderLevel,
        CallbackInfo ci
    ) {
        if (!beeeye$stereoReady) return;
        beeeye$stereoReady = false;
        StereoRenderer.setHudPhase(false);

        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        RenderTarget leftFbo = StereoRenderer.getLeftEyeFbo();
        RenderTarget rightFbo = StereoRenderer.getRightEyeFbo();
        RenderTarget hudFbo = StereoRenderer.getHudFbo();
        RenderTarget compositeRT = StereoRenderer.getCompositeTarget();
        if (
            leftFbo == null ||
            rightFbo == null ||
            hudFbo == null ||
            compositeRT == null
        ) return;

        int fullWidth = mainTarget.width;
        int fullHeight = mainTarget.height;
        int halfWidth = fullWidth / 2;

        int mainTexId = beeeye$getTextureId(mainTarget.getColorTexture());
        int leftTexId = beeeye$getTextureId(leftFbo.getColorTexture());
        int rightTexId = beeeye$getTextureId(rightFbo.getColorTexture());
        int hudTexId = beeeye$getTextureId(hudFbo.getColorTexture());
        int compositeTexId = beeeye$getTextureId(compositeRT.getColorTexture());
        if (
            mainTexId <= 0 ||
            leftTexId <= 0 ||
            rightTexId <= 0 ||
            hudTexId <= 0 ||
            compositeTexId <= 0
        ) return;

        // Temp GL FBOs wrapping RenderTarget textures
        int mainGlFbo = beeeye$wrapTexAsFbo(mainTexId);
        int leftGlFbo = beeeye$wrapTexAsFbo(leftTexId);
        int rightGlFbo = beeeye$wrapTexAsFbo(rightTexId);
        int hudGlFbo = beeeye$wrapTexAsFbo(hudTexId);
        int compositeGlFbo = beeeye$wrapTexAsFbo(compositeTexId);

        // Restore stereo world: eye FBOs → main target halves
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, leftGlFbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, mainGlFbo);
        GL30.glBlitFramebuffer(
            0,
            0,
            halfWidth,
            fullHeight,
            0,
            0,
            halfWidth,
            fullHeight,
            GL11.GL_COLOR_BUFFER_BIT,
            GL11.GL_NEAREST
        );

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, rightGlFbo);
        GL30.glBlitFramebuffer(
            0,
            0,
            halfWidth,
            fullHeight,
            halfWidth,
            0,
            fullWidth,
            fullHeight,
            GL11.GL_COLOR_BUFFER_BIT,
            GL11.GL_NEAREST
        );

        // Copy identical half-width hudFbo to both halves of composite.
        // Same source pixels → crosshair at same position on both eyes.
        StereoRenderer.clearFbo(compositeRT);

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, hudGlFbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, compositeGlFbo);

        // hudFbo → left half
        GL30.glBlitFramebuffer(
            0,
            0,
            halfWidth,
            fullHeight,
            0,
            0,
            halfWidth,
            fullHeight,
            GL11.GL_COLOR_BUFFER_BIT,
            GL11.GL_NEAREST
        );

        // hudFbo → right half (identical pixels)
        GL30.glBlitFramebuffer(
            0,
            0,
            halfWidth,
            fullHeight,
            halfWidth,
            0,
            fullWidth,
            fullHeight,
            GL11.GL_COLOR_BUFFER_BIT,
            GL11.GL_NEAREST
        );

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        compositeRT.blitAndBlendToTexture(mainTarget.getColorTextureView());

        // Cleanup temp FBOs
        GL30.glDeleteFramebuffers(mainGlFbo);
        GL30.glDeleteFramebuffers(leftGlFbo);
        GL30.glDeleteFramebuffers(rightGlFbo);
        GL30.glDeleteFramebuffers(hudGlFbo);
        GL30.glDeleteFramebuffers(compositeGlFbo);
    }

    @Unique
    private void beeeye$renderStereoFrame(DeltaTracker deltaTracker) {
        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        int fullWidth = mainTarget.width;
        int fullHeight = mainTarget.height;

        StereoRenderer.ensureFramebuffers(fullWidth, fullHeight);
        StereoRenderer.setInStereoPass(true);

        // === LEFT EYE ===
        StereoRenderer.setCurrentEye(Eye.LEFT);
        StereoRenderer.setRenderingEye(true);
        StereoRenderer.clearFbo(StereoRenderer.getLeftEyeFbo());
        updateCamera(deltaTracker);
        beeeye$refreshGlobalUniforms(deltaTracker);
        renderLevel(deltaTracker);
        StereoRenderer.setRenderingEye(false);

        // === RIGHT EYE ===
        StereoRenderer.setCurrentEye(Eye.RIGHT);
        StereoRenderer.setRenderingEye(true);
        StereoRenderer.clearFbo(StereoRenderer.getRightEyeFbo());
        updateCamera(deltaTracker);
        beeeye$refreshGlobalUniforms(deltaTracker);
        renderLevel(deltaTracker);
        StereoRenderer.setRenderingEye(false);

        StereoRenderer.setCurrentEye(null);
        StereoRenderer.setInStereoPass(false);

        // Clear hudFbo, enable HUD phase
        StereoRenderer.clearFbo(StereoRenderer.getHudFbo());
        beeeye$stereoReady = true;
        StereoRenderer.setHudPhase(true);
    }

    /**
     * Refresh GlobalSettingsUniform with current camera position.
     * Must be called after updateCamera() for each eye so the shader
     * sees the correct camera pos for block vertex transforms.
     */
    @Unique
    private void beeeye$refreshGlobalUniforms(DeltaTracker deltaTracker) {
        globalSettingsUniform.update(
            minecraft.getWindow().getWidth(),
            minecraft.getWindow().getHeight(),
            minecraft.options.glintStrength().get(),
            minecraft.level == null ? 0L : minecraft.level.getGameTime(),
            deltaTracker,
            minecraft.options.getMenuBackgroundBlurriness(),
            mainCamera,
            minecraft.options.textureFiltering().get() ==
                net.minecraft.client.TextureFilteringMethod.ANISOTROPIC
        );
    }

    @Unique
    private int beeeye$wrapTexAsFbo(int textureId) {
        int fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER,
            GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D,
            textureId,
            0
        );
        return fbo;
    }

    @Unique
    private int beeeye$getTextureId(GpuTexture texture) {
        if (texture == null) return -1;
        try {
            if (texture.getClass().getName().contains("Validation")) {
                java.lang.reflect.Method unwrap = texture
                    .getClass()
                    .getMethod("getRealTexture");
                texture = (GpuTexture) unwrap.invoke(texture);
            }
            java.lang.reflect.Method method = texture
                .getClass()
                .getMethod("glId");
            return (int) method.invoke(texture);
        } catch (Exception e) {
            return -1;
        }
    }
}
