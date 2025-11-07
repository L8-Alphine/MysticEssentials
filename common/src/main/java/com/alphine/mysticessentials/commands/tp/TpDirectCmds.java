package com.alphine.mysticessentials.commands.tp;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.perm.Bypass;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.storage.PlayerDataStore;
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

public class TpDirectCmds {
    private final CooldownManager cd; private final WarmupManager warm; private final PlayerDataStore pdata;
    public TpDirectCmds(CooldownManager cd, WarmupManager warm, PlayerDataStore pdata){ this.cd=cd; this.warm=warm; this.pdata=pdata; }

    private static boolean featureOn(){
        var c = MEConfig.INSTANCE; return c == null || c.features == null || c.features.enableHomesWarpsTP;
    }

    public void register(CommandDispatcher<CommandSourceStack> d){
        // /tp <player>
        d.register(Commands.literal("tp")
                .requires(src -> Perms.has(src, PermNodes.TP_USE, 2))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            if (!featureOn()) { ctx.getSource().sendFailure(Component.literal("§cTeleport features are disabled by config.")); return 0; }

                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String name = StringArgumentType.getString(ctx,"player");
                            ServerPlayer target = p.getServer().getPlayerList().getPlayerByName(name);
                            if (target == null) { p.displayClientMessage(Component.literal("§cPlayer not found."), false); return 0; }
                            if (p.getUUID().equals(target.getUUID())) { p.displayClientMessage(Component.literal("§cYou are already at your own location."), false); return 0; }

                            long now = System.currentTimeMillis();
                            if (!Bypass.cooldown(ctx.getSource()) && cd.getDefaultSeconds("tp") > 0 && !cd.checkAndStampDefault(p.getUUID(),"tp",now)){
                                long rem = cd.remaining(p.getUUID(),"tp",now);
                                p.displayClientMessage(Component.literal("§cCooldown: §e"+rem+"s"), false); return 0;
                            }

                            int warmSec = (MEConfig.INSTANCE != null) ? MEConfig.INSTANCE.getWarmup("tp") : 0;
                            Runnable tp = () -> Teleports.pushBackAndTeleport(p, target.serverLevel(), target.getX(), target.getY(), target.getZ(), target.getYRot(), target.getXRot(), pdata);
                            if (Bypass.warmup(ctx.getSource())) tp.run();
                            else warm.startOrBypass(ctx.getSource().getServer(), p, warmSec, tp);
                            return 1;
                        })
                )
        );

        // /tphere <player>
        d.register(Commands.literal("tphere")
                .requires(src -> Perms.has(src, PermNodes.TPHERE_USE, 2))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            if (!featureOn()) { ctx.getSource().sendFailure(Component.literal("§cTeleport features are disabled by config.")); return 0; }

                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String name = StringArgumentType.getString(ctx,"player");
                            ServerPlayer target = p.getServer().getPlayerList().getPlayerByName(name);
                            if (target == null) { p.displayClientMessage(Component.literal("§cPlayer not found."), false); return 0; }
                            if (p.getUUID().equals(target.getUUID())) { p.displayClientMessage(Component.literal("§cThat's you."), false); return 0; }

                            Teleports.pushBack(target, pdata);
                            target.teleportTo(p.serverLevel(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
                            return 1;
                        })
                )
        );

        // /tpo <offlineName>
        d.register(Commands.literal("tpo")
                .requires(src -> Perms.has(src, PermNodes.TPO_USE, 2))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            if (!featureOn()) { ctx.getSource().sendFailure(Component.literal("§cTeleport features are disabled by config.")); return 0; }

                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String name = StringArgumentType.getString(ctx,"name");
                            var profileOpt = p.getServer().getProfileCache().get(name);
                            if (profileOpt.isEmpty()) { p.displayClientMessage(Component.literal("§cNo such player profile."), false); return 0; }
                            var profile = profileOpt.get();

                            var ol = pdata.getLast(profile.getId());
                            if (ol.isEmpty()) { p.displayClientMessage(Component.literal("§cNo last known location for that player."), false); return 0; }
                            var l = ol.get();

                            ResourceLocation id = ResourceLocation.tryParse(l.dim);
                            if (id == null) { p.displayClientMessage(Component.literal("§cBad dimension id: " + l.dim), false); return 0; }
                            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
                            ServerLevel level = p.getServer().getLevel(key);
                            if (level == null) { p.displayClientMessage(Component.literal("§cWorld missing: " + l.dim), false); return 0; }

                            Teleports.pushBackAndTeleport(p, level, l.x, l.y, l.z, l.yaw, l.pitch, pdata);
                            p.displayClientMessage(Component.literal("§aTeleported to §e" + name + "§a's last location."), false);
                            return 1;
                        })
                )
        );
    }
}
