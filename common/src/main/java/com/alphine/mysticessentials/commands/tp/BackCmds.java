package com.alphine.mysticessentials.commands.tp;

import com.mojang.brigadier.CommandDispatcher;
import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.storage.PlayerDataStore;
import com.alphine.mysticessentials.util.Teleports;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

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
                    if (!featureOn()) { ctx.getSource().sendFailure(Component.literal("§cTeleport features are disabled by config.")); return 0; }

                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    var ol = pdata.popBack(p.getUUID());
                    if (ol.isEmpty()) { p.displayClientMessage(Component.literal("§7No previous location."), false); return 0; }

                    var l = ol.get();
                    ResourceLocation id = ResourceLocation.tryParse(l.dim);
                    if (id == null) { p.displayClientMessage(Component.literal("§cBad dimension id: "+l.dim), false); return 0; }

                    ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
                    ServerLevel level = p.getServer().getLevel(key);
                    if (level == null) { p.displayClientMessage(Component.literal("§cWorld missing: "+l.dim), false); return 0; }

                    Teleports.pushBackAndTeleport(p, level, l.x, l.y, l.z, l.yaw, l.pitch, pdata);
                    p.displayClientMessage(Component.literal("§aReturned to previous location."), false);
                    return 1;
                })
        );

        d.register(Commands.literal("deathback")
                .requires(src -> Perms.has(src, PermNodes.DEATHBACK_USE, 0))
                .executes(ctx -> {
                    if (!featureOn()) { ctx.getSource().sendFailure(Component.literal("§cTeleport features are disabled by config.")); return 0; }

                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    var od = pdata.getDeath(p.getUUID());
                    if (od.isEmpty()) { p.displayClientMessage(Component.literal("§7No death location recorded."), false); return 0; }

                    var l = od.get();
                    ResourceLocation id = ResourceLocation.tryParse(l.dim);
                    if (id == null) { p.displayClientMessage(Component.literal("§cBad dimension id: "+l.dim), false); return 0; }

                    ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
                    ServerLevel level = p.getServer().getLevel(key);
                    if (level == null) { p.displayClientMessage(Component.literal("§cWorld missing: "+l.dim), false); return 0; }

                    Teleports.pushBackAndTeleport(p, level, l.x, l.y, l.z, l.yaw, l.pitch, pdata);
                    p.displayClientMessage(Component.literal("§aTeleported to last death location."), false);
                    return 1;
                })
        );
    }
}
