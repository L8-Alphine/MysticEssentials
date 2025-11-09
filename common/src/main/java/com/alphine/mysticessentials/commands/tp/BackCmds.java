package com.alphine.mysticessentials.commands.tp;

import com.mojang.brigadier.CommandDispatcher;
import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.storage.PlayerDataStore;
import com.alphine.mysticessentials.util.MessagesUtil;
import com.alphine.mysticessentials.util.Teleports;
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
    public BackCmds(PlayerDataStore pdata){ this.pdata = pdata; }

    private static boolean featureOn() {
        var c = MEConfig.INSTANCE;
        return c == null || c.features == null || c.features.enableHomesWarpsTP;
    }

    public void register(CommandDispatcher<CommandSourceStack> d){
        d.register(Commands.literal("back")
                .requires(src -> Perms.has(src, PermNodes.BACK_USE, 0))
                .executes(ctx -> {
                    if (!featureOn()) { ctx.getSource().sendFailure(MessagesUtil.msg("feature.disabled.teleport")); return 0; }

                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    var ol = pdata.consumeBack(p.getUUID()); // single-slot back
                    if (ol.isEmpty()) { p.displayClientMessage(MessagesUtil.msg("back.none"), false); return 0; }

                    var l = ol.get();
                    ResourceLocation id = ResourceLocation.tryParse(l.dim);
                    if (id == null) { p.displayClientMessage(MessagesUtil.msg("warp.bad_dimension", Map.of("dim", l.dim)), false); return 0; }

                    ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
                    ServerLevel level = p.getServer().getLevel(key);
                    if (level == null) { p.displayClientMessage(MessagesUtil.msg("warp.world_missing", Map.of("dim", l.dim)), false); return 0; }

                    Teleports.pushBackAndTeleport(p, level, l.x, l.y, l.z, l.yaw, l.pitch, pdata);
                    p.displayClientMessage(MessagesUtil.msg("back.returned"), false);
                    return 1;
                })
        );

        d.register(Commands.literal("deathback")
                .requires(src -> Perms.has(src, PermNodes.DEATHBACK_USE, 0))
                .executes(ctx -> {
                    if (!featureOn()) { ctx.getSource().sendFailure(MessagesUtil.msg("feature.disabled.teleport")); return 0; }

                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    var od = pdata.getDeath(p.getUUID());
                    if (od.isEmpty()) { p.displayClientMessage(MessagesUtil.msg("deathback.none"), false); return 0; }

                    var l = od.get();
                    ResourceLocation id = ResourceLocation.tryParse(l.dim);
                    if (id == null) { p.displayClientMessage(MessagesUtil.msg("warp.bad_dimension", Map.of("dim", l.dim)), false); return 0; }

                    ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
                    ServerLevel level = p.getServer().getLevel(key);
                    if (level == null) { p.displayClientMessage(MessagesUtil.msg("warp.world_missing", Map.of("dim", l.dim)), false); return 0; }

                    Teleports.pushBackAndTeleport(p, level, l.x, l.y, l.z, l.yaw, l.pitch, pdata);
                    p.displayClientMessage(MessagesUtil.msg("deathback.tp"), false);
                    return 1;
                })
        );
    }
}
