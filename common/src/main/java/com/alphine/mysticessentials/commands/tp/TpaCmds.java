package com.alphine.mysticessentials.commands.tp;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.storage.PlayerDataStore;
import com.alphine.mysticessentials.teleport.TpaManager;
import com.alphine.mysticessentials.teleport.WarmupManager;
import com.alphine.mysticessentials.util.MessagesUtil;
import com.alphine.mysticessentials.util.Teleports;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

public class TpaCmds {
    private final TpaManager tpa;
    private final WarmupManager warm;
    private final PlayerDataStore pdata;

    public TpaCmds(TpaManager tpa, WarmupManager warm, PlayerDataStore pdata){
        this.tpa = tpa; this.warm = warm; this.pdata = pdata;
    }

    private static boolean featureOn() {
        var c = MEConfig.INSTANCE;
        return c == null || c.features == null || c.features.enableHomesWarpsTP;
    }

    public void register(CommandDispatcher<CommandSourceStack> d){
        // /tpa <player>
        d.register(Commands.literal("tpa")
                .requires(src -> Perms.has(src, PermNodes.TPA_USE, 0))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            if (!featureOn()) { ctx.getSource().sendFailure(MessagesUtil.msg("feature.disabled.teleport")); return 0; }

                            ServerPlayer from = ctx.getSource().getPlayerOrException();
                            String name = StringArgumentType.getString(ctx, "player");
                            ServerPlayer to = from.getServer().getPlayerList().getPlayerByName(name);
                            if (to == null) { from.displayClientMessage(MessagesUtil.msg("tp.player_not_found"), false); return 0; }
                            if (to.getUUID().equals(from.getUUID())) { from.displayClientMessage(MessagesUtil.msg("tpa.self"), false); return 0; }

                            var flags = pdata.getFlags(to.getUUID());
                            if (flags != null && flags.tpToggle) {
                                from.displayClientMessage(MessagesUtil.msg("tpa.target_not_accepting"), false);
                                return 0;
                            }

                            // Auto-accept path
                            if (flags != null && flags.tpAuto) {
                                int warmSec = (MEConfig.INSTANCE != null) ? MEConfig.INSTANCE.getWarmup("tpa") : 0;
                                Runnable tp = () -> Teleports.pushBackAndTeleport(
                                        from, to.serverLevel(),
                                        to.getX(), to.getY(), to.getZ(),
                                        to.getYRot(), to.getXRot(), pdata
                                );
                                warm.startOrBypass(ctx.getSource().getServer(), from, warmSec, () -> {
                                    tp.run();
                                    from.displayClientMessage(
                                            MessagesUtil.msg("tpa.auto_accept.from", Map.of("target", to.getName().getString())), false);
                                    to.displayClientMessage(
                                            MessagesUtil.msg("tpa.auto_accept.to", Map.of("sender", from.getName().getString())), false);
                                });
                                return 1;
                            }

                            // Normal request
                            tpa.request(from.getUUID(), to.getUUID(), 60);
                            from.displayClientMessage(MessagesUtil.msg("tpa.sent", Map.of("target", to.getName().getString())), false);
                            to.displayClientMessage(MessagesUtil.msg("tpa.received", Map.of("sender", from.getName().getString())), false);
                            return 1;
                        })
                )
        );

        // /tpaccept
        d.register(Commands.literal("tpaccept")
                .requires(src -> Perms.has(src, PermNodes.TPACCEPT_USE, 0))
                .executes(ctx -> {
                    if (!featureOn()) { ctx.getSource().sendFailure(MessagesUtil.msg("feature.disabled.teleport")); return 0; }

                    ServerPlayer to = ctx.getSource().getPlayerOrException();
                    var req = tpa.consume(to.getUUID());
                    if (req.isEmpty()) { to.displayClientMessage(MessagesUtil.msg("tpa.none"), false); return 0; }
                    ServerPlayer from = to.getServer().getPlayerList().getPlayer(req.get().from);
                    if (from == null) { to.displayClientMessage(MessagesUtil.msg("tpa.requester_offline"), false); return 0; }

                    int warmSec = (MEConfig.INSTANCE != null) ? MEConfig.INSTANCE.getWarmup("tpa") : 0;
                    Runnable tp = () -> Teleports.pushBackAndTeleport(
                            from, to.serverLevel(),
                            to.getX(), to.getY(), to.getZ(),
                            to.getYRot(), to.getXRot(), pdata
                    );
                    warm.startOrBypass(ctx.getSource().getServer(), from, warmSec, tp);

                    to.displayClientMessage(MessagesUtil.msg("tpa.accepted.to"), false);
                    from.displayClientMessage(MessagesUtil.msg("tpa.accepted.from", Map.of("target", to.getName().getString())), false);
                    return 1;
                })
        );

        // /tpdeny
        d.register(Commands.literal("tpdeny")
                .requires(src -> Perms.has(src, PermNodes.TPDENY_USE, 0))
                .executes(ctx -> {
                    if (!featureOn()) { ctx.getSource().sendFailure(MessagesUtil.msg("feature.disabled.teleport")); return 0; }
                    ServerPlayer to = ctx.getSource().getPlayerOrException();
                    var req = tpa.consume(to.getUUID());
                    if (req.isEmpty()) { to.displayClientMessage(MessagesUtil.msg("tpa.none"), false); return 0; }
                    to.displayClientMessage(MessagesUtil.msg("tpa.denied"), false);
                    return 1;
                })
        );

        // /tptoggle
        d.register(Commands.literal("tptoggle")
                .requires(src -> Perms.has(src, /* If your node is TP_TOGGLE, swap here */ PermNodes.TPP_TOGGLE, 0))
                .executes(ctx -> {
                    if (!featureOn()) { ctx.getSource().sendFailure(MessagesUtil.msg("feature.disabled.teleport")); return 0; }

                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    var f = pdata.getFlags(p.getUUID());
                    f.tpToggle = !f.tpToggle; pdata.saveFlags(p.getUUID(), f);
                    p.displayClientMessage(MessagesUtil.msg(f.tpToggle ? "tptoggle.off" : "tptoggle.on"), false);
                    return 1;
                })
        );

        // /tpauto
        d.register(Commands.literal("tpauto")
                .requires(src -> Perms.has(src, PermNodes.TPAUTO_USE, 0))
                .executes(ctx -> {
                    if (!featureOn()) { ctx.getSource().sendFailure(MessagesUtil.msg("feature.disabled.teleport")); return 0; }

                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    var f = pdata.getFlags(p.getUUID());
                    f.tpAuto = !f.tpAuto; pdata.saveFlags(p.getUUID(), f);
                    p.displayClientMessage(MessagesUtil.msg(f.tpAuto ? "tpauto.on" : "tpauto.off"), false);
                    return 1;
                })
        );
    }
}
