package com.beeeye;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

public class BeeeyeKeyBindings {

    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(
        Identifier.fromNamespaceAndPath(Beeeye.MOD_ID, "keys")
    );

    public static final KeyMapping TOGGLE_STEREO = new KeyMapping(
        "key.beeeye.toggle",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_BACKSLASH, // \ key
        CATEGORY
    );
}
