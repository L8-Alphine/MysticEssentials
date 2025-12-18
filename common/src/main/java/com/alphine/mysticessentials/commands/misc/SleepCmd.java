package com.alphine.mysticessentials.commands.misc;

import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.util.MessagesUtil;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

public class SleepCmd {

    public void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("sleep")
                .requires(src -> Perms.has(src, PermNodes.SLEEP, 2))
                .executes(ctx -> doRest(ctx.getSource()))
        );

        // Alias: /rest
        d.register(Commands.literal("rest")
                .requires(src -> Perms.has(src, PermNodes.SLEEP, 2))
                .executes(ctx -> doRest(ctx.getSource()))
        );
    }

    private int doRest(CommandSourceStack src) {
        try {
            ServerPlayer p = src.getPlayerOrException();
            p.getStats().setValue(p, Stats.CUSTOM.get(Stats.TIME_SINCE_REST), 0);
            p.displayClientMessage(MessagesUtil.msg("sleep.reset"), false);
            return 1;
        } catch (Exception ex) {
            src.sendFailure(Component.literal("Internal error."));
            return 0;
        }
    }
}
