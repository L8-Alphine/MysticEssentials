package com.alphine.mysticessentials.commands.misc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.util.MessagesUtil;
import net.minecraft.commands.*;

import java.util.Map;

public class TimeCmd {
    public void register(CommandDispatcher<CommandSourceStack> d){
        d.register(Commands.literal("day")
                .requires(src -> Perms.has(src, PermNodes.DAY_USE, 2))
                .executes(ctx -> {
                    ctx.getSource().getLevel().setDayTime(1000);
                    ctx.getSource().sendSuccess(() -> MessagesUtil.msg("time.day"), false);
                    return 1;
                })
        );

        d.register(Commands.literal("night")
                .requires(src -> Perms.has(src, PermNodes.NIGHT_USE, 2))
                .executes(ctx -> {
                    ctx.getSource().getLevel().setDayTime(13000);
                    ctx.getSource().sendSuccess(() -> MessagesUtil.msg("time.night"), false);
                    return 1;
                })
        );

        d.register(Commands.literal("time")
                .requires(src -> Perms.has(src, PermNodes.TIME_SET, 2))
                .then(Commands.argument("ticks", IntegerArgumentType.integer(0))
                        .executes(ctx -> {
                            int t = IntegerArgumentType.getInteger(ctx, "ticks");
                            ctx.getSource().getLevel().setDayTime(t);
                            ctx.getSource().sendSuccess(() -> MessagesUtil.msg("time.set.ticks", Map.of("ticks", t)), false);
                            return 1;
                        })
                )
        );
    }
}
