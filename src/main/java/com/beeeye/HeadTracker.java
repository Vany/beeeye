package com.beeeye;

/**
 * Thread-safe head tracking state. Written by OSC listener thread,
 * read by render thread. Uses immutable {@link Quat} records for
 * atomic reference swaps — zero tearing between components.
 *
 * Dead zone anchored to last stable position — jitter is suppressed
 * at any head angle, not just calibration center.
 */
public class HeadTracker {

    /** Immutable quaternion — reference assignment is atomic in JVM. */
    public record Quat(float x, float y, float z, float w) {
        public static final Quat IDENTITY = new Quat(0, 0, 0, 1);

        public float lengthSq() {
            return x * x + y * y + z * z + w * w;
        }

        /** Conjugate divided by squared length (general inverse). */
        public Quat inverse() {
            float len2 = lengthSq();
            if (len2 < 1e-6f) return IDENTITY;
            float inv = 1.0f / len2;
            return new Quat(-x * inv, -y * inv, -z * inv, w * inv);
        }

        /** Hamilton product: this * other. */
        public Quat mul(Quat o) {
            return new Quat(
                w * o.x + x * o.w + y * o.z - z * o.y,
                w * o.y - x * o.z + y * o.w + z * o.x,
                w * o.z + x * o.y - y * o.x + z * o.w,
                w * o.w - x * o.x - y * o.y - z * o.z
            );
        }

        /** Intrinsic yaw (Y-axis rotation) in degrees. */
        public float toYaw() {
            return (float) Math.toDegrees(
                Math.atan2(2.0 * (w * y + x * z), 1.0 - 2.0 * (y * y + z * z))
            );
        }

        /** Intrinsic pitch (X-axis rotation) in degrees. */
        public float toPitch() {
            float sinP = 2.0f * (w * x - y * z);
            return (float) Math.toDegrees(
                Math.asin(Math.clamp(sinP, -1.0f, 1.0f))
            );
        }

        /** Dot product. */
        public float dot(Quat o) {
            return x * o.x + y * o.y + z * o.z + w * o.w;
        }

        /** Normalized lerp — fast slerp approximation, sufficient at high sample rates. */
        public Quat nlerp(Quat target, float t) {
            float d = this.dot(target);
            float tx = target.x,
                ty = target.y,
                tz = target.z,
                tw = target.w;
            if (d < 0) {
                tx = -tx;
                ty = -ty;
                tz = -tz;
                tw = -tw;
            }
            float rx = x + t * (tx - x),
                ry = y + t * (ty - y);
            float rz = z + t * (tz - z),
                rw = w + t * (tw - w);
            float len = (float) Math.sqrt(
                rx * rx + ry * ry + rz * rz + rw * rw
            );
            if (len < 1e-6f) return IDENTITY;
            float inv = 1.0f / len;
            return new Quat(rx * inv, ry * inv, rz * inv, rw * inv);
        }
    }

    /** Consider tracking lost after this silence duration. */
    private static final long TRACKING_TIMEOUT_MS = 500;

    /** Nlerp blend factor — balances responsiveness vs jitter at ~60Hz OSC rate. */
    private static final float NLERP_FACTOR = 0.4f;

    /** Milliseconds per Minecraft tick, used for settle time calculation. */
    private static final long MS_PER_TICK = 50L;

    private static float getDeadZone() {
        return BeeeyeConfig.get(
            BeeeyeConfig.HEAD_DEADZONE,
            BeeeyeConfig.DEFAULT_HEAD_DEADZONE
        ).floatValue();
    }

    // Single volatile references — atomic swap, no component tearing
    private static volatile Quat current = Quat.IDENTITY;
    private static volatile Quat neutralInverse = Quat.IDENTITY;
    private static volatile boolean calibrated;
    private static volatile long lastUpdateMs;

    // Anchor: the position where head last came to rest.
    // Dead zone is measured from anchor, not from calibration center.
    // Written/read only on render thread (getDelta), so no volatile needed.
    private static Quat anchor = Quat.IDENTITY;
    private static boolean moving = false;

    // Settle detection: head must stay within dead zone for convergenceSpeed × MS_PER_TICK.
    // settleOrigin = position when we first entered the dead zone candidate area.
    // settleStartMs = timestamp when settle candidate started.
    private static Quat settleOrigin = Quat.IDENTITY;
    private static long settleStartMs = 0;

    /** Push a complete quaternion from OSC listener, with nlerp smoothing. */
    public static void update(Quat q) {
        current = current.nlerp(q, NLERP_FACTOR);
        lastUpdateMs = System.currentTimeMillis();
    }

    /** Is head tracking data arriving and calibrated? */
    public static boolean isActive() {
        return (
            calibrated &&
            (System.currentTimeMillis() - lastUpdateMs) < TRACKING_TIMEOUT_MS
        );
    }

    /**
     * Get delta rotation relative to neutral, with two dead zones:
     * 1. Neutral dead zone — when head is near calibration center, output snaps to zero
     * 2. Anchor dead zone — at any other angle, jitter suppressed around last stable position
     */
    public static Quat getDelta() {
        Quat delta = current.mul(neutralInverse);
        float dz = getDeadZone();

        // Dead zone #1: neutral center — snap to zero when near calibration pose
        float neutralYaw = Math.abs(delta.toYaw());
        float neutralPitch = Math.abs(delta.toPitch());
        if (neutralYaw < dz && neutralPitch < dz) {
            anchor = current;
            moving = false;
            return Quat.IDENTITY;
        }

        // Dead zone #2: anchored — suppress jitter at any angle
        Quat anchorDelta = current.mul(anchor.inverse());
        float anchorYaw = Math.abs(anchorDelta.toYaw());
        float anchorPitch = Math.abs(anchorDelta.toPitch());

        long settleMs =
            BeeeyeConfig.get(
                BeeeyeConfig.CONVERGENCE_SPEED,
                BeeeyeConfig.DEFAULT_CONVERGENCE_SPEED
            ) *
            MS_PER_TICK;

        if (moving) {
            Quat settleDelta = current.mul(settleOrigin.inverse());
            float settleYaw = Math.abs(settleDelta.toYaw());
            float settlePitch = Math.abs(settleDelta.toPitch());

            if (settleYaw < dz && settlePitch < dz) {
                if (System.currentTimeMillis() - settleStartMs >= settleMs) {
                    moving = false;
                    anchor = settleOrigin;
                    return anchor.mul(neutralInverse);
                }
            } else {
                settleOrigin = current;
                settleStartMs = System.currentTimeMillis();
            }
            anchor = current;
            return delta;
        } else {
            if (anchorYaw >= dz || anchorPitch >= dz) {
                moving = true;
                anchor = current;
                settleOrigin = current;
                settleStartMs = System.currentTimeMillis();
                return delta;
            }
            return anchor.mul(neutralInverse);
        }
    }

    /** Capture current quaternion as neutral pose. */
    public static void calibrate() {
        Quat q = current;
        if (q.lengthSq() < 1e-3f) {
            Beeeye.LOGGER.warn(
                "[Beeeye] Cannot calibrate — no quaternion data yet"
            );
            return;
        }
        neutralInverse = q.inverse();
        anchor = q;
        settleOrigin = q;
        settleStartMs = System.currentTimeMillis();
        moving = false;
        calibrated = true;
        Beeeye.LOGGER.info(
            "[Beeeye] Head tracking calibrated: q=({}, {}, {}, {})",
            String.format("%.3f", q.x),
            String.format("%.3f", q.y),
            String.format("%.3f", q.z),
            String.format("%.3f", q.w)
        );
    }
}
