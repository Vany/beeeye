package com.beeeye;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-side command: /beeeye
 *
 * Usage:
 *   /beeeye                     - show current settings
 *   /beeeye toggle              - toggle stereo on/off
 *   /beeeye set eyedistance <v> - set IPD (0.01-1.0)
 *   /beeeye set convergence <v> - set convergence distance (1.0-50.0)
 *   /beeeye set dynamicconvergence <true|false>
 *   /beeeye set speed <ticks>   - convergence speed (1-40)
 *   /beeeye set oscport <port>  - OSC UDP port (1024-65535)
 *   /beeeye set deadzone <deg>  - head tracking dead zone (0-15)
 */
public class BeeeyeCommand {

    /** Numeric setting descriptor — drives validation, persistence, and feedback. */
    private record Setting(
        ModConfigSpec.ConfigValue<? extends Number> config,
        double min,
        double max,
        String unit,
        boolean isInt
    ) {}

    private static final Map<String, Setting> SETTINGS = new LinkedHashMap<>();

    static {
        SETTINGS.put(
            "eyedistance",
            new Setting(BeeeyeConfig.EYE_DISTANCE, 0.01, 1.0, "blocks", false)
        );
        SETTINGS.put(
            "convergence",
            new Setting(BeeeyeConfig.CONVERGENCE, 1.0, 50.0, "blocks", false)
        );
        SETTINGS.put(
            "speed",
            new Setting(BeeeyeConfig.CONVERGENCE_SPEED, 1, 40, "ticks", true)
        );
        SETTINGS.put(
            "oscport",
            new Setting(BeeeyeConfig.OSC_PORT, 1024, 65535, "", true)
        );
        SETTINGS.put(
            "deadzone",
            new Setting(BeeeyeConfig.HEAD_DEADZONE, 0.0, 15.0, "degrees", false)
        );
    }

    private static final SuggestionProvider<
        CommandSourceStack
    > SETTING_SUGGESTIONS = (context, builder) ->
        SharedSuggestionProvider.suggest(SETTINGS.keySet(), builder);

    public static void register(
        CommandDispatcher<CommandSourceStack> dispatcher
    ) {
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal(
            "beeeye"
        )
            .executes(BeeeyeCommand::showStatus)
            .then(Commands.literal("toggle").executes(BeeeyeCommand::toggle))
            .then(
                Commands.literal("set")
                    .then(
                        Commands.literal("dynamicconvergence").then(
                            Commands.argument(
                                "enabled",
                                BoolArgumentType.bool()
                            ).executes(BeeeyeCommand::setDynamicConvergence)
                        )
                    )
                    .then(
                        Commands.argument("setting", StringArgumentType.word())
                            .suggests(SETTING_SUGGESTIONS)
                            .then(
                                Commands.argument(
                                    "value",
                                    DoubleArgumentType.doubleArg()
                                ).executes(BeeeyeCommand::setSetting)
                            )
                    )
            );

        dispatcher.register(command);
    }

    private static int showStatus(CommandContext<CommandSourceStack> context) {
        var src = context.getSource();
        boolean enabled = Beeeye.isStereoEnabled();
        float ipd = StereoState.getIPD();
        float convergence = Convergence.get();
        boolean dynConv = Convergence.isDynamic();

        src.sendSuccess(
            () ->
                Component.literal(
                    "§6[Beeeye]§r Stereo: " + (enabled ? "§aON" : "§cOFF")
                ),
            false
        );
        src.sendSuccess(
            () ->
                Component.literal(
                    "§6[Beeeye]§r Eye distance: §e" + ipd + "§r blocks"
                ),
            false
        );
        src.sendSuccess(
            () ->
                Component.literal(
                    "§6[Beeeye]§r Convergence: §e" +
                        convergence +
                        "§r blocks" +
                        (dynConv
                            ? " §7(dynamic: §e" +
                              String.format("%.1f", Convergence.getDynamic()) +
                              "§7)"
                            : "")
                ),
            false
        );
        src.sendSuccess(
            () ->
                Component.literal(
                    "§6[Beeeye]§r Dynamic convergence: " +
                        (dynConv ? "§aON" : "§cOFF")
                ),
            false
        );

        src.sendSuccess(
            () ->
                Component.literal(
                    "§6[Beeeye]§r OSC port: §e" + BeeeyeConfig.oscPort()
                ),
            false
        );
        return 1;
    }

    private static int toggle(CommandContext<CommandSourceStack> context) {
        boolean newState = !Beeeye.isStereoEnabled();
        Beeeye.setStereoEnabled(newState);
        context
            .getSource()
            .sendSuccess(
                () ->
                    Component.literal(
                        "§6[Beeeye]§r Stereo: " + (newState ? "§aON" : "§cOFF")
                    ),
                false
            );
        return 1;
    }

    private static int setDynamicConvergence(
        CommandContext<CommandSourceStack> context
    ) {
        boolean enabled = BoolArgumentType.getBool(context, "enabled");
        BeeeyeConfig.DYNAMIC_CONVERGENCE.set(enabled);
        BeeeyeConfig.save();
        context
            .getSource()
            .sendSuccess(
                () ->
                    Component.literal(
                        "§6[Beeeye]§r Dynamic convergence: " +
                            (enabled ? "§aON" : "§cOFF")
                    ),
                false
            );
        return 1;
    }

    @SuppressWarnings("unchecked")
    private static int setSetting(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(
            context,
            "setting"
        ).toLowerCase();
        double value = DoubleArgumentType.getDouble(context, "value");

        Setting setting = SETTINGS.get(name);
        if (setting == null) {
            context
                .getSource()
                .sendFailure(
                    Component.literal(
                        "§c[Beeeye]§r Unknown setting: " +
                            name +
                            ". Use: " +
                            String.join(", ", SETTINGS.keySet()) +
                            ", dynamicconvergence"
                    )
                );
            return 0;
        }

        if (value < setting.min || value > setting.max) {
            context
                .getSource()
                .sendFailure(
                    Component.literal(
                        "§c[Beeeye]§r " +
                            name +
                            " must be between " +
                            setting.min +
                            " and " +
                            setting.max
                    )
                );
            return 0;
        }

        if (setting.isInt) {
            ((ModConfigSpec.IntValue) setting.config).set((int) value);
        } else {
            ((ModConfigSpec.DoubleValue) setting.config).set(value);
        }
        BeeeyeConfig.save();

        // Hot-reload OSC listener on port change — no restart required
        if (name.equals("oscport")) {
            Beeeye.getOscListener().restart((int) value);
        }

        String display = setting.isInt
            ? String.valueOf((int) value)
            : String.valueOf(value);
        String suffix = setting.unit.isEmpty() ? "" : " " + setting.unit;
        context
            .getSource()
            .sendSuccess(
                () ->
                    Component.literal(
                        "§6[Beeeye]§r " +
                            name +
                            " set to §e" +
                            display +
                            "§r" +
                            suffix
                    ),
                false
            );
        return 1;
    }
}
