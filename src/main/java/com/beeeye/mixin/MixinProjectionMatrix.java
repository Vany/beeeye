package com.beeeye.mixin;

import com.beeeye.Beeeye;
import com.beeeye.StereoPerspective;
import com.beeeye.StereoState;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Off-axis stereo: shift projection matrix m20 to create asymmetric frustum.
 * Camera stays in place (no chunk cache issues). Each eye gets a different
 * frustum shift, producing stereo parallax.
 */
@Mixin(GameRenderer.class)
public abstract class MixinProjectionMatrix {

    @Inject(method = "getProjectionMatrix", at = @At("RETURN"))
    private void beeeye$applyOffAxisShift(
        float fov,
        CallbackInfoReturnable<Matrix4f> cir
    ) {
        if (!Beeeye.isStereoEnabled() || !StereoState.isInStereoPass()) return;

        Matrix4f matrix = cir.getReturnValue();
        if (matrix == null) return;

        float shift = StereoPerspective.projectionOffset(matrix.m00());
        if (shift != 0) {
            matrix.m20(matrix.m20() + shift);
        }
    }
}
