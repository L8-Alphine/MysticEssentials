package com.alphine.mysticessentials.commands.misc;

import com.mojang.brigadier.CommandDispatcher;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class HealCmd {
    public void register(CommandDispatcher<CommandSourceStack> d){
        d.register(net.minecraft.commands.Commands.literal("heal")
                .requires(src -> Perms.has(src, PermNodes.HEAL_USE, 2))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    p.setHealth(p.getMaxHealth());
                    p.getFoodData().setFoodLevel(20);
                    p.getFoodData().setSaturation(10f);
                    p.displayClientMessage(Component.literal("§aYou have been healed."), false);
                    return 1;
                })
        );
    }
}
