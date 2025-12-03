package com.alphine.mysticessentials.commands.chat;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class MsgCmd {

    public void register(CommandDispatcher<CommandSourceStack> d) {

        // -------------------------------------------------
        // Remove vanilla whisper commands so we fully own:
        //   /msg, /tell, /w, /whisper, /pm
        // (does NOT touch /tellraw etc.)
        // -------------------------------------------------
        removeRootChild(d, "msg");
        removeRootChild(d, "tell");
        removeRootChild(d, "w");
        removeRootChild(d, "whisper");
        removeRootChild(d, "pm");

        // Our main /msg command
        var root = Commands.literal("msg")
                .requires(src -> Perms.has(src, PermNodes.MSG_SEND, 0))
                .then(Commands.argument("target", EntityArgument.player())
                        .then(Commands.argument("message", MessageArgument.message())
                                .executes(this::execute)));

        // Register and then redirect aliases to the same node
        CommandNode<CommandSourceStack> rootNode = d.register(root);

        d.register(Commands.literal("tell").redirect(rootNode));
        d.register(Commands.literal("pm").redirect(rootNode));
        d.register(Commands.literal("w").redirect(rootNode));
        d.register(Commands.literal("whisper").redirect(rootNode));
    }

    private static void removeRootChild(CommandDispatcher<CommandSourceStack> d, String name) {
        CommandNode<CommandSourceStack> existing = d.getRoot().getChild(name);
        if (existing != null) {
            d.getRoot().getChildren().remove(existing);
        }
    }

    private int execute(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer sender = ctx.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");

        if (sender.getUUID().equals(target.getUUID())) {
            sender.displayClientMessage(
                    Component.literal("§cYou cannot message yourself."),
                    false
            );
            return 0;
        }

        var msgComp = MessageArgument.getMessage(ctx, "message");
        String raw = msgComp.getString();

        // Use MysticEssentials private message pipeline (Redis-aware)
        MysticEssentialsCommon.get().privateMessages.sendMessage(sender, target, raw);
        return 1;
    }
}
