package com.beeeye.mixin;

import com.beeeye.Beeeye;
import com.beeeye.HeadTracker;
import com.beeeye.StereoRenderer;
import com.beeeye.StereoState;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Redirect getMainRenderTarget() during stereo rendering.
 * Also overrides pick() for head-tracking: replaces hit detection direction
 * with the head-tracked camera forward vector instead of vanilla crosshair.
 */
@Mixin(Minecraft.class)
public class MixinMinecraft {

    @Inject(
        method = "getMainRenderTarget",
        at = @At("HEAD"),
        cancellable = true
    )
    private void beeeye$overrideRenderTarget(
        CallbackInfoReturnable<RenderTarget> cir
    ) {
        if (!Beeeye.isStereoEnabled()) return;

        if (StereoState.isRenderingEye()) {
            RenderTarget eyeFbo = StereoState.getCurrentEyeFbo();
            if (eyeFbo != null) {
                cir.setReturnValue(eyeFbo);
            } else {
                Beeeye.LOGGER.warn("getMainRenderTarget: EYE_RENDER but eyeFbo is null for eye={}", StereoState.getCurrentEye());
            }
            return;
        }

        if (StereoState.isHudPhase()) {
            RenderTarget hudFbo = StereoRenderer.getActiveHudFbo();
            if (hudFbo != null) {
                cir.setReturnValue(hudFbo);
            } else {
                Beeeye.LOGGER.warn("getMainRenderTarget: HUD_CAPTURE but active hudFbo is null");
            }
        }
    }

    // Head-tracking: override pick direction with camera forward vector
    @Inject(method = "pick", at = @At("TAIL"))
    private void beeeye$overridePick(float partialTick, CallbackInfo ci) {
        if (!Beeeye.isStereoEnabled() || !HeadTracker.isActive()) return;
        Minecraft mc = (Minecraft) (Object) this;
        if (mc.player == null || mc.level == null) return;

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 eyePos = mc.player.getEyePosition(partialTick);
        Vec3 forward = new Vec3(camera.forwardVector());

        double blockRange = mc.player.blockInteractionRange();
        double entityRange = mc.player.entityInteractionRange();
        double maxRange = Math.max(blockRange, entityRange);
        Vec3 endPos = eyePos.add(forward.scale(maxRange));

        HitResult blockHit = mc.level.clip(
            new ClipContext(eyePos, endPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player)
        );

        AABB searchBox = new AABB(eyePos, endPos).inflate(1.0);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
            mc.player, eyePos, endPos, searchBox, EntitySelector.CAN_BE_PICKED, entityRange * entityRange
        );

        HitResult best = blockHit;
        if (entityHit != null) {
            double blockDist = blockHit.getType() != HitResult.Type.MISS
                ? blockHit.getLocation().distanceToSqr(eyePos) : Double.MAX_VALUE;
            if (entityHit.getLocation().distanceToSqr(eyePos) < blockDist) best = entityHit;
        }

        mc.hitResult = best;
        mc.crosshairPickEntity = best instanceof EntityHitResult ehr ? ehr.getEntity() : null;
    }
}
