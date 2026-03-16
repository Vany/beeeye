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

    private static volatile boolean initialized = false;
    private static volatile boolean stereoEnabled = false;
    private static final OscListener oscListener = new OscListener();

    public static OscListener getOscListener() {
        return oscListener;
    }

    public Beeeye(IEventBus modEventBus, ModContainer modContainer) {
        if (initialized) {
            throw new IllegalStateException(
                "[Beeeye] Duplicate mod instance detected! " +
                "You have multiple Beeeye jars loaded simultaneously. " +
                "Remove all but one Beeeye jar from your mods folder."
            );
        }
        initialized = true;
        LOGGER.info("Beeeye initializing...");

        modContainer.registerConfig(ModConfig.Type.CLIENT, BeeeyeConfig.SPEC);

        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::onRegisterKeyMappings);

        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onRegisterClientCommands);
        NeoForge.EVENT_BUS.addListener(this::onLoggingIn);
        NeoForge.EVENT_BUS.addListener(this::onLoggingOut);

        oscListener.start(BeeeyeConfig.oscPort());
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

            Minecraft mc = Minecraft.getInstance();

            if (stereoEnabled) {
                HeadTracker.Quat drift = HeadTracker.calibrate();
                if (mc.player != null) {
                    HeadTracker.Quat q = HeadTracker.getCurrent();
                    String msg = String.format(
                        "Beeeye ON  q=(%.2f,%.2f,%.2f,%.2f)",
                        q.x(),
                        q.y(),
                        q.z(),
                        q.w()
                    );
                    if (drift != null) {
                        msg += String.format(
                            "  drift=(%.1f°, %.1f°)",
                            drift.toYaw(),
                            drift.toPitch()
                        );
                    }
                    mc.player.displayClientMessage(
                        Component.literal(msg),
                        true
                    );
                }
            } else {
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                        Component.literal("Beeeye OFF"),
                        true
                    );
                }
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
