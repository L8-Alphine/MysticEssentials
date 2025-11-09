package com.alphine.mysticessentials.commands.misc;

import com.mojang.brigadier.CommandDispatcher;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.util.MessagesUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

public class FlyCmd {
    public void register(CommandDispatcher<CommandSourceStack> d){
        d.register(net.minecraft.commands.Commands.literal("fly")
                .requires(src -> Perms.has(src, PermNodes.FLY_USE, 2))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    boolean enabled = !p.getAbilities().mayfly;
                    p.getAbilities().mayfly = enabled;
                    p.getAbilities().flying = enabled;
                    p.onUpdateAbilities();
                    p.displayClientMessage(MessagesUtil.msg(enabled ? "fly.on" : "fly.off"), false);
                    return 1;
                })
        );
    }
}
