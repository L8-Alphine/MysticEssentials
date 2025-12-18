package com.alphine.mysticessentials.commands.tp;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.storage.PlayerDataStore;
import com.alphine.mysticessentials.storage.WarpsStore;
import com.alphine.mysticessentials.teleport.TeleportExecutor;
import com.alphine.mysticessentials.util.MessagesUtil;
import com.alphine.mysticessentials.util.Teleports;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.Objects;

public class WarpCmd {
    private static final SuggestionProvider<CommandSourceStack> WARP_SUGGEST = (ctx, b) -> {
        var common = MysticEssentialsCommon.get();
        if (common != null && common.warps != null) {
            for (var n : common.warps.names()) b.suggest(n);
        }
        return b.buildFuture();
    };
    private final WarpsStore warps;
    private final TeleportExecutor exec;
    private final PlayerDataStore pdata;

    public WarpCmd(WarpsStore w, TeleportExecutor exec, PlayerDataStore pdata) {
        this.warps = w;
        this.exec = exec;
        this.pdata = pdata;
    }

    private static boolean featureOn() {
        var c = MEConfig.INSTANCE;
        return c == null || c.features == null || c.features.enableHomesWarpsTP;
    }

    public void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("warp")
                .requires(src -> Perms.has(src, PermNodes.WARP_USE, 0))
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(WARP_SUGGEST)
                        .executes(ctx -> {
                            if (!featureOn()) {
                                ctx.getSource().sendFailure(MessagesUtil.msg("feature.disabled.teleport"));
                                return 0;
                            }

                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String name = StringArgumentType.getString(ctx, "name");
                            var ow = warps.get(name);
                            if (ow.isEmpty()) {
                                p.displayClientMessage(MessagesUtil.msg("warp.unknown", Map.of("warp", name)), false);
                                return 0;
                            }
                            var w = ow.get();

                            exec.runTeleport(ctx.getSource().getServer(), p, ctx.getSource(), "warp", () -> {
                                ResourceLocation id = ResourceLocation.tryParse(w.dim);
                                if (id == null) {
                                    p.displayClientMessage(MessagesUtil.msg("warp.bad_dimension", Map.of("dim", w.dim)), false);
                                    return false;
                                }
                                ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
                                ServerLevel level = Objects.requireNonNull(p.getServer()).getLevel(key);
                                if (level == null) {
                                    p.displayClientMessage(MessagesUtil.msg("warp.world_missing", Map.of("dim", w.dim)), false);
                                    return false;
                                }
                                Teleports.pushBackAndTeleport(p, level, w.x, w.y, w.z, w.yaw, w.pitch, pdata);
                                return true;
                            });

                            return 1;
                        })
                )
        );

        d.register(Commands.literal("setwarp")
                .requires(src -> Perms.has(src, PermNodes.WARP_SET, 2))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String name = StringArgumentType.getString(ctx, "name");
                            var pos = p.blockPosition();
                            WarpsStore.Warp w = new WarpsStore.Warp();
                            w.name = name;
                            w.dim = p.serverLevel().dimension().location().toString();
                            w.x = pos.getX() + 0.5;
                            w.y = pos.getY();
                            w.z = pos.getZ() + 0.5;
                            w.yaw = p.getYRot();
                            w.pitch = p.getXRot();
                            warps.set(w);
                            p.displayClientMessage(MessagesUtil.msg("warp.set.saved", Map.of("warp", name)), false);
                            return 1;
                        })
                )
        );

        d.register(Commands.literal("delwarp")
                .requires(src -> Perms.has(src, PermNodes.WARP_DEL, 2))
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(WARP_SUGGEST)
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String name = StringArgumentType.getString(ctx, "name");
                            boolean ok = warps.del(name);
                            p.displayClientMessage(
                                    ok ? MessagesUtil.msg("warp.del.ok", Map.of("warp", name))
                                            : MessagesUtil.msg("warp.del.missing", Map.of("warp", name)),
                                    false
                            );
                            return ok ? 1 : 0;
                        })
                )
        );

        d.register(Commands.literal("warps")
                .requires(src -> Perms.has(src, PermNodes.WARP_LIST, 0))
                .executes(ctx -> {
                    if (!featureOn()) {
                        ctx.getSource().sendFailure(MessagesUtil.msg("feature.disabled.teleport"));
                        return 0;
                    }
                    var list = warps.names().stream().sorted().toList();
                    if (list.isEmpty()) {
                        ctx.getSource().sendSuccess(() -> MessagesUtil.msg("warps.none"), false);
                    } else {
                        String listStr = "&e" + String.join("&7, &e", list);
                        ctx.getSource().sendSuccess(() -> MessagesUtil.msg("warps.list", Map.of("list", listStr)), false);
                    }
                    return 1;
                })
        );
    }
}
