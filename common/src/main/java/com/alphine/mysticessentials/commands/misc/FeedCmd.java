package com.alphine.mysticessentials.commands.misc;

import com.mojang.brigadier.CommandDispatcher;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class FeedCmd {
    public void register(CommandDispatcher<CommandSourceStack> d){
        d.register(net.minecraft.commands.Commands.literal("feed")
                .requires(src -> Perms.has(src, PermNodes.FEED_USE, 2))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    p.getFoodData().setFoodLevel(20);
                    p.getFoodData().setSaturation(10f);
                    p.displayClientMessage(Component.literal("§aYou feel full."), false);
                    return 1;
                })
        );
    }
}
