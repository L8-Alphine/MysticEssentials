package com.alphine.mysticessentials.commands.misc;

import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.util.MessagesUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.Map;

public class GmCmd {

    public void register(CommandDispatcher<CommandSourceStack> d) {
        // Main /gm command
        CommandNode<CommandSourceStack> gmRoot = d.register(
                Commands.literal("gm")
                        .requires(src -> Perms.has(src, PermNodes.GAMEMODE_USE, 2))
                        .then(Commands.argument("mode", StringArgumentType.word())
                                // /gm <mode>
                                .executes(ctx -> {
                                    CommandSourceStack src = ctx.getSource();
                                    ServerPlayer self = src.getPlayerOrException();
                                    String arg = StringArgumentType.getString(ctx, "mode").toLowerCase();
                                    GameType type = parseMode(arg);

                                    if (type == null) {
                                        self.displayClientMessage(MessagesUtil.msg("gm.unknown"), false);
                                        return 0;
                                    }

                                    return setGamemode(src, self, type);
                                })
                                // /gm <mode> <player>
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .requires(src -> Perms.has(src, PermNodes.GAMEMODE_OTHERS, 2))
                                        .executes(ctx -> {
                                            CommandSourceStack src = ctx.getSource();
                                            String arg = StringArgumentType.getString(ctx, "mode").toLowerCase();
                                            GameType type = parseMode(arg);

                                            if (type == null) {
                                                ServerPlayer self = src.getPlayerOrException();
                                                self.displayClientMessage(MessagesUtil.msg("gm.unknown"), false);
                                                return 0;
                                            }

                                            String targetName = StringArgumentType.getString(ctx, "player");
                                            ServerPlayer target = src.getServer().getPlayerList().getPlayerByName(targetName);
                                            if (target == null) {
                                                src.sendFailure(Component.literal("§cPlayer §f" + targetName + " §cis not online."));
                                                return 0;
                                            }

                                            return setGamemode(src, target, type);
                                        })
                                )
                        )
        );

        // Shorthand aliases:
        // /gmc [player] -> creative
        d.register(Commands.literal("gmc")
                .requires(src -> Perms.has(src, PermNodes.GAMEMODE_USE, 2))
                .executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    ServerPlayer self = src.getPlayerOrException();
                    return setGamemode(src, self, GameType.CREATIVE);
                })
                .then(Commands.argument("player", StringArgumentType.word())
                        .requires(src -> Perms.has(src, PermNodes.GAMEMODE_OTHERS, 2))
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            String targetName = StringArgumentType.getString(ctx, "player");
                            ServerPlayer target = src.getServer().getPlayerList().getPlayerByName(targetName);
                            if (target == null) {
                                src.sendFailure(Component.literal("§cPlayer §f" + targetName + " §cis not online."));
                                return 0;
                            }
                            return setGamemode(src, target, GameType.CREATIVE);
                        })
                )
        );

        // /gms [player] -> survival
        d.register(Commands.literal("gms")
                .requires(src -> Perms.has(src, PermNodes.GAMEMODE_USE, 2))
                .executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    ServerPlayer self = src.getPlayerOrException();
                    return setGamemode(src, self, GameType.SURVIVAL);
                })
                .then(Commands.argument("player", StringArgumentType.word())
                        .requires(src -> Perms.has(src, PermNodes.GAMEMODE_OTHERS, 2))
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            String targetName = StringArgumentType.getString(ctx, "player");
                            ServerPlayer target = src.getServer().getPlayerList().getPlayerByName(targetName);
                            if (target == null) {
                                src.sendFailure(Component.literal("§cPlayer §f" + targetName + " §cis not online."));
                                return 0;
                            }
                            return setGamemode(src, target, GameType.SURVIVAL);
                        })
                )
        );

        // /gma [player] -> adventure
        d.register(Commands.literal("gma")
                .requires(src -> Perms.has(src, PermNodes.GAMEMODE_USE, 2))
                .executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    ServerPlayer self = src.getPlayerOrException();
                    return setGamemode(src, self, GameType.ADVENTURE);
                })
                .then(Commands.argument("player", StringArgumentType.word())
                        .requires(src -> Perms.has(src, PermNodes.GAMEMODE_OTHERS, 2))
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            String targetName = StringArgumentType.getString(ctx, "player");
                            ServerPlayer target = src.getServer().getPlayerList().getPlayerByName(targetName);
                            if (target == null) {
                                src.sendFailure(Component.literal("§cPlayer §f" + targetName + " §cis not online."));
                                return 0;
                            }
                            return setGamemode(src, target, GameType.ADVENTURE);
                        })
                )
        );

        // /gmsp [player] -> spectator
        d.register(Commands.literal("gmsp")
                .requires(src -> Perms.has(src, PermNodes.GAMEMODE_USE, 2))
                .executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    ServerPlayer self = src.getPlayerOrException();
                    return setGamemode(src, self, GameType.SPECTATOR);
                })
                .then(Commands.argument("player", StringArgumentType.word())
                        .requires(src -> Perms.has(src, PermNodes.GAMEMODE_OTHERS, 2))
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            String targetName = StringArgumentType.getString(ctx, "player");
                            ServerPlayer target = src.getServer().getPlayerList().getPlayerByName(targetName);
                            if (target == null) {
                                src.sendFailure(Component.literal("§cPlayer §f" + targetName + " §cis not online."));
                                return 0;
                            }
                            return setGamemode(src, target, GameType.SPECTATOR);
                        })
                )
        );
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static GameType parseMode(String arg) {
        return switch (arg) {
            case "0", "s", "survival"          -> GameType.SURVIVAL;
            case "1", "c", "creative"          -> GameType.CREATIVE;
            case "2", "a", "adventure"         -> GameType.ADVENTURE;
            case "3", "sp", "spec", "spectator" -> GameType.SPECTATOR;
            default -> null;
        };
    }

    /**
     * Centralized permission + message handling for setting gamemode.
     */
    private static int setGamemode(CommandSourceStack src, ServerPlayer target, GameType type) {
        // Base permission check
        if (!Perms.has(src, PermNodes.GAMEMODE_USE, 2)) {
            src.sendFailure(Component.literal("§cYou don't have permission to change gamemodes."));
            return 0;
        }

        // Per-mode permission
        String modeNode = switch (type) {
            case SURVIVAL -> PermNodes.GAMEMODE_SURVIVAL;
            case CREATIVE -> PermNodes.GAMEMODE_CREATIVE;
            case ADVENTURE -> PermNodes.GAMEMODE_ADVENTURE;
            case SPECTATOR -> PermNodes.GAMEMODE_SPECTATOR;
        };

        if (!Perms.has(src, modeNode, 2)) {
            src.sendFailure(Component.literal("§cYou don't have permission to use gamemode " + type.getName() + "."));
            return 0;
        }

        // Changing others?
        boolean self = (src.getEntity() instanceof ServerPlayer sp) && sp.getUUID().equals(target.getUUID());
        if (!self && !Perms.has(src, PermNodes.GAMEMODE_OTHERS, 2)) {
            src.sendFailure(Component.literal("§cYou don't have permission to change other players' gamemodes."));
            return 0;
        }

        // Apply gamemode
        target.setGameMode(type);

        // Message to target
        target.displayClientMessage(
                MessagesUtil.msg("gm.set", Map.of("mode", type.getName())),
                false
        );

        // If changing someone else, also notify the command sender
        if (!self) {
            src.sendSuccess(() ->
                            Component.literal("§aSet gamemode of §f" + target.getGameProfile().getName() +
                                    " §ato §f" + type.getName()),
                    false
            );
        }

        return 1;
    }
}
