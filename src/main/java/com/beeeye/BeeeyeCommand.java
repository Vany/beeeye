package com.beeeye;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.ClientCommandSourceStack;

import java.util.Arrays;
import java.util.List;

/**
 * Client-side command: /beeeye
 *
 * Usage:
 *   /beeeye                     - show current settings
 *   /beeeye toggle              - toggle stereo on/off
 *   /beeeye set eyedistance <value>   - set IPD (0.01-1.0)
 *   /beeeye set convergence <value>   - set convergence distance (1.0-50.0)
 */
public class BeeeyeCommand {

    private static final List<String> SETTINGS = Arrays.asList("eyedistance", "convergence");

    private static final SuggestionProvider<CommandSourceStack> SETTING_SUGGESTIONS =
        (context, builder) -> SharedSuggestionProvider.suggest(SETTINGS, builder);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal("beeeye")
            .executes(BeeeyeCommand::showStatus)
            .then(Commands.literal("toggle")
                .executes(BeeeyeCommand::toggle))
            .then(Commands.literal("set")
                .then(Commands.argument("setting", StringArgumentType.word())
                    .suggests(SETTING_SUGGESTIONS)
                    .then(Commands.argument("value", DoubleArgumentType.doubleArg())
                        .executes(BeeeyeCommand::setSetting))));

        dispatcher.register(command);
    }

    private static int showStatus(CommandContext<CommandSourceStack> context) {
        boolean enabled = Beeeye.isStereoEnabled();
        float ipd = StereoRenderer.getIPD();
        float convergence = StereoRenderer.getConvergence();

        context.getSource().sendSuccess(
            () -> Component.literal("§6[Beeeye]§r Stereo: " + (enabled ? "§aON" : "§cOFF")),
            false
        );
        context.getSource().sendSuccess(
            () -> Component.literal("§6[Beeeye]§r Eye distance: §e" + ipd + "§r blocks"),
            false
        );
        context.getSource().sendSuccess(
            () -> Component.literal("§6[Beeeye]§r Convergence: §e" + convergence + "§r blocks"),
            false
        );
        return 1;
    }

    private static int toggle(CommandContext<CommandSourceStack> context) {
        boolean newState = !Beeeye.isStereoEnabled();
        Beeeye.setStereoEnabled(newState);

        context.getSource().sendSuccess(
            () -> Component.literal("§6[Beeeye]§r Stereo: " + (newState ? "§aON" : "§cOFF")),
            false
        );
        return 1;
    }

    private static int setSetting(CommandContext<CommandSourceStack> context) {
        String setting = StringArgumentType.getString(context, "setting").toLowerCase();
        double value = DoubleArgumentType.getDouble(context, "value");

        switch (setting) {
            case "eyedistance" -> {
                if (value < 0.01 || value > 1.0) {
                    context.getSource().sendFailure(
                        Component.literal("§c[Beeeye]§r Eye distance must be between 0.01 and 1.0")
                    );
                    return 0;
                }
                BeeeyeConfig.EYE_DISTANCE.set(value);
                context.getSource().sendSuccess(
                    () -> Component.literal("§6[Beeeye]§r Eye distance set to §e" + value + "§r blocks"),
                    false
                );
            }
            case "convergence" -> {
                if (value < 1.0 || value > 50.0) {
                    context.getSource().sendFailure(
                        Component.literal("§c[Beeeye]§r Convergence must be between 1.0 and 50.0")
                    );
                    return 0;
                }
                BeeeyeConfig.CONVERGENCE.set(value);
                context.getSource().sendSuccess(
                    () -> Component.literal("§6[Beeeye]§r Convergence set to §e" + value + "§r blocks"),
                    false
                );
            }
            default -> {
                context.getSource().sendFailure(
                    Component.literal("§c[Beeeye]§r Unknown setting: " + setting + ". Use: eyedistance, convergence")
                );
                return 0;
            }
        }
        return 1;
    }
}
