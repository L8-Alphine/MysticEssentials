package com.alphine.mysticessentials.commands.chat;

import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class ClearChatCmd {

    private static final int CLEAR_LINES = 100;

    public void register(CommandDispatcher<CommandSourceStack> d) {
        var root = Commands.literal("clearchat")
                .requires(src -> Perms.has(src, PermNodes.CHAT_CLEAR, 2))
                .executes(ctx -> {
                    var server = ctx.getSource().getServer();
                    Component clearedBy = Component.literal("§7Chat cleared by §a"
                            + ctx.getSource().getDisplayName().getString());

                    for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                        for (int i = 0; i < CLEAR_LINES; i++) {
                            p.sendSystemMessage(Component.empty());
                        }
                        p.sendSystemMessage(clearedBy);
                    }

                    return 1;
                });

        CommandNode<CommandSourceStack> rootNode = d.register(root);
        d.register(Commands.literal("cc").redirect(rootNode));
        d.register(Commands.literal("chatclear").redirect(rootNode));
    }
}
