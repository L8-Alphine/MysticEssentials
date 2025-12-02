package com.alphine.mysticessentials.commands.chat;

import com.alphine.mysticessentials.config.ChatConfigManager;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.server.level.ServerPlayer;

public class BroadcastCmd {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    public void register(CommandDispatcher<CommandSourceStack> d) {
        var root = Commands.literal("broadcast")
                .requires(src -> Perms.has(src, PermNodes.CHAT_BROADCAST, 2))
                .then(Commands.argument("message", MessageArgument.message())
                        .executes(this::execute));

        d.register(root);
        d.register(Commands.literal("bc").redirect(root.build()));
        d.register(Commands.literal("bcast").redirect(root.build()));
    }

    private int execute(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ChatConfigManager.BroadcastConfig cfg = ChatConfigManager.BROADCAST;
        if (cfg == null || !cfg.enabled) {
            ctx.getSource().sendFailure(
                    net.minecraft.network.chat.Component.literal("§cBroadcasts are disabled.")
            );
            return 0;
        }

        String raw = MessageArgument.getMessage(ctx, "message").getString().trim();
        if (raw.isEmpty()) {
            ctx.getSource().sendFailure(
                    net.minecraft.network.chat.Component.literal("§cYou must provide a message.")
            );
            return 0;
        }

        var server = ctx.getSource().getServer();

        // MiniMessage format with <message>
        String mmString = cfg.format.replace("<message>", raw);
        Component adv = MM.deserialize(mmString);

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            ((Audience) p).sendMessage(adv);
        }

        if (cfg.logToConsole) {
            String console = cfg.consoleFormat.replace("<message>", raw);
            server.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal(console)
            );
        }

        return 1;
    }
}