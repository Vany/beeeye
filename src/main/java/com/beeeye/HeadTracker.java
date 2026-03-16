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

    // Written by OSC thread, read by render thread — volatile for atomic swap.
    private static volatile Quat current = Quat.IDENTITY;
    private static volatile Quat neutralInverse = Quat.IDENTITY;
    private static volatile boolean calibrated;
    private static volatile long lastUpdateMs;

    // Render-thread-only state (dead zone logic). Not volatile — single thread.
    // Anchor: the position where head last came to rest.
    // Dead zone is measured from anchor, not from calibration center.
    private static Quat anchor = Quat.IDENTITY;
    private static boolean moving = false;

    /** Anchor dead zone requires 100ms settle before locking. */
    private static final long SETTLE_MS = 100;
    private static long settleStartMs = 0;

    // Per-frame snapshot: computed once by snapshotDelta(), consumed by both
    // camera setup passes (LEFT + RIGHT). Dead zone state machine runs exactly once.
    // Render-thread-only — plain field, no volatile needed.
    private static Quat frameDelta = Quat.IDENTITY;

    /** Raw current quaternion from device. */
    public static Quat getCurrent() {
        return current;
    }

    /** Push a complete quaternion from OSC listener — raw passthrough, no smoothing. */
    public static void update(Quat q) {
        current = q;
        lastUpdateMs = System.currentTimeMillis();
    }

    /** Is head tracking data arriving and calibrated? */
    public static boolean isActive() {
        return (
            calibrated &&
            (System.currentTimeMillis() - lastUpdateMs) < TRACKING_TIMEOUT_MS
        );
    }

    private static float getDeadZone() {
        return BeeeyeConfig.headDeadzone();
    }

    /**
     * Snapshot delta for this frame. Call once per frame (after LEFT camera setup)
     * before reading getFrameDelta(). Runs the full dead zone state machine exactly once.
     */
    public static void snapshotDelta() {
        frameDelta = computeDelta();
    }

    /**
     * Cached per-frame delta — valid after snapshotDelta() is called.
     * Both LEFT and RIGHT camera passes read this without re-running dead zone logic.
     */
    public static Quat getFrameDelta() {
        return frameDelta;
    }

    /**
     * Compute delta rotation relative to neutral, with two dead zones:
     * 1. Neutral dead zone — when head is near calibration center, snap to zero
     * 2. Anchor dead zone — at any angle, suppress jitter around last stable position
     *
     * Called only via snapshotDelta() — not directly from render code.
     */
    private static Quat computeDelta() {
        Quat delta = current.mul(neutralInverse);
        float dz = getDeadZone();

        // Dead zone #1: neutral center — snap to zero when near calibration pose
        if (Math.abs(delta.toYaw()) < dz && Math.abs(delta.toPitch()) < dz) {
            anchor = current;
            moving = false;
            return Quat.IDENTITY;
        }

        // Dead zone #2: anchored — suppress jitter at any angle
        Quat anchorDelta = current.mul(anchor.inverse());
        float anchorYaw = Math.abs(anchorDelta.toYaw());
        float anchorPitch = Math.abs(anchorDelta.toPitch());

        if (moving) {
            if (anchorYaw < dz && anchorPitch < dz) {
                // Inside anchor dead zone — start or continue settle timer.
                // Do NOT update anchor here; hold it fixed so anchorDelta is
                // measured against a stable reference, not a chasing one.
                if (settleStartMs == 0) {
                    settleStartMs = System.currentTimeMillis();
                } else if (
                    System.currentTimeMillis() - settleStartMs >= SETTLE_MS
                ) {
                    // Settled for 100ms — lock anchor
                    moving = false;
                    settleStartMs = 0;
                    return anchor.mul(neutralInverse);
                }
            } else {
                // Outside dead zone — still moving. Reset settle timer and
                // advance anchor so dead zone tracks the current trajectory.
                settleStartMs = 0;
                anchor = current;
            }
            return delta;
        } else {
            // Stationary — check if moved beyond dead zone
            if (anchorYaw >= dz || anchorPitch >= dz) {
                moving = true;
                anchor = current;
                return delta;
            }
            return anchor.mul(neutralInverse);
        }
    }

    /**
     * Capture current quaternion as neutral pose.
     * Returns drift since last calibration (delta between old and new neutral),
     * or null on first calibration / no data.
     */
    public static Quat calibrate() {
        Quat q = current;
        if (q.lengthSq() < 1e-3f) {
            Beeeye.LOGGER.warn(
                "[Beeeye] Cannot calibrate — no quaternion data yet"
            );
            return null;
        }
        Quat prevNeutralInv = neutralInverse;
        boolean wasCalibrated = calibrated;

        neutralInverse = q.inverse();
        anchor = q;
        moving = false;
        settleStartMs = 0;
        calibrated = true;

        Quat drift = wasCalibrated ? q.mul(prevNeutralInv) : null;

        Beeeye.LOGGER.info(
            "[Beeeye] Head tracking calibrated: q=({}, {}, {}, {})",
            String.format("%.3f", q.x),
            String.format("%.3f", q.y),
            String.format("%.3f", q.z),
            String.format("%.3f", q.w)
        );
        return drift;
    }
}
