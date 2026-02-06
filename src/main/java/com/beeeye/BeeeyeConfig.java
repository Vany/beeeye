package com.beeeye;

import net.neoforged.neoforge.common.ModConfigSpec;

public class BeeeyeConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.DoubleValue EYE_DISTANCE;
    public static final ModConfigSpec.DoubleValue CONVERGENCE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Beeeye Stereo Rendering Configuration");
        builder.push("stereo");

        EYE_DISTANCE = builder
            .comment(
                "Distance between eyes in blocks (IPD). Each eye offset = +/- eyeDistance/2"
            )
            .defineInRange("eyeDistance", 0.25, 0.01, 1.0);

        CONVERGENCE = builder
            .comment(
                "Convergence distance in blocks. Objects at this distance have zero parallax.",
                "Closer objects appear in front of screen, farther objects behind.",
                "Lower values = stronger 3D effect, higher values = subtler effect."
            )
            .defineInRange("convergence", 5.0, 1.0, 50.0);

        builder.pop();

        SPEC = builder.build();
    }
}
