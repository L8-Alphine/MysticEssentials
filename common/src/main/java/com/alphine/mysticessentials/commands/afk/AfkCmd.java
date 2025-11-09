package com.alphine.mysticessentials.commands.afk;

import com.alphine.mysticessentials.util.AfkService;
import com.alphine.mysticessentials.util.MessagesUtil; // ← add
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;
import java.util.Map;

public final class AfkCmd {
    private final AfkService afk;
    public AfkCmd(AfkService afk) { this.afk = Objects.requireNonNull(afk, "afk"); }

    public void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("afk")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    afk.toggleAfk(p, null); // service handles messages
                    return 1;
                })
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String msg = StringArgumentType.getString(ctx, "message");
                            afk.toggleAfk(p, msg); // service handles messages
                            return 1;
                        })
                )
        );
    }
}
