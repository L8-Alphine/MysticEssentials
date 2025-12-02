package com.alphine.mysticessentials.commands.misc;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.util.DurationUtil;
import com.alphine.mysticessentials.util.MessagesUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

public class PlaytimeCmds {

    public void register(CommandDispatcher<CommandSourceStack> d) {
        var root = Commands.literal("playtime")
                // /playtime (self)
                .requires(src -> Perms.has(src, PermNodes.PLAYTIME, 0))
                .executes(ctx -> {
                    ServerPlayer self = ctx.getSource().getPlayerOrException();
                    return sendPlaytime(ctx.getSource(), self);
                })

                // /playtime <player>
                .then(Commands.argument("player", EntityArgument.player())
                        .requires(src -> Perms.has(src, PermNodes.PLAYTIME_OTHERS, 0))
                        .executes(ctx -> {
                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                            return sendPlaytime(ctx.getSource(), target);
                        }))

                // /playtime admin ...
                .then(Commands.literal("admin")
                        .requires(src -> Perms.has(src, PermNodes.PLAYTIME_ADMIN, 0))

                        // /playtime admin get <player>
                        .then(Commands.literal("get")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                            return sendPlaytime(ctx.getSource(), target);
                                        })
                                ))

                        // /playtime admin reset <player>
                        .then(Commands.literal("reset")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                            var common = MysticEssentialsCommon.get();

                                            common.pdata.setTotalPlaytimeMillis(target.getUUID(), 0L);

                                            Component msg = MessagesUtil.msg(
                                                    "playtime.admin.reset",
                                                    Map.of("name", target.getGameProfile().getName())
                                            );
                                            ctx.getSource().sendSuccess(() -> msg, false);
                                            return 1;
                                        })
                                ))

                        // /playtime admin set <player> <seconds>
                        .then(Commands.literal("set")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("seconds", IntegerArgumentType.integer(0))
                                                .executes(ctx -> {
                                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                                    int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
                                                    long millis = seconds * 1000L;

                                                    var common = MysticEssentialsCommon.get();
                                                    common.pdata.setTotalPlaytimeMillis(target.getUUID(), millis);

                                                    String nice = DurationUtil.fmtRemaining(millis);

                                                    Component msg = MessagesUtil.msg(
                                                            "playtime.admin.set",
                                                            Map.of(
                                                                    "name", target.getGameProfile().getName(),
                                                                    "time", nice,
                                                                    "seconds", String.valueOf(seconds)
                                                            )
                                                    );
                                                    ctx.getSource().sendSuccess(() -> msg, false);
                                                    return 1;
                                                })
                                        )
                                ))
                );

        d.register(root);
    }

    /** Helper: format and send a playtime line for target to source. */
    private static int sendPlaytime(CommandSourceStack src, ServerPlayer target) {
        var common = MysticEssentialsCommon.get();
        long totalMs = common.pdata.getTotalPlaytimeMillis(target.getUUID());
        String nice = DurationUtil.fmtRemaining(totalMs);

        boolean self = src.getEntity() instanceof ServerPlayer sp
                && sp.getUUID().equals(target.getUUID());

        String key = self ? "playtime.self" : "playtime.other";

        Component msg = MessagesUtil.msg(
                key,
                Map.of(
                        "name", target.getGameProfile().getName(),
                        "time", nice
                )
        );

        src.sendSuccess(() -> msg, false);
        return 1;
    }
}
