package com.beeeye;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Beeeye.MOD_ID)
public class Beeeye {

    public static final String MOD_ID = "beeeye";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile boolean stereoEnabled = false;
    private static final OscListener oscListener = new OscListener();

    public Beeeye(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Beeeye initializing...");

        modContainer.registerConfig(ModConfig.Type.CLIENT, BeeeyeConfig.SPEC);

        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::onRegisterKeyMappings);

        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onRegisterClientCommands);
        NeoForge.EVENT_BUS.addListener(this::onLoggingIn);
        NeoForge.EVENT_BUS.addListener(this::onLoggingOut);

        int port = BeeeyeConfig.get(
            BeeeyeConfig.OSC_PORT,
            BeeeyeConfig.DEFAULT_OSC_PORT
        );
        oscListener.start(port);
        Runtime.getRuntime().addShutdownHook(
            new Thread(oscListener::stop, "beeeye-osc-shutdown")
        );
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        LOGGER.info("Beeeye client setup complete");
    }

    private void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.registerCategory(BeeeyeKeyBindings.CATEGORY);
        event.register(BeeeyeKeyBindings.TOGGLE_STEREO);
        LOGGER.info("Beeeye keybindings registered");
    }

    private void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        BeeeyeCommand.register(event.getDispatcher());
        LOGGER.info("Beeeye commands registered");
    }

    private void onClientTick(ClientTickEvent.Post event) {
        while (BeeeyeKeyBindings.TOGGLE_STEREO.consumeClick()) {
            stereoEnabled = !stereoEnabled;
            LOGGER.info(
                "Stereo rendering: {}",
                stereoEnabled ? "ENABLED" : "DISABLED"
            );

            // Show on-screen message
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                String status = stereoEnabled ? "ON" : "OFF";
                mc.player.displayClientMessage(
                    Component.literal("Beeeye Stereo: " + status),
                    true
                );
            }

            if (stereoEnabled) {
                HeadTracker.calibrate();
            } else {
                StereoRenderer.cleanup();
            }
        }
    }

    private void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        // Force FBO recreation on world join — viewport may have changed
        StereoRenderer.cleanup();
        LOGGER.info(
            "Joined world, stereo FBOs will be recreated on next frame"
        );
    }

    private void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (stereoEnabled) {
            LOGGER.info("Leaving world, disabling stereo");
            stereoEnabled = false;
            StereoRenderer.cleanup();
        }
    }

    public static boolean isStereoEnabled() {
        return stereoEnabled;
    }

    public static void setStereoEnabled(boolean enabled) {
        stereoEnabled = enabled;
        if (!enabled) {
            StereoRenderer.cleanup();
        }
    }
}
