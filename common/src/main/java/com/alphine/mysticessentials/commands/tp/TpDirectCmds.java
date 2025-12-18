package com.alphine.mysticessentials.commands.tp;

import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.storage.PlayerDataStore;
import com.alphine.mysticessentials.teleport.TeleportExecutor;
import com.alphine.mysticessentials.util.MessagesUtil;
import com.alphine.mysticessentials.util.Teleports;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

public class TpDirectCmds {
    private final TeleportExecutor exec;
    private final PlayerDataStore pdata;

    public TpDirectCmds(TeleportExecutor exec, PlayerDataStore pdata) {
        this.exec = exec;
        this.pdata = pdata;
    }

    private static boolean featureOn() {
        var c = MEConfig.INSTANCE;
        return c == null || c.features == null || c.features.enableHomesWarpsTP;
    }

    private static void removeRootLiteral(CommandDispatcher<CommandSourceStack> d, String name) {
        var root = d.getRoot();
        CommandNode<CommandSourceStack> node = root.getChild(name);
        if (node == null) return;

        try {
            var f = CommandNode.class.getDeclaredField("children");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (java.util.Map<String, CommandNode<CommandSourceStack>>) f.get(root);
            map.remove(name);
        } catch (Throwable ignored) {}
    }

    public void register(CommandDispatcher<CommandSourceStack> d) {
        removeRootLiteral(d, "tp");

        // /tp <player>
        d.register(Commands.literal("tp")
                .requires(src -> Perms.has(src, PermNodes.TP_USE, 2))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            if (!featureOn()) {
                                ctx.getSource().sendFailure(MessagesUtil.msg("feature.disabled.teleport"));
                                return 0;
                            }

                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String name = StringArgumentType.getString(ctx, "player");
                            ServerPlayer target = p.getServer().getPlayerList().getPlayerByName(name);
                            if (target == null) {
                                p.displayClientMessage(MessagesUtil.msg("tp.player_not_found"), false);
                                return 0;
                            }
                            if (p.getUUID().equals(target.getUUID())) {
                                p.displayClientMessage(MessagesUtil.msg("tp.already_here"), false);
                                return 0;
                            }

                            exec.runTeleport(ctx.getSource().getServer(), p, ctx.getSource(), "tp", () -> {
                                Teleports.pushBackAndTeleport(
                                        p, target.serverLevel(),
                                        target.getX(), target.getY(), target.getZ(),
                                        target.getYRot(), target.getXRot(), pdata
                                );
                                return true;
                            });

                            return 1;
                        })
                )
        );

        // /tphere <player> (TARGET moves)
        d.register(Commands.literal("tphere")
                .requires(src -> Perms.has(src, PermNodes.TPHERE_USE, 2))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            if (!featureOn()) {
                                ctx.getSource().sendFailure(MessagesUtil.msg("feature.disabled.teleport"));
                                return 0;
                            }

                            ServerPlayer issuer = ctx.getSource().getPlayerOrException();
                            String name = StringArgumentType.getString(ctx, "player");
                            ServerPlayer target = issuer.getServer().getPlayerList().getPlayerByName(name);
                            if (target == null) {
                                issuer.displayClientMessage(MessagesUtil.msg("tp.player_not_found"), false);
                                return 0;
                            }
                            if (issuer.getUUID().equals(target.getUUID())) {
                                issuer.displayClientMessage(MessagesUtil.msg("tphere.self"), false);
                                return 0;
                            }

                            exec.runTeleport(ctx.getSource().getServer(), target, ctx.getSource(), "tphere", () -> {
                                Teleports.pushBack(target, pdata);
                                target.teleportTo(
                                        issuer.serverLevel(),
                                        issuer.getX(), issuer.getY(), issuer.getZ(),
                                        issuer.getYRot(), issuer.getXRot()
                                );
                                return true;
                            });

                            return 1;
                        })
                )
        );

        // /tpo <offlineName> (issuer moves)
        d.register(Commands.literal("tpo")
                .requires(src -> Perms.has(src, PermNodes.TPO_USE, 2))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            if (!featureOn()) {
                                ctx.getSource().sendFailure(MessagesUtil.msg("feature.disabled.teleport"));
                                return 0;
                            }

                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String name = StringArgumentType.getString(ctx, "name");

                            var profileOpt = p.getServer().getProfileCache().get(name);
                            if (profileOpt.isEmpty()) {
                                p.displayClientMessage(MessagesUtil.msg("tpo.profile_missing"), false);
                                return 0;
                            }
                            var profile = profileOpt.get();

                            var ol = pdata.getLast(profile.getId());
                            if (ol.isEmpty()) {
                                p.displayClientMessage(MessagesUtil.msg("tpo.no_last_location"), false);
                                return 0;
                            }
                            var l = ol.get();

                            exec.runTeleport(ctx.getSource().getServer(), p, ctx.getSource(), "tpo", () -> {
                                var id = net.minecraft.resources.ResourceLocation.tryParse(l.dim);
                                if (id == null) {
                                    p.displayClientMessage(MessagesUtil.msg("warp.bad_dimension", Map.of("dim", l.dim)), false);
                                    return false;
                                }
                                var key = net.minecraft.resources.ResourceKey.create(
                                        net.minecraft.core.registries.Registries.DIMENSION, id
                                );
                                var level = p.getServer().getLevel(key);
                                if (level == null) {
                                    p.displayClientMessage(MessagesUtil.msg("warp.world_missing", Map.of("dim", l.dim)), false);
                                    return false;
                                }

                                Teleports.pushBackAndTeleport(p, level, l.x, l.y, l.z, l.yaw, l.pitch, pdata);
                                p.displayClientMessage(MessagesUtil.msg("tpo.teleported_to_last", Map.of("name", name)), false);
                                return true;
                            });

                            return 1;
                        })
                )
        );
    }
}
