package com.beeeye;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;

public class BeeeyeConfig {

    // Default values — single source of truth for config and fallbacks
    public static final double DEFAULT_EYE_DISTANCE = 0.25;
    public static final double DEFAULT_CONVERGENCE = 5.0;
    public static final int DEFAULT_CONVERGENCE_SPEED = 4;
    public static final int DEFAULT_OSC_PORT = 8001;
    public static final double DEFAULT_HEAD_DEADZONE = 2.0;

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.DoubleValue EYE_DISTANCE;
    public static final ModConfigSpec.DoubleValue CONVERGENCE;
    public static final ModConfigSpec.BooleanValue DYNAMIC_CONVERGENCE;
    public static final ModConfigSpec.IntValue CONVERGENCE_SPEED;
    public static final ModConfigSpec.IntValue OSC_PORT;
    public static final ModConfigSpec.DoubleValue HEAD_DEADZONE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Beeeye Stereo Rendering Configuration");
        builder.push("stereo");

        EYE_DISTANCE = builder
            .comment(
                "Distance between eyes in blocks (IPD). Each eye offset = +/- eyeDistance/2"
            )
            .defineInRange("eyeDistance", DEFAULT_EYE_DISTANCE, 0.01, 1.0);

        CONVERGENCE = builder
            .comment(
                "Convergence distance in blocks. Objects at this distance have zero parallax.",
                "Closer objects appear in front of screen, farther objects behind.",
                "Lower values = stronger 3D effect, higher values = subtler effect.",
                "Used as fallback when dynamic convergence has no target (looking at sky)."
            )
            .defineInRange("convergence", DEFAULT_CONVERGENCE, 1.0, 50.0);

        DYNAMIC_CONVERGENCE = builder
            .comment(
                "Automatically adjust convergence to the block/entity you're looking at.",
                "Eyes converge on the crosshair target for natural depth perception."
            )
            .define("dynamicConvergence", true);

        CONVERGENCE_SPEED = builder
            .comment(
                "Time in minecraft ticks to converge to new target distance.",
                "1 = nearly instant, 4 = smooth default, 40 = very slow drift."
            )
            .defineInRange(
                "convergenceSpeed",
                DEFAULT_CONVERGENCE_SPEED,
                1,
                40
            );

        OSC_PORT = builder
            .comment(
                "UDP port to listen for OSC face-tracking data.",
                "Configure the OSC source app to stream to this port."
            )
            .defineInRange("oscPort", DEFAULT_OSC_PORT, 1024, 65535);

        HEAD_DEADZONE = builder
            .comment(
                "Head tracking dead zone in degrees.",
                "Rotation below this threshold is ignored to prevent micro-jitter.",
                "0 = no dead zone, 3 = default, higher = more stable but less responsive."
            )
            .defineInRange("headDeadzone", DEFAULT_HEAD_DEADZONE, 0.0, 15.0);

        builder.pop();

        SPEC = builder.build();
    }

    /** Read config value safely; returns fallback if config not yet loaded. */
    public static <T> T get(ConfigValue<T> value, T fallback) {
        try {
            return value.get();
        } catch (Exception e) {
            return fallback;
        }
    }

    // =========================================================================
    // Typed accessors — single source of truth, no scattered fallback literals
    // =========================================================================

    public static float eyeDistance() {
        return get(EYE_DISTANCE, DEFAULT_EYE_DISTANCE).floatValue();
    }

    public static float convergence() {
        return get(CONVERGENCE, DEFAULT_CONVERGENCE).floatValue();
    }

    public static boolean dynamicConvergence() {
        return get(DYNAMIC_CONVERGENCE, false);
    }

    public static int convergenceSpeed() {
        return get(CONVERGENCE_SPEED, DEFAULT_CONVERGENCE_SPEED);
    }

    public static int oscPort() {
        return get(OSC_PORT, DEFAULT_OSC_PORT);
    }

    public static float headDeadzone() {
        return get(HEAD_DEADZONE, DEFAULT_HEAD_DEADZONE).floatValue();
    }

    /** Persist current config values to disk. */
    public static void save() {
        SPEC.save();
    }
}
