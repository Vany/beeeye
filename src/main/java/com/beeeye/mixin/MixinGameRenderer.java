package com.beeeye.mixin;

import com.beeeye.Beeeye;
import com.beeeye.BinocularPicker;
import com.beeeye.BodyCrosshair;
import com.beeeye.Convergence;
import com.beeeye.GlFboCache;
import com.beeeye.GlFboCache.Slot;
import com.beeeye.GlTextureUtil;
import com.beeeye.HeadTracker;
import com.beeeye.StereoRenderer;
import com.beeeye.StereoRenderer.BlitRect;
import com.beeeye.StereoState;
import com.beeeye.StereoState.Eye;
import com.beeeye.StereoState.RenderPhase;
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
            StereoState.setPhase(RenderPhase.INACTIVE);
            StereoState.setCurrentEye(null);
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
        StereoState.setPhase(RenderPhase.COMPOSITING);

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

        int fullW = mainTarget.width;
        int fullH = mainTarget.height;
        int halfW = fullW / 2;

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

        BlitRect eye = BlitRect.region(0, 0, halfW, fullH);
        BlitRect leftHalf = eye;
        BlitRect rightHalf = BlitRect.region(halfW, 0, halfW, fullH);

        // Eyes -> main target halves
        beeeye$blit(leftGl, mainGl, eye, leftHalf);
        beeeye$blit(rightGl, mainGl, eye, rightHalf);

        // HUD -> both halves of composite buffer
        StereoRenderer.clearFbo(compositeRT);
        beeeye$blit(hudGl, compositeGl, eye, leftHalf);
        beeeye$blit(hudGl, compositeGl, eye, rightHalf);

        // Alpha-blend composite (HUD) onto main target
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        compositeRT.blitAndBlendToTexture(mainTarget.getColorTextureView());

        // Convergence-offset crosshair (replaces vanilla crosshair suppressed by MixinGui)
        BodyCrosshair.drawConvergenceCrosshair(mainGl, fullW, fullH);

        // Body crosshair overlay
        if (HeadTracker.isActive()) {
            BodyCrosshair.draw(mainGl, fullW, fullH);
        }

        StereoState.setPhase(RenderPhase.INACTIVE);
    }

    // =========================================================================
    // Stereo frame orchestration
    // =========================================================================

    @Unique
    private void beeeye$renderStereoFrame(DeltaTracker deltaTracker) {
        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        StereoRenderer.ensureFramebuffers(mainTarget.width, mainTarget.height);

        for (Eye eye : Eye.values()) {
            StereoState.setCurrentEye(eye);
            StereoState.setPhase(RenderPhase.EYE_RENDER);
            StereoRenderer.clearFbo(StereoState.getCurrentEyeFbo());
            updateCamera(deltaTracker);

            // Update convergence after LEFT camera setup — mainCamera now
            // includes head tracking, so rays match actual view direction.
            if (
                eye == Eye.LEFT &&
                Convergence.isDynamic() &&
                minecraft.player != null
            ) {
                float targetDistance = BinocularPicker.pick(
                    mainCamera,
                    minecraft.level,
                    minecraft.player
                );
                Convergence.update(targetDistance);
            }

            beeeye$refreshGlobalUniforms(deltaTracker);
            renderLevel(deltaTracker);
        }

        StereoState.setCurrentEye(null);
        StereoRenderer.clearFbo(StereoRenderer.getHudFbo());
        beeeye$stereoReady = true;
        StereoState.setPhase(RenderPhase.HUD_CAPTURE);
    }

    // =========================================================================
    // Head-tracked interaction picking
    // =========================================================================

    @Inject(method = "pick", at = @At("TAIL"))
    private void beeeye$overridePick(float partialTick, CallbackInfo ci) {
        if (!Beeeye.isStereoEnabled() || !HeadTracker.isActive()) return;
        if (minecraft.player == null || minecraft.level == null) return;

        Vec3 eyePos = minecraft.player.getEyePosition(partialTick);
        Vec3 forward = new Vec3(mainCamera.forwardVector());

        double blockRange = minecraft.player.blockInteractionRange();
        double entityRange = minecraft.player.entityInteractionRange();
        double maxRange = Math.max(blockRange, entityRange);
        Vec3 endPos = eyePos.add(forward.scale(maxRange));

        // Block raycast
        HitResult blockHit = minecraft.level.clip(
            new ClipContext(
                eyePos,
                endPos,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                minecraft.player
            )
        );

        // Entity raycast
        AABB searchBox = new AABB(eyePos, endPos).inflate(1.0);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
            minecraft.player,
            eyePos,
            endPos,
            searchBox,
            EntitySelector.CAN_BE_PICKED,
            entityRange * entityRange
        );

        // Pick closest
        HitResult best = blockHit;
        if (entityHit != null) {
            double blockDist =
                blockHit.getType() != HitResult.Type.MISS
                    ? blockHit.getLocation().distanceToSqr(eyePos)
                    : Double.MAX_VALUE;
            double entityDist = entityHit.getLocation().distanceToSqr(eyePos);
            if (entityDist < blockDist) best = entityHit;
        }

        minecraft.hitResult = best;
        minecraft.crosshairPickEntity =
            best instanceof EntityHitResult ehr ? ehr.getEntity() : null;
    }

    // =========================================================================
    // GL helpers
    // =========================================================================

    @Unique
    private static void beeeye$blit(
        int readFbo,
        int drawFbo,
        BlitRect src,
        BlitRect dst
    ) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
        GL30.glBlitFramebuffer(
            src.x0(),
            src.y0(),
            src.x1(),
            src.y1(),
            dst.x0(),
            dst.y0(),
            dst.x1(),
            dst.y1(),
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
