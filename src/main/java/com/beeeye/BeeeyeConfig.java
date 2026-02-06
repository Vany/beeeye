package com.beeeye;

import net.neoforged.neoforge.common.ModConfigSpec;

public class BeeeyeConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.DoubleValue EYE_DISTANCE;
    public static final ModConfigSpec.IntValue SCREEN_SHIFT;
    public static final ModConfigSpec.IntValue INTERFACE_SHIFT;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Beeeye Stereo Rendering Configuration");
        builder.push("stereo");

        EYE_DISTANCE = builder
                .comment("Distance between eyes in blocks. Each eye camera offset = +/- eyeDistance/2")
                .defineInRange("eyeDistance", 0.25, 0.01, 1.0);

        SCREEN_SHIFT = builder
                .comment("Horizontal shift of split line in pixels. Positive = right, negative = left")
                .defineInRange("screenShift", 0, -500, 500);

        INTERFACE_SHIFT = builder
                .comment("GUI placement shift in pixels. Positive = left GUI moves right, negative = right GUI moves left")
                .defineInRange("interfaceShift", 0, -500, 500);

        builder.pop();

        SPEC = builder.build();
    }
}
