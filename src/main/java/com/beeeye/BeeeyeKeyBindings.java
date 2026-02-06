package com.beeeye;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

public class BeeeyeKeyBindings {

    public static final KeyMapping TOGGLE_STEREO = new KeyMapping(
        "key.beeeye.toggle",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_BACKSLASH, // \ key
        KeyMapping.Category.MISC
    );
}
