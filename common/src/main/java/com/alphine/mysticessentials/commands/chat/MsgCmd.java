package com.alphine.mysticessentials.commands.chat;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import com.mojang.brigadier.tree.CommandNode;

public class MsgCmd {

    public void register(CommandDispatcher<CommandSourceStack> d) {
        var root = Commands.literal("msg")
                .requires(src -> Perms.has(src, PermNodes.MSG_SEND, 0))
                .then(Commands.argument("target", EntityArgument.player())
                        .then(Commands.argument("message", MessageArgument.message())
                                .executes(this::execute)));

        // register main node and reuse it for redirects
        CommandNode<CommandSourceStack> rootNode = d.register(root);

        d.register(Commands.literal("tell").redirect(rootNode));
        d.register(Commands.literal("pm").redirect(rootNode));
        d.register(Commands.literal("w").redirect(rootNode));
        d.register(Commands.literal("whisper").redirect(rootNode));
    }

    private int execute(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer sender = ctx.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");

        if (sender.getUUID().equals(target.getUUID())) {
            sender.displayClientMessage(Component.literal("§cYou cannot message yourself."), false);
            return 0;
        }

        var msgComp = MessageArgument.getMessage(ctx, "message");
        String raw = msgComp.getString();

        // PrivateMessageService is responsible for doing local vs Redis-global routing
        MysticEssentialsCommon.get().privateMessages.sendMessage(sender, target, raw);
        return 1;
    }
}
