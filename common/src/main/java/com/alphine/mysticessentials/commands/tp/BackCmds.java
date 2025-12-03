package com.alphine.mysticessentials.commands.tp;

import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.perm.Bypass;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.storage.PlayerDataStore;
import com.alphine.mysticessentials.teleport.CooldownManager;
import com.alphine.mysticessentials.teleport.WarmupManager;
import com.alphine.mysticessentials.util.MessagesUtil;
import com.alphine.mysticessentials.util.Teleports;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Map;

public class BackCmds {

    private final PlayerDataStore pdata;
    private final CooldownManager cd;
    private final WarmupManager warm;

    public BackCmds(PlayerDataStore pdata, CooldownManager cd, WarmupManager warm) {
        this.pdata = pdata;
        this.cd = cd;
        this.warm = warm;
    }

    private static boolean featureOn() {
        var c = MEConfig.INSTANCE;
        return c == null || c.features == null || c.features.enableHomesWarpsTP;
    }

    public void register(CommandDispatcher<CommandSourceStack> d) {
        // ---------------------------------------------------------------------
        // /back  - return to last stored "back" location
        // ---------------------------------------------------------------------
        d.register(Commands.literal("back")
                .requires(src -> Perms.has(src, PermNodes.BACK_USE, 0))
                .executes(ctx -> {
                    if (!featureOn()) {
                        ctx.getSource().sendFailure(MessagesUtil.msg("feature.disabled.teleport"));
                        return 0;
                    }

                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    var ol = pdata.consumeBack(p.getUUID()); // NOTE: still consumed immediately
                    if (ol.isEmpty()) {
                        p.displayClientMessage(MessagesUtil.msg("back.none"), false);
                        return 0;
                    }

                    var l = ol.get();

                    int warmSec = (MEConfig.INSTANCE != null) ? MEConfig.INSTANCE.getWarmup("back") : 0;
                    CommandSourceStack src = ctx.getSource();

                    Runnable tp = () -> {
                        long now = System.currentTimeMillis();
                        if (!Bypass.cooldown(src)
                                && cd.getDefaultSeconds("back") > 0
                                && !cd.checkAndStampDefault(p.getUUID(), "back", now)) {

                            long rem = cd.remaining(p.getUUID(), "back", now);
                            p.displayClientMessage(
                                    MessagesUtil.msg("cooldown.wait", Map.of("seconds", rem)), false
                            );
                            return;
                        }

                        ResourceLocation id = ResourceLocation.tryParse(l.dim);
                        if (id == null) {
                            p.displayClientMessage(MessagesUtil.msg("warp.bad_dimension", Map.of("dim", l.dim)), false);
                            return;
                        }

                        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
                        ServerLevel level = p.getServer().getLevel(key);
                        if (level == null) {
                            p.displayClientMessage(MessagesUtil.msg("warp.world_missing", Map.of("dim", l.dim)), false);
                            return;
                        }

                        Teleports.pushBackAndTeleport(p, level, l.x, l.y, l.z, l.yaw, l.pitch, pdata);
                        p.displayClientMessage(MessagesUtil.msg("back.returned"), false);
                    };

                    warm.startOrBypass(ctx.getSource().getServer(), p, warmSec, tp);
                    return 1;
                })
        );

        // ---------------------------------------------------------------------
        // /deathback  - return to last death location
        // ---------------------------------------------------------------------
        d.register(Commands.literal("deathback")
                .requires(src -> Perms.has(src, PermNodes.DEATHBACK_USE, 0))
                .executes(ctx -> {
                    if (!featureOn()) {
                        ctx.getSource().sendFailure(MessagesUtil.msg("feature.disabled.teleport"));
                        return 0;
                    }

                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    var od = pdata.getDeath(p.getUUID());
                    if (od.isEmpty()) {
                        p.displayClientMessage(MessagesUtil.msg("deathback.none"), false);
                        return 0;
                    }

                    var l = od.get();

                    int warmSec = (MEConfig.INSTANCE != null) ? MEConfig.INSTANCE.getWarmup("deathback") : 0;
                    CommandSourceStack src = ctx.getSource();

                    Runnable tp = () -> {
                        long now = System.currentTimeMillis();
                        if (!Bypass.cooldown(src)
                                && cd.getDefaultSeconds("deathback") > 0
                                && !cd.checkAndStampDefault(p.getUUID(), "deathback", now)) {

                            long rem = cd.remaining(p.getUUID(), "deathback", now);
                            p.displayClientMessage(
                                    MessagesUtil.msg("cooldown.wait", Map.of("seconds", rem)), false
                            );
                            return;
                        }

                        ResourceLocation id = ResourceLocation.tryParse(l.dim);
                        if (id == null) {
                            p.displayClientMessage(MessagesUtil.msg("warp.bad_dimension", Map.of("dim", l.dim)), false);
                            return;
                        }

                        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
                        ServerLevel level = p.getServer().getLevel(key);
                        if (level == null) {
                            p.displayClientMessage(MessagesUtil.msg("warp.world_missing", Map.of("dim", l.dim)), false);
                            return;
                        }

                        Teleports.pushBackAndTeleport(p, level, l.x, l.y, l.z, l.yaw, l.pitch, pdata);
                        p.displayClientMessage(MessagesUtil.msg("deathback.tp"), false);
                    };

                    warm.startOrBypass(ctx.getSource().getServer(), p, warmSec, tp);
                    return 1;
                })
        );
    }
}
