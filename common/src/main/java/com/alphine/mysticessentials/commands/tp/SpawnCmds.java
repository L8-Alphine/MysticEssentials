package com.alphine.mysticessentials.commands.tp;

import com.mojang.brigadier.CommandDispatcher;
import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.perm.Bypass;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.storage.SpawnStore;
import com.alphine.mysticessentials.teleport.CooldownManager;
import com.alphine.mysticessentials.teleport.WarmupManager;
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

public class SpawnCmds {
    private final SpawnStore spawn; private final CooldownManager cd; private final WarmupManager warm; private final com.alphine.mysticessentials.storage.PlayerDataStore pdata;
    public SpawnCmds(SpawnStore s, CooldownManager cd, WarmupManager warm, com.alphine.mysticessentials.storage.PlayerDataStore pdata){
        this.spawn=s; this.cd=cd; this.warm=warm; this.pdata=pdata;
    }

    private static boolean featureOn(){
        var c = MEConfig.INSTANCE; return c == null || c.features == null || c.features.enableHomesWarpsTP;
    }

    public void register(CommandDispatcher<CommandSourceStack> d){
        d.register(Commands.literal("spawn")
                .requires(src -> Perms.has(src, PermNodes.SPAWN_USE, 0))
                .executes(ctx -> {
                    if (!featureOn()) { ctx.getSource().sendFailure(Component.literal("§cTeleport features are disabled by config.")); return 0; }

                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    var s = spawn.get(); if (s == null) { p.displayClientMessage(Component.literal("§cSpawn is not set."), false); return 0; }

                    long now = System.currentTimeMillis();
                    boolean bypassCd = Bypass.cooldown(ctx.getSource());
                    if (!bypassCd && cd.getDefaultSeconds("spawn") > 0 && !cd.checkAndStampDefault(p.getUUID(), "spawn", now)) {
                        long rem = cd.remaining(p.getUUID(), "spawn", now);
                        p.displayClientMessage(Component.literal("§cCooldown: §e" + rem + "s"), false); return 0;
                    }

                    int warmSec = (MEConfig.INSTANCE != null) ? MEConfig.INSTANCE.getWarmup("spawn") : 0;
                    Runnable tp = () -> {
                        ResourceLocation id = ResourceLocation.tryParse(s.dim);
                        if (id == null) { p.displayClientMessage(Component.literal("§cBad dimension id: " + s.dim), false); return; }
                        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
                        ServerLevel level = p.getServer().getLevel(key);
                        if (level == null) { p.displayClientMessage(Component.literal("§cWorld missing: " + s.dim), false); return; }
                        Teleports.pushBackAndTeleport(p, level, s.x, s.y, s.z, s.yaw, s.pitch, pdata);
                    };

                    if (Bypass.warmup(ctx.getSource())) tp.run();
                    else warm.startOrBypass(ctx.getSource().getServer(), p, warmSec, tp);
                    return 1;
                })
        );

        d.register(Commands.literal("setspawn")
                .requires(src -> Perms.has(src, PermNodes.SPAWN_SET, 2))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    var pos = p.blockPosition();
                    SpawnStore.Point s = new SpawnStore.Point();
                    s.dim = p.serverLevel().dimension().location().toString();
                    s.x = pos.getX() + 0.5; s.y = pos.getY(); s.z = pos.getZ() + 0.5;
                    s.yaw = p.getYRot(); s.pitch = p.getXRot();
                    spawn.set(s);
                    p.displayClientMessage(Component.literal("§aSpawn set."), false);
                    return 1;
                })
        );
    }
}
