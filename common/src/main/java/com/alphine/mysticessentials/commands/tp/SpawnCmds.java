package com.alphine.mysticessentials.commands.tp;

import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.storage.SpawnStore;
import com.alphine.mysticessentials.teleport.TeleportExecutor;
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

public class SpawnCmds {
    private final SpawnStore spawn;
    private final TeleportExecutor exec;
    private final com.alphine.mysticessentials.storage.PlayerDataStore pdata;

    public SpawnCmds(SpawnStore s, TeleportExecutor exec,
                     com.alphine.mysticessentials.storage.PlayerDataStore pdata) {
        this.spawn = s;
        this.exec = exec;
        this.pdata = pdata;
    }

    private static boolean featureOn() {
        var c = MEConfig.INSTANCE;
        return c == null || c.features == null || c.features.enableHomesWarpsTP;
    }

    public void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("spawn")
                .requires(src -> Perms.has(src, PermNodes.SPAWN_USE, 0))
                .executes(ctx -> {
                    if (!featureOn()) {
                        ctx.getSource().sendFailure(MessagesUtil.msg("feature.disabled.teleport"));
                        return 0;
                    }

                    ServerPlayer p = ctx.getSource().getPlayerOrException();

                    exec.runTeleport(ctx.getSource().getServer(), p, ctx.getSource(), "spawn", () -> {
                        var s = spawn.get();
                        if (s == null) {
                            p.displayClientMessage(MessagesUtil.msg("spawn.not_set"), false);
                            return false;
                        }

                        ResourceLocation id = ResourceLocation.tryParse(s.dim);
                        if (id == null) {
                            p.displayClientMessage(MessagesUtil.msg("warp.bad_dimension", Map.of("dim", s.dim)), false);
                            return false;
                        }

                        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
                        ServerLevel level = p.getServer().getLevel(key);
                        if (level == null) {
                            p.displayClientMessage(MessagesUtil.msg("warp.world_missing", Map.of("dim", s.dim)), false);
                            return false;
                        }

                        Teleports.pushBackAndTeleport(p, level, s.x, s.y, s.z, s.yaw, s.pitch, pdata);
                        return true;
                    });

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
                    s.x = pos.getX() + 0.5;
                    s.y = pos.getY();
                    s.z = pos.getZ() + 0.5;
                    s.yaw = p.getYRot();
                    s.pitch = p.getXRot();
                    spawn.set(s);
                    p.displayClientMessage(MessagesUtil.msg("spawn.set.ok"), false);
                    return 1;
                })
        );
    }
}
