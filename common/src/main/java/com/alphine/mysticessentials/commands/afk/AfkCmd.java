package com.alphine.mysticessentials.commands.afk;

import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.util.AfkService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

public final class AfkCmd {
    private final AfkService afk;

    public AfkCmd(AfkService afk) { this.afk = Objects.requireNonNull(afk, "afk"); }

    public void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("afk")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    boolean nowAfk = afk.toggleAfk(p, null);
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            nowAfk ? "You are now AFK." : "You are no longer AFK."
                    ), false);
                    return 1;
                })
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String msg = StringArgumentType.getString(ctx, "message");
                            boolean nowAfk = afk.toggleAfk(p, msg);
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    nowAfk ? "You are now AFK." : "You are no longer AFK."
                            ), false);
                            return 1;
                        })
                )
        );
    }

    // TODO: Removed in favor of above simplified version
//    private int toggle(CommandSourceStack src, String message) {
//        ServerPlayer p = src.getPlayer();
//        if (p == null) {
//            src.sendFailure(Component.literal("Players only."));
//            return 0;
//        }
//
//        // Permission to set a custom message?
//        if (message != null && !message.isBlank() && !Perms.has(p, PermNodes.AFK_MESSAGE_SET, 0)) {
//            src.sendFailure(Component.literal("You don't have permission to set a custom AFK message."));
//            return 0;
//        }
//
//        boolean nowAfk = afk.toggleAfk(p, message);
//        if (nowAfk) src.sendSuccess(() -> Component.literal("You are now AFK."), false);
//        else        src.sendSuccess(() -> Component.literal("You are no longer AFK."), false);
//        return 1;
//    }
}
