package com.alphine.mysticessentials.commands.chat;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class SocialSpyCmd {

    public void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("socialspy")
                .requires(src -> Perms.has(src, PermNodes.MSG_SPY, 2))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    boolean enabled = MysticEssentialsCommon.get().privateMessages.toggleSpy(p);

                    ctx.getSource().sendSuccess(
                            () -> Component.literal(enabled
                                    ? "§aPrivate message spy enabled."
                                    : "§cPrivate message spy disabled."),
                            false
                    );
                    return 1;
                }));
    }
}
