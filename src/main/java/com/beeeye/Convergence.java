package com.beeeye;

/**
 * Dynamic convergence calculator — smoothly tracks the distance to
 * the block/entity the player is looking at.
 *
 * Convergence distance determines the zero-parallax plane in off-axis
 * stereo projection. Objects at this distance appear at screen depth;
 * closer objects pop out, farther objects recede.
 */
public class Convergence {

    public static final float MIN = 1.0f;
    public static final float MAX = 50.0f;

    /** Tuned exponential decay: ~90% convergence within `speed` ticks. */
    private static final double DECAY_RATE = 2.2;

    private static float dynamic =
        (float) BeeeyeConfig.DEFAULT_CONVERGENCE;

    /** Active convergence — dynamic (raycast-driven) or static from config. */
    public static float get() {
        return isDynamic() ? dynamic : getStatic();
    }

    /** Static convergence from config (fallback for sky/no-hit). */
    public static float getStatic() {
        return BeeeyeConfig.get(
            BeeeyeConfig.CONVERGENCE,
            BeeeyeConfig.DEFAULT_CONVERGENCE
        ).floatValue();
    }

    /** Current dynamic convergence value (for status display). */
    public static float getDynamic() {
        return dynamic;
    }

    public static boolean isDynamic() {
        return BeeeyeConfig.get(BeeeyeConfig.DYNAMIC_CONVERGENCE, false);
    }

    /**
     * Smoothly update dynamic convergence toward target distance.
     * Speed config (1-40 ticks) → exponential lerp: 1 - exp(-DECAY_RATE / speed).
     * Speed=1 is near-instant, speed=4 is smooth default.
     */
    public static void update(float targetDistance) {
        float clamped = Math.clamp(targetDistance, MIN, MAX);
        int speed = BeeeyeConfig.get(
            BeeeyeConfig.CONVERGENCE_SPEED,
            BeeeyeConfig.DEFAULT_CONVERGENCE_SPEED
        );
        float smoothing = (float) (1.0 - Math.exp(-DECAY_RATE / speed));
        dynamic += (clamped - dynamic) * smoothing;
    }
}
