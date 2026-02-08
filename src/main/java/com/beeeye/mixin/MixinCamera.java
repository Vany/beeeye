package com.beeeye.mixin;

import com.beeeye.Beeeye;
import com.beeeye.HeadTracker;
import com.beeeye.StereoRenderer;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Camera offset for true stereo: move camera along local X axis per eye.
 * Combined with off-axis projection (MixinProjectionMatrix) for convergence.
 * Eye offset is small (~0.125 blocks) — should not cause chunk cache issues.
 */
@Mixin(Camera.class)
public abstract class MixinCamera {

    @Shadow
    protected abstract void move(
        float distanceOffset,
        float verticalOffset,
        float horizontalOffset
    );

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    private float xRot;

    @Shadow
    private float yRot;

    @Inject(method = "setup", at = @At("TAIL"))
    private void beeeye$applyEyeOffset(
        Level level,
        Entity entity,
        boolean detached,
        boolean thirdPersonReverse,
        float partialTick,
        CallbackInfo ci
    ) {
        // Head tracking: only in stereo mode, single snapshot — no tearing
        if (Beeeye.isStereoEnabled() && HeadTracker.isActive()) {
            HeadTracker.Quat delta = HeadTracker.getDelta();
            setRotation(
                yRot + delta.toYaw(),
                Math.clamp(xRot + delta.toPitch(), -90f, 90f)
            );
        }

        // Stereo eye offset
        if (
            !Beeeye.isStereoEnabled() || !StereoRenderer.isInStereoPass()
        ) return;

        float offset = StereoRenderer.getEyeOffset();
        if (offset != 0) {
            move(0, 0, offset); // (forward, up, right)
        }
    }
}
