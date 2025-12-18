package com.alphine.mysticessentials.commands.tp;

import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.storage.PlayerDataStore;
import com.alphine.mysticessentials.teleport.TeleportExecutor;
import com.alphine.mysticessentials.teleport.TpaManager;
import com.alphine.mysticessentials.teleport.TpaManager.Direction;
import com.alphine.mysticessentials.util.MessagesUtil;
import com.alphine.mysticessentials.util.Teleports;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class TpaCmds {
    private static final SuggestionProvider<CommandSourceStack> TPA_SUGGEST =
            (ctx, builder) -> suggestOnlineExceptSelf(ctx.getSource(), builder);

    private final TpaManager tpa;
    private final PlayerDataStore pdata;
    private final TeleportExecutor exec;

    public TpaCmds(TpaManager tpa, TeleportExecutor exec, PlayerDataStore pdata) {
        this.tpa = tpa;
        this.exec = exec;
        this.pdata = pdata;
    }

    private static CompletableFuture<Suggestions> suggestOnlineExceptSelf(
            CommandSourceStack src,
            SuggestionsBuilder builder
    ) {
        ServerPlayer self;
        try {
            self = src.getPlayerOrException();
        } catch (Exception ex) {
            return builder.buildFuture();
        }

        return SharedSuggestionProvider.suggest(
                src.getServer().getPlayerList().getPlayers().stream()
                        .map(p -> p.getGameProfile().getName())
                        .filter(name -> !name.equalsIgnoreCase(self.getGameProfile().getName())),
                builder
        );
    }

    private static boolean featureOn() {
        var c = MEConfig.INSTANCE;
        return c == null || c.features == null || c.features.enableHomesWarpsTP;
    }

    public void register(CommandDispatcher<CommandSourceStack> d) {

        // /tpa <player>
        d.register(Commands.literal("tpa")
                .requires(src -> Perms.has(src, PermNodes.TPA_USE, 0))
                .then(Commands.argument("player", EntityArgument.player())
                        .suggests(TPA_SUGGEST)
                        .executes(ctx -> {
                            if (!featureOn()) {
                                ctx.getSource().sendFailure(MessagesUtil.msg("feature.disabled.teleport"));
                                return 0;
                            }

                            ServerPlayer from = ctx.getSource().getPlayerOrException();
                            ServerPlayer to = EntityArgument.getPlayer(ctx, "player");

                            if (to.getUUID().equals(from.getUUID())) {
                                from.displayClientMessage(MessagesUtil.msg("tpa.self"), false);
                                return 0;
                            }

                            var flags = pdata.getFlags(to.getUUID());
                            if (flags != null && flags.tpToggle) {
                                from.displayClientMessage(MessagesUtil.msg("tpa.target_not_accepting"), false);
                                return 0;
                            }

                            // Auto-accept: FROM -> TO (FROM is moving)
                            if (flags != null && flags.tpAuto) {
                                exec.runTeleport(ctx.getSource().getServer(), from, ctx.getSource(), "tpa", () -> {
                                    Teleports.pushBackAndTeleport(
                                            from, to.serverLevel(),
                                            to.getX(), to.getY(), to.getZ(),
                                            to.getYRot(), to.getXRot(), pdata
                                    );

                                    from.displayClientMessage(
                                            MessagesUtil.msg("tpa.auto_accept.from", Map.of("target", to.getName().getString())), false);
                                    to.displayClientMessage(
                                            MessagesUtil.msg("tpa.auto_accept.to", Map.of("sender", from.getName().getString())), false);
                                    return true;
                                });
                                return 1;
                            }

                            // Normal request
                            tpa.request(from.getUUID(), to.getUUID(), 60);
                            from.displayClientMessage(
                                    MessagesUtil.msg("tpa.sent", Map.of("target", to.getName().getString())), false);
                            to.displayClientMessage(
                                    MessagesUtil.msg("tpa.received", Map.of("sender", from.getName().getString())), false);
                            return 1;
                        }))
        );

        // /tpahere <player>
        d.register(Commands.literal("tpahere")
                .requires(src -> Perms.has(src, PermNodes.TPA_USE, 0))
                .then(Commands.argument("player", EntityArgument.player())
                        .suggests(TPA_SUGGEST)
                        .executes(ctx -> {
                            if (!featureOn()) {
                                ctx.getSource().sendFailure(MessagesUtil.msg("feature.disabled.teleport"));
                                return 0;
                            }

                            ServerPlayer requester = ctx.getSource().getPlayerOrException();
                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");

                            if (target.getUUID().equals(requester.getUUID())) {
                                requester.displayClientMessage(MessagesUtil.msg("tpa.self"), false);
                                return 0;
                            }

                            var flags = pdata.getFlags(target.getUUID());
                            if (flags != null && flags.tpToggle) {
                                requester.displayClientMessage(MessagesUtil.msg("tpa.target_not_accepting"), false);
                                return 0;
                            }

                            // Auto-accept: TARGET -> REQUESTER (TARGET is moving)
                            if (flags != null && flags.tpAuto) {
                                exec.runTeleport(ctx.getSource().getServer(), target, ctx.getSource(), "tpa", () -> {
                                    Teleports.pushBackAndTeleport(
                                            target, requester.serverLevel(),
                                            requester.getX(), requester.getY(), requester.getZ(),
                                            requester.getYRot(), requester.getXRot(), pdata
                                    );

                                    requester.displayClientMessage(
                                            MessagesUtil.msg("tpa.auto_accept.from", Map.of("target", target.getName().getString())), false);
                                    target.displayClientMessage(
                                            MessagesUtil.msg("tpa.auto_accept.to", Map.of("sender", requester.getName().getString())), false);
                                    return true;
                                });
                                return 1;
                            }

                            // Normal request
                            tpa.requestHere(requester.getUUID(), target.getUUID(), 60);
                            requester.displayClientMessage(
                                    MessagesUtil.msg("tpa.sent", Map.of("target", target.getName().getString())), false);
                            target.displayClientMessage(
                                    MessagesUtil.msg("tpa.received", Map.of("sender", requester.getName().getString())), false);
                            return 1;
                        }))
        );

        // /tpaccept
        d.register(Commands.literal("tpaccept")
                .requires(src -> Perms.has(src, PermNodes.TPACCEPT_USE, 0))
                .executes(ctx -> {
                    if (!featureOn()) {
                        ctx.getSource().sendFailure(MessagesUtil.msg("feature.disabled.teleport"));
                        return 0;
                    }

                    ServerPlayer to = ctx.getSource().getPlayerOrException();

                    var optReq = tpa.consume(to.getUUID());
                    if (optReq.isEmpty()) {
                        to.displayClientMessage(MessagesUtil.msg("tpa.none"), false);
                        return 0;
                    }

                    TpaManager.Request req = optReq.get();
                    ServerPlayer from = Objects.requireNonNull(to.getServer()).getPlayerList().getPlayer(req.from);
                    if (from == null) {
                        to.displayClientMessage(MessagesUtil.msg("tpa.requester_offline"), false);
                        return 0;
                    }

                    if (req.direction == Direction.TO_TARGET) {
                        // requester moves (from -> to)
                        exec.runTeleport(ctx.getSource().getServer(), from, ctx.getSource(), "tpa", () -> {
                            Teleports.pushBackAndTeleport(
                                    from, to.serverLevel(),
                                    to.getX(), to.getY(), to.getZ(),
                                    to.getYRot(), to.getXRot(), pdata
                            );

                            to.displayClientMessage(MessagesUtil.msg("tpa.accepted.to"), false);
                            from.displayClientMessage(
                                    MessagesUtil.msg("tpa.accepted.from", Map.of("target", to.getName().getString())), false);
                            return true;
                        });
                    } else {
                        // accepter moves (to -> from)
                        exec.runTeleport(ctx.getSource().getServer(), to, ctx.getSource(), "tpa", () -> {
                            Teleports.pushBackAndTeleport(
                                    to, from.serverLevel(),
                                    from.getX(), from.getY(), from.getZ(),
                                    from.getYRot(), from.getXRot(), pdata
                            );

                            to.displayClientMessage(MessagesUtil.msg("tpa.accepted.to"), false);
                            from.displayClientMessage(
                                    MessagesUtil.msg("tpa.accepted.from", Map.of("target", to.getName().getString())), false);
                            return true;
                        });
                    }

                    return 1;
                })
        );

        // /tpdeny
        d.register(Commands.literal("tpdeny")
                .requires(src -> Perms.has(src, PermNodes.TPDENY_USE, 0))
                .executes(ctx -> {
                    if (!featureOn()) {
                        ctx.getSource().sendFailure(MessagesUtil.msg("feature.disabled.teleport"));
                        return 0;
                    }
                    ServerPlayer to = ctx.getSource().getPlayerOrException();
                    var req = tpa.consume(to.getUUID());
                    if (req.isEmpty()) {
                        to.displayClientMessage(MessagesUtil.msg("tpa.none"), false);
                        return 0;
                    }
                    to.displayClientMessage(MessagesUtil.msg("tpa.denied"), false);
                    return 1;
                })
        );

        // /tptoggle
        d.register(Commands.literal("tptoggle")
                .requires(src -> Perms.has(src, PermNodes.TPP_TOGGLE, 0))
                .executes(ctx -> {
                    if (!featureOn()) {
                        ctx.getSource().sendFailure(MessagesUtil.msg("feature.disabled.teleport"));
                        return 0;
                    }

                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    var f = pdata.getFlags(p.getUUID());
                    f.tpToggle = !f.tpToggle;
                    pdata.saveFlags(p.getUUID(), f);
                    p.displayClientMessage(MessagesUtil.msg(f.tpToggle ? "tptoggle.off" : "tptoggle.on"), false);
                    return 1;
                })
        );

        // /tpauto
        d.register(Commands.literal("tpauto")
                .requires(src -> Perms.has(src, PermNodes.TPAUTO_USE, 0))
                .executes(ctx -> {
                    if (!featureOn()) {
                        ctx.getSource().sendFailure(MessagesUtil.msg("feature.disabled.teleport"));
                        return 0;
                    }

                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    var f = pdata.getFlags(p.getUUID());
                    f.tpAuto = !f.tpAuto;
                    pdata.saveFlags(p.getUUID(), f);
                    p.displayClientMessage(MessagesUtil.msg(f.tpAuto ? "tpauto.on" : "tpauto.off"), false);
                    return 1;
                })
        );
    }
}
