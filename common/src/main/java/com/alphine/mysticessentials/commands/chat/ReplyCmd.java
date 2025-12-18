package com.alphine.mysticessentials.commands.chat;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.server.level.ServerPlayer;

public class ReplyCmd {

    public void register(CommandDispatcher<CommandSourceStack> d) {
        var root = Commands.literal("reply")
                .requires(src -> Perms.has(src, PermNodes.MSG_REPLY, 0))
                .then(Commands.argument("message", MessageArgument.message())
                        .executes(ctx -> {
                            ServerPlayer sender = ctx.getSource().getPlayerOrException();
                            var msgComp = MessageArgument.getMessage(ctx, "message");
                            String raw = msgComp.getString();

                            MysticEssentialsCommon.get().privateMessages.reply(sender, raw);
                            return 1;
                        }));

        CommandNode<CommandSourceStack> rootNode = d.register(root);
        d.register(Commands.literal("r").redirect(rootNode));
    }
}
