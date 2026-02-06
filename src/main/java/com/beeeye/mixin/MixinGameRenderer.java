package com.beeeye.mixin;

import com.beeeye.Beeeye;
import com.beeeye.StereoRenderer;
import com.beeeye.StereoRenderer.Eye;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import java.lang.reflect.Method;
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

    // Cached reflection methods
    @Unique
    private static final java.util.Map<
        Class<?>,
        Method
    > beeeye$glIdMethodCache = new java.util.concurrent.ConcurrentHashMap<>();

    @Unique
    private static Method beeeye$unwrapMethod = null;

    @Unique
    private static boolean beeeye$reflectionInitialized = false;

    @Unique
    private static boolean beeeye$reflectionErrorLogged = false;

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
     * After render() completes, HUD is in hudFbo. Composite stereo + HUD to main target.
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

        // Get texture IDs and ensure cached FBOs are valid
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

        // Update cached FBOs if textures changed
        StereoRenderer.setMainGlFbo(
            beeeye$ensureFbo(
                StereoRenderer.getMainGlFbo(),
                mainTexId,
                StereoRenderer.getCachedMainTex()
            )
        );
        StereoRenderer.setLeftGlFbo(
            beeeye$ensureFbo(
                StereoRenderer.getLeftGlFbo(),
                leftTexId,
                StereoRenderer.getCachedLeftTex()
            )
        );
        StereoRenderer.setRightGlFbo(
            beeeye$ensureFbo(
                StereoRenderer.getRightGlFbo(),
                rightTexId,
                StereoRenderer.getCachedRightTex()
            )
        );
        StereoRenderer.setHudGlFbo(
            beeeye$ensureFbo(
                StereoRenderer.getHudGlFbo(),
                hudTexId,
                StereoRenderer.getCachedHudTex()
            )
        );
        StereoRenderer.setCompositeGlFbo(
            beeeye$ensureFbo(
                StereoRenderer.getCompositeGlFbo(),
                compositeTexId,
                StereoRenderer.getCachedCompositeTex()
            )
        );
        StereoRenderer.setCachedMainTex(mainTexId);
        StereoRenderer.setCachedLeftTex(leftTexId);
        StereoRenderer.setCachedRightTex(rightTexId);
        StereoRenderer.setCachedHudTex(hudTexId);
        StereoRenderer.setCachedCompositeTex(compositeTexId);

        // Blit eye FBOs → main target halves
        GL30.glBindFramebuffer(
            GL30.GL_READ_FRAMEBUFFER,
            StereoRenderer.getLeftGlFbo()
        );
        GL30.glBindFramebuffer(
            GL30.GL_DRAW_FRAMEBUFFER,
            StereoRenderer.getMainGlFbo()
        );
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

        GL30.glBindFramebuffer(
            GL30.GL_READ_FRAMEBUFFER,
            StereoRenderer.getRightGlFbo()
        );
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

        // Copy hudFbo to both halves of composite
        StereoRenderer.clearFbo(compositeRT);
        GL30.glBindFramebuffer(
            GL30.GL_READ_FRAMEBUFFER,
            StereoRenderer.getHudGlFbo()
        );
        GL30.glBindFramebuffer(
            GL30.GL_DRAW_FRAMEBUFFER,
            StereoRenderer.getCompositeGlFbo()
        );
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
    }

    @Unique
    private void beeeye$renderStereoFrame(DeltaTracker deltaTracker) {
        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        StereoRenderer.ensureFramebuffers(mainTarget.width, mainTarget.height);
        StereoRenderer.setInStereoPass(true);

        // Left eye
        StereoRenderer.setCurrentEye(Eye.LEFT);
        StereoRenderer.setRenderingEye(true);
        StereoRenderer.clearFbo(StereoRenderer.getLeftEyeFbo());
        updateCamera(deltaTracker);
        beeeye$refreshGlobalUniforms(deltaTracker);
        renderLevel(deltaTracker);
        StereoRenderer.setRenderingEye(false);

        // Right eye
        StereoRenderer.setCurrentEye(Eye.RIGHT);
        StereoRenderer.setRenderingEye(true);
        StereoRenderer.clearFbo(StereoRenderer.getRightEyeFbo());
        updateCamera(deltaTracker);
        beeeye$refreshGlobalUniforms(deltaTracker);
        renderLevel(deltaTracker);
        StereoRenderer.setRenderingEye(false);

        StereoRenderer.setCurrentEye(null);
        StereoRenderer.setInStereoPass(false);

        // Prepare HUD phase
        StereoRenderer.clearFbo(StereoRenderer.getHudFbo());
        beeeye$stereoReady = true;
        StereoRenderer.setHudPhase(true);
    }

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

    /** Ensure FBO exists and points to correct texture; recreate if texture changed. */
    @Unique
    private static int beeeye$ensureFbo(
        int fbo,
        int newTexId,
        int cachedTexId
    ) {
        if (fbo != 0 && newTexId == cachedTexId) {
            return fbo;
        }
        if (fbo != 0) {
            GL30.glDeleteFramebuffers(fbo);
        }
        fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER,
            GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D,
            newTexId,
            0
        );
        return fbo;
    }

    /** Get GL texture ID via cached reflection (per-class method cache). */
    @Unique
    private static int beeeye$getTextureId(GpuTexture texture) {
        if (texture == null) return -1;
        try {
            // Initialize unwrap method cache once
            if (!beeeye$reflectionInitialized) {
                beeeye$reflectionInitialized = true;
                try {
                    if (texture.getClass().getName().contains("Validation")) {
                        beeeye$unwrapMethod = texture
                            .getClass()
                            .getMethod("getRealTexture");
                    }
                } catch (Exception ignored) {}
            }

            // Unwrap validation texture if needed
            GpuTexture realTexture = texture;
            if (
                beeeye$unwrapMethod != null &&
                texture.getClass().getName().contains("Validation")
            ) {
                realTexture = (GpuTexture) beeeye$unwrapMethod.invoke(texture);
            }

            // Get glId method from per-class cache
            Class<?> textureClass = realTexture.getClass();
            Method glIdMethod = beeeye$glIdMethodCache.computeIfAbsent(
                textureClass,
                cls -> {
                    try {
                        return cls.getMethod("glId");
                    } catch (NoSuchMethodException e) {
                        return null;
                    }
                }
            );

            if (glIdMethod == null) {
                if (!beeeye$reflectionErrorLogged) {
                    Beeeye.LOGGER.warn(
                        "No glId method found for texture class: {}",
                        textureClass.getName()
                    );
                    beeeye$reflectionErrorLogged = true;
                }
                return -1;
            }

            return (int) glIdMethod.invoke(realTexture);
        } catch (Exception e) {
            if (!beeeye$reflectionErrorLogged) {
                Beeeye.LOGGER.warn(
                    "Failed to get texture ID via reflection",
                    e
                );
                beeeye$reflectionErrorLogged = true;
            }
            return -1;
        }
    }
}
