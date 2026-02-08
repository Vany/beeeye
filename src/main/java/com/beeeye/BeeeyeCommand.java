package com.beeeye;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.Arrays;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

/**
 * Client-side command: /beeeye
 *
 * Usage:
 *   /beeeye                     - show current settings
 *   /beeeye toggle              - toggle stereo on/off
 *   /beeeye set eyedistance <value>   - set IPD (0.01-1.0)
 *   /beeeye set convergence <value>   - set convergence distance (1.0-50.0)
 *   /beeeye set dynamicconvergence <true|false> - toggle dynamic convergence
 *   /beeeye set speed <ticks>         - convergence speed in ticks (1-40)
 */
public class BeeeyeCommand {

    private static final List<String> SETTINGS = Arrays.asList(
        "eyedistance",
        "convergence",
        "speed",
        "oscport",
        "deadzone"
    );

    private static final SuggestionProvider<
        CommandSourceStack
    > SETTING_SUGGESTIONS = (context, builder) ->
        SharedSuggestionProvider.suggest(SETTINGS, builder);

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
        boolean enabled = Beeeye.isStereoEnabled();
        float ipd = StereoRenderer.getIPD();
        float convergence = StereoRenderer.getConvergence();

        context
            .getSource()
            .sendSuccess(
                () ->
                    Component.literal(
                        "§6[Beeeye]§r Stereo: " + (enabled ? "§aON" : "§cOFF")
                    ),
                false
            );
        context
            .getSource()
            .sendSuccess(
                () ->
                    Component.literal(
                        "§6[Beeeye]§r Eye distance: §e" + ipd + "§r blocks"
                    ),
                false
            );
        boolean dynConv = StereoRenderer.isDynamicConvergenceEnabled();
        context
            .getSource()
            .sendSuccess(
                () ->
                    Component.literal(
                        "§6[Beeeye]§r Convergence: §e" +
                            convergence +
                            "§r blocks" +
                            (dynConv
                                ? " §7(dynamic: §e" +
                                  String.format(
                                      "%.1f",
                                      StereoRenderer.getDynamicConvergence()
                                  ) +
                                  "§7)"
                                : "")
                    ),
                false
            );
        context
            .getSource()
            .sendSuccess(
                () ->
                    Component.literal(
                        "§6[Beeeye]§r Dynamic convergence: " +
                            (dynConv ? "§aON" : "§cOFF")
                    ),
                false
            );
        int oscPort = BeeeyeConfig.get(
            BeeeyeConfig.OSC_PORT,
            BeeeyeConfig.DEFAULT_OSC_PORT
        );
        context
            .getSource()
            .sendSuccess(
                () -> Component.literal("§6[Beeeye]§r OSC port: §e" + oscPort),
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

    private static int setSetting(CommandContext<CommandSourceStack> context) {
        String setting = StringArgumentType.getString(
            context,
            "setting"
        ).toLowerCase();
        double value = DoubleArgumentType.getDouble(context, "value");

        switch (setting) {
            case "eyedistance" -> {
                if (value < 0.01 || value > 1.0) {
                    context
                        .getSource()
                        .sendFailure(
                            Component.literal(
                                "§c[Beeeye]§r Eye distance must be between 0.01 and 1.0"
                            )
                        );
                    return 0;
                }
                BeeeyeConfig.EYE_DISTANCE.set(value);
                BeeeyeConfig.save();
                context
                    .getSource()
                    .sendSuccess(
                        () ->
                            Component.literal(
                                "§6[Beeeye]§r Eye distance set to §e" +
                                    value +
                                    "§r blocks"
                            ),
                        false
                    );
            }
            case "convergence" -> {
                if (value < 1.0 || value > 50.0) {
                    context
                        .getSource()
                        .sendFailure(
                            Component.literal(
                                "§c[Beeeye]§r Convergence must be between 1.0 and 50.0"
                            )
                        );
                    return 0;
                }
                BeeeyeConfig.CONVERGENCE.set(value);
                BeeeyeConfig.save();
                context
                    .getSource()
                    .sendSuccess(
                        () ->
                            Component.literal(
                                "§6[Beeeye]§r Convergence set to §e" +
                                    value +
                                    "§r blocks"
                            ),
                        false
                    );
            }
            case "speed" -> {
                int ticks = (int) value;
                if (ticks < 1 || ticks > 40) {
                    context
                        .getSource()
                        .sendFailure(
                            Component.literal(
                                "§c[Beeeye]§r Speed must be between 1 and 40 ticks"
                            )
                        );
                    return 0;
                }
                BeeeyeConfig.CONVERGENCE_SPEED.set(ticks);
                BeeeyeConfig.save();
                context
                    .getSource()
                    .sendSuccess(
                        () ->
                            Component.literal(
                                "§6[Beeeye]§r Convergence speed set to §e" +
                                    ticks +
                                    "§r ticks"
                            ),
                        false
                    );
            }
            case "oscport" -> {
                int port = (int) value;
                if (port < 1024 || port > 65535) {
                    context
                        .getSource()
                        .sendFailure(
                            Component.literal(
                                "§c[Beeeye]§r OSC port must be between 1024 and 65535"
                            )
                        );
                    return 0;
                }
                BeeeyeConfig.OSC_PORT.set(port);
                BeeeyeConfig.save();
                context
                    .getSource()
                    .sendSuccess(
                        () ->
                            Component.literal(
                                "§6[Beeeye]§r OSC port set to §e" + port
                            ),
                        false
                    );
            }
            case "deadzone" -> {
                if (value < 0.0 || value > 15.0) {
                    context
                        .getSource()
                        .sendFailure(
                            Component.literal(
                                "§c[Beeeye]§r Dead zone must be between 0.0 and 15.0 degrees"
                            )
                        );
                    return 0;
                }
                BeeeyeConfig.HEAD_DEADZONE.set(value);
                BeeeyeConfig.save();
                context
                    .getSource()
                    .sendSuccess(
                        () ->
                            Component.literal(
                                "§6[Beeeye]§r Head dead zone set to §e" +
                                    value +
                                    "§r degrees"
                            ),
                        false
                    );
            }
            default -> {
                context
                    .getSource()
                    .sendFailure(
                        Component.literal(
                            "§c[Beeeye]§r Unknown setting: " +
                                setting +
                                ". Use: eyedistance, convergence, speed, dynamicconvergence"
                        )
                    );
                return 0;
            }
        }
        return 1;
    }
}
