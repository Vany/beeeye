package com.beeeye.mixin;

import com.beeeye.Beeeye;
import com.beeeye.BodyCrosshair;
import com.beeeye.GlFboCache;
import com.beeeye.GlFboCache.Slot;
import com.beeeye.GlTextureUtil;
import com.beeeye.HeadTracker;
import com.beeeye.StereoRenderer;
import com.beeeye.StereoRenderer.Eye;
import com.beeeye.StereoRenderer.RenderPhase;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
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
 * Stereo rendering pipeline: each eye renders to its own half-width FBO,
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

    // =========================================================================
    // Render pipeline
    // =========================================================================

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
            // Only reset phase on error — normal flow sets HUD_CAPTURE at end
            StereoRenderer.setPhase(RenderPhase.INACTIVE);
            StereoRenderer.setCurrentEye(null);
        } finally {
            beeeye$inStereoRender = false;
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void beeeye$onRenderTail(
        DeltaTracker deltaTracker,
        boolean doRenderLevel,
        CallbackInfo ci
    ) {
        if (!beeeye$stereoReady) return;
        beeeye$stereoReady = false;
        StereoRenderer.setPhase(RenderPhase.COMPOSITING);

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

        int fullW = mainTarget.width,
            fullH = mainTarget.height,
            halfW = fullW / 2;

        // Resolve GL texture IDs via reflection utility
        int mainTex = GlTextureUtil.textureId(mainTarget.getColorTexture());
        int leftTex = GlTextureUtil.textureId(leftFbo.getColorTexture());
        int rightTex = GlTextureUtil.textureId(rightFbo.getColorTexture());
        int hudTex = GlTextureUtil.textureId(hudFbo.getColorTexture());
        int compositeTex = GlTextureUtil.textureId(
            compositeRT.getColorTexture()
        );
        if (
            mainTex <= 0 ||
            leftTex <= 0 ||
            rightTex <= 0 ||
            hudTex <= 0 ||
            compositeTex <= 0
        ) return;

        GlFboCache cache = StereoRenderer.getGlFboCache();
        int mainGl = cache.fbo(Slot.MAIN, mainTex);
        int leftGl = cache.fbo(Slot.LEFT, leftTex);
        int rightGl = cache.fbo(Slot.RIGHT, rightTex);
        int hudGl = cache.fbo(Slot.HUD, hudTex);
        int compositeGl = cache.fbo(Slot.COMPOSITE, compositeTex);

        // Eyes -> main target halves
        beeeye$blit(leftGl, mainGl, 0, 0, halfW, fullH, 0, 0, halfW, fullH);
        beeeye$blit(
            rightGl,
            mainGl,
            0,
            0,
            halfW,
            fullH,
            halfW,
            0,
            fullW,
            fullH
        );

        // HUD -> both halves of composite buffer
        StereoRenderer.clearFbo(compositeRT);
        beeeye$blit(hudGl, compositeGl, 0, 0, halfW, fullH, 0, 0, halfW, fullH);
        beeeye$blit(
            hudGl,
            compositeGl,
            0,
            0,
            halfW,
            fullH,
            halfW,
            0,
            fullW,
            fullH
        );

        // Alpha-blend composite (HUD) onto main target
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        compositeRT.blitAndBlendToTexture(mainTarget.getColorTextureView());

        // Body crosshair overlay
        if (HeadTracker.isActive()) {
            BodyCrosshair.draw(mainGl, fullW, fullH);
        }

        StereoRenderer.setPhase(RenderPhase.INACTIVE);
    }

    // =========================================================================
    // Stereo frame orchestration
    // =========================================================================

    @Unique
    private void beeeye$renderStereoFrame(DeltaTracker deltaTracker) {
        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        StereoRenderer.ensureFramebuffers(mainTarget.width, mainTarget.height);

        if (
            StereoRenderer.isDynamicConvergenceEnabled() &&
            minecraft.player != null
        ) {
            Vec3 eyePos = minecraft.player.getEyePosition(1.0f);
            HitResult hit = minecraft.hitResult;
            float targetDistance;
            if (hit != null && hit.getType() != HitResult.Type.MISS) {
                targetDistance = (float) hit.getLocation().distanceTo(eyePos);
            } else {
                targetDistance = beeeye$pickEntityViaEyeRays(eyePos);
            }
            StereoRenderer.updateDynamicConvergence(targetDistance);
        }

        for (Eye eye : Eye.values()) {
            StereoRenderer.setCurrentEye(eye);
            StereoRenderer.setPhase(RenderPhase.EYE_RENDER);
            StereoRenderer.clearFbo(StereoRenderer.getCurrentEyeFbo());
            updateCamera(deltaTracker);
            beeeye$refreshGlobalUniforms(deltaTracker);
            renderLevel(deltaTracker);
        }

        StereoRenderer.setCurrentEye(null);
        StereoRenderer.clearFbo(StereoRenderer.getHudFbo());
        beeeye$stereoReady = true;
        StereoRenderer.setPhase(RenderPhase.HUD_CAPTURE);
    }

    // =========================================================================
    // Dynamic convergence — eye-ray picking
    // =========================================================================

    @Unique
    private float beeeye$pickEntityViaEyeRays(Vec3 eyePos) {
        float staticConv = StereoRenderer.getStaticConvergence();
        if (
            minecraft.player == null || minecraft.level == null
        ) return staticConv;

        Vec3 forward = new Vec3(mainCamera.forwardVector());
        Vec3 left = new Vec3(mainCamera.leftVector());
        float halfIPD = StereoRenderer.getIPD() / 2.0f;
        float convDist = StereoRenderer.getConvergence();
        Vec3 convergencePoint = eyePos.add(forward.scale(convDist));

        Vec3 leftEyePos = eyePos.add(left.scale(halfIPD));
        Vec3 rightEyePos = eyePos.subtract(left.scale(halfIPD));

        double bestDistSq = Double.MAX_VALUE;

        for (Vec3 eyeOrigin : new Vec3[] { leftEyePos, rightEyePos }) {
            HitResult blockHit = minecraft.level.clip(
                new ClipContext(
                    eyeOrigin,
                    convergencePoint,
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    minecraft.player
                )
            );
            if (blockHit.getType() != HitResult.Type.MISS) {
                double d = blockHit.getLocation().distanceToSqr(eyePos);
                if (d < bestDistSq) bestDistSq = d;
            }

            AABB searchBox = new AABB(eyeOrigin, convergencePoint).inflate(1.0);
            EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                minecraft.player,
                eyeOrigin,
                convergencePoint,
                searchBox,
                EntitySelector.CAN_BE_PICKED,
                convDist * convDist + 4.0
            );
            if (entityHit != null) {
                double d = entityHit.getLocation().distanceToSqr(eyePos);
                if (d < bestDistSq) bestDistSq = d;
            }
        }

        return bestDistSq < Double.MAX_VALUE
            ? (float) Math.sqrt(bestDistSq)
            : staticConv;
    }

    // =========================================================================
    // GL helpers
    // =========================================================================

    @Unique
    private static void beeeye$blit(
        int readFbo,
        int drawFbo,
        int sx0,
        int sy0,
        int sx1,
        int sy1,
        int dx0,
        int dy0,
        int dx1,
        int dy1
    ) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
        GL30.glBlitFramebuffer(
            sx0,
            sy0,
            sx1,
            sy1,
            dx0,
            dy0,
            dx1,
            dy1,
            GL11.GL_COLOR_BUFFER_BIT,
            GL11.GL_NEAREST
        );
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
}
