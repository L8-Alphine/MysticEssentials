package com.alphine.mysticessentials.commands.tp;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.storage.PlayerDataStore;
import com.alphine.mysticessentials.teleport.TpaManager;
import com.alphine.mysticessentials.teleport.WarmupManager;
import com.alphine.mysticessentials.util.Teleports;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class TpaCmds {
    private final TpaManager tpa; private final WarmupManager warm; private final PlayerDataStore pdata;
    public TpaCmds(TpaManager tpa, WarmupManager warm, PlayerDataStore pdata){ this.tpa=tpa; this.warm=warm; this.pdata=pdata; }

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
                            if (!featureOn()) { ctx.getSource().sendFailure(Component.literal("§cTeleport features are disabled by config.")); return 0; }

                            ServerPlayer from = ctx.getSource().getPlayerOrException();
                            String name = StringArgumentType.getString(ctx,"player");
                            ServerPlayer to = from.getServer().getPlayerList().getPlayerByName(name);
                            if (to == null) { from.displayClientMessage(Component.literal("§cPlayer not found."), false); return 0; }
                            if (to.getUUID().equals(from.getUUID())) { from.displayClientMessage(Component.literal("§cYou cannot send TPA to yourself."), false); return 0; }

                            var flags = pdata.getFlags(to.getUUID());
                            if (flags != null && flags.tpToggle) {
                                from.displayClientMessage(Component.literal("§cThat player is not accepting TPA requests."), false);
                                return 0;
                            }

                            // Auto-accept path
                            if (flags != null && flags.tpAuto) {
                                Runnable tp = () -> Teleports.pushBackAndTeleport(from, to.serverLevel(), to.getX(), to.getY(), to.getZ(), to.getYRot(), to.getXRot(), pdata);
                                int warmSec = (MEConfig.INSTANCE != null) ? MEConfig.INSTANCE.getWarmup("tpa") : 0;
                                warm.startOrBypass(ctx.getSource().getServer(), from, warmSec, () -> {
                                    tp.run();
                                    from.displayClientMessage(Component.literal("§aAuto-accepted. Teleporting to §e" + to.getName().getString()), false);
                                    to.displayClientMessage(Component.literal("§7Auto-accepted TPA from §e" + from.getName().getString()), false);
                                });
                                return 1;
                            }

                            // Normal request (overwrites previous pending for that target, which is fine)
                            tpa.request(from.getUUID(), to.getUUID(), 60);
                            from.displayClientMessage(Component.literal("§aTPA request sent to §e" + to.getName().getString()), false);
                            to.displayClientMessage(Component.literal("§e" + from.getName().getString() + " §7requested to teleport to you. §a/tpaccept §7or §c/tpdeny"), false);
                            return 1;
                        })
                )
        );

        // /tpaccept
        d.register(Commands.literal("tpaccept")
                .requires(src -> Perms.has(src, PermNodes.TPACCEPT_USE, 0))
                .executes(ctx -> {
                    if (!featureOn()) { ctx.getSource().sendFailure(Component.literal("§cTeleport features are disabled by config.")); return 0; }

                    ServerPlayer to = ctx.getSource().getPlayerOrException();
                    var req = tpa.consume(to.getUUID());
                    if (req.isEmpty()) { to.displayClientMessage(Component.literal("§cNo pending requests."), false); return 0; }
                    ServerPlayer from = to.getServer().getPlayerList().getPlayer(req.get().from);
                    if (from == null) { to.displayClientMessage(Component.literal("§cRequester offline."), false); return 0; }

                    int warmSec = (MEConfig.INSTANCE != null) ? MEConfig.INSTANCE.getWarmup("tpa") : 0;
                    Runnable tp = () -> Teleports.pushBackAndTeleport(from, to.serverLevel(), to.getX(), to.getY(), to.getZ(), to.getYRot(), to.getXRot(), pdata);
                    warm.startOrBypass(ctx.getSource().getServer(), from, warmSec, tp);

                    to.displayClientMessage(Component.literal("§aAccepted request."), false);
                    from.displayClientMessage(Component.literal("§aTeleporting to §e" + to.getName().getString()), false);
                    return 1;
                })
        );

        // /tpdeny
        d.register(Commands.literal("tpdeny")
                .requires(src -> Perms.has(src, PermNodes.TPDENY_USE, 0))
                .executes(ctx -> {
                    if (!featureOn()) { ctx.getSource().sendFailure(Component.literal("§cTeleport features are disabled by config.")); return 0; }
                    ServerPlayer to = ctx.getSource().getPlayerOrException();
                    var req = tpa.consume(to.getUUID());
                    if (req.isEmpty()) { to.displayClientMessage(Component.literal("§cNo pending requests."), false); return 0; }
                    to.displayClientMessage(Component.literal("§cDenied request."), false);
                    return 1;
                })
        );

        // /tptoggle   (NOTE: was PermNodes.TPP_TOGGLE; likely a typo)
        d.register(Commands.literal("tptoggle")
                .requires(src -> Perms.has(src, /* PermNodes.TP_TOGGLE */ PermNodes.TPP_TOGGLE, 0))
                .executes(ctx -> {
                    if (!featureOn()) { ctx.getSource().sendFailure(Component.literal("§cTeleport features are disabled by config.")); return 0; }

                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    var f = pdata.getFlags(p.getUUID());
                    f.tpToggle = !f.tpToggle; pdata.saveFlags(p.getUUID(), f);
                    p.displayClientMessage(Component.literal("§7TPA Toggle: " + (f.tpToggle ? "§cOFF" : "§aON")), false);
                    return 1;
                })
        );

        // /tpauto
        d.register(Commands.literal("tpauto")
                .requires(src -> Perms.has(src, PermNodes.TPAUTO_USE, 0))
                .executes(ctx -> {
                    if (!featureOn()) { ctx.getSource().sendFailure(Component.literal("§cTeleport features are disabled by config.")); return 0; }

                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    var f = pdata.getFlags(p.getUUID());
                    f.tpAuto = !f.tpAuto; pdata.saveFlags(p.getUUID(), f);
                    p.displayClientMessage(Component.literal("§7TPA Auto-Accept: " + (f.tpAuto ? "§aON" : "§cOFF")), false);
                    return 1;
                })
        );
    }
}
