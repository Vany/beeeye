package com.beeeye.mixin;

import com.beeeye.Beeeye;
import com.beeeye.StereoPerspective;
import com.beeeye.StereoState;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Off-axis stereo: shift projection matrix m20 to create asymmetric frustum.
 * Camera stays in place (no chunk cache issues). Each eye gets a different
 * frustum shift, producing stereo parallax.
 *
 * Hooks into Camera.extractRenderState() TAIL so the shift is written into
 * cameraState.projectionMatrix before renderLevel() copies it into the GPU.
 */
@Mixin(Camera.class)
public abstract class MixinProjectionMatrix {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void beeeye$applyOffAxisShift(
        CameraRenderState cameraState,
        float cameraEntityPartialTicks,
        CallbackInfo ci
    ) {
        if (!Beeeye.isStereoEnabled() || !StereoState.isRenderingEye()) return;

        float shift = StereoPerspective.projectionOffset(cameraState.projectionMatrix.m00());
        if (shift != 0) {
            cameraState.projectionMatrix.m20(cameraState.projectionMatrix.m20() + shift);
        }
    }
}
