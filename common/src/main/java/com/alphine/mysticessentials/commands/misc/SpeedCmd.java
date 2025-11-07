package com.alphine.mysticessentials.commands.misc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class SpeedCmd {
    public void register(CommandDispatcher<CommandSourceStack> d){
        d.register(net.minecraft.commands.Commands.literal("speed")
                .requires(src -> Perms.has(src, PermNodes.SPEED_USE, 2))
                .then(net.minecraft.commands.Commands.argument("amount", FloatArgumentType.floatArg(0.1f, 10.0f))
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            float amount = FloatArgumentType.getFloat(ctx, "amount");
                            if(p.getAbilities().flying){
                                p.getAbilities().setFlyingSpeed(amount / 10.0f);
                            } else {
                                p.getAbilities().setWalkingSpeed(amount / 10.0f);
                            }
                            p.onUpdateAbilities();
                            p.displayClientMessage(Component.literal("§7Speed set to §e" + amount), false);
                            return 1;
                        })
                )
        );
    }
}
