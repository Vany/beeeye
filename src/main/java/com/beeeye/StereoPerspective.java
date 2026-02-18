package com.beeeye;

import com.beeeye.StereoState.Eye;

/**
 * Stereo projection and eye-offset math — separated from the state machine.
 *
 * Off-axis projection: shift the projection matrix m20 element to create an
 * asymmetric frustum per eye. Camera position is unchanged (avoids chunk cache
 * invalidation). Objects at the convergence distance have zero parallax.
 *
 * Shift formula:
 *   m20 += sign * (IPD/2) * m00 / convergence
 *
 * Where m00 = 2*near/(right-left) encodes the horizontal field of view.
 * Multiplying by m00 converts from world-space IPD to clip-space shift.
 *
 * Eye offset: physical camera position displaced ±IPD/2 along local X.
 * Combined with the projection shift, objects at convergence distance
 * appear at the same screen pixel in both eyes (zero parallax).
 */
public class StereoPerspective {

    /**
     * Projection matrix m20 shift for the current eye.
     * Pass the matrix's m00 value (encodes horizontal FOV scale).
     * Returns 0 if not in an eye render pass.
     */
    public static float projectionOffset(float m00) {
        Eye eye = StereoState.getCurrentEye();
        if (eye == null) return 0f;
        float halfIPD = StereoState.getIPD() / 2.0f;
        return -(eye.sign * halfIPD * m00) / Convergence.get();
    }

    /**
     * Camera X offset along local eye axis: +IPD/2 (LEFT) or -IPD/2 (RIGHT).
     * Returns 0 if not in an eye render pass.
     */
    public static float eyeOffset() {
        Eye eye = StereoState.getCurrentEye();
        if (eye == null) return 0f;
        return eye.sign * (StereoState.getIPD() / 2.0f);
    }
}
