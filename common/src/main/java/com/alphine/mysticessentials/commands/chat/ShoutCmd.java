package com.alphine.mysticessentials.commands.chat;

import com.alphine.mysticessentials.config.ChatConfigManager;
import com.alphine.mysticessentials.config.ChatConfigManager.ChannelsConfig;
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

public class ShoutCmd {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    public void register(CommandDispatcher<CommandSourceStack> d) {
        var root = Commands.literal("shout")
                .requires(src -> Perms.has(src, PermNodes.CHAT_SHOUT, 0))
                .then(Commands.argument("message", MessageArgument.message())
                        .executes(this::execute));

        d.register(root);
        d.register(Commands.literal("sh").redirect(root.build()));
        d.register(Commands.literal("yell").redirect(root.build()));
    }

    private int execute(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ChatConfigManager.ShoutConfig cfg = ChatConfigManager.SHOUT;
        if (cfg == null || !cfg.enabled) {
            ctx.getSource().sendFailure(
                    net.minecraft.network.chat.Component.literal("§cShout is disabled.")
            );
            return 0;
        }

        ServerPlayer sender = ctx.getSource().getPlayerOrException();
        String raw = MessageArgument.getMessage(ctx, "message").getString().trim();
        if (raw.isEmpty()) {
            sender.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("§cYou must provide a message."),
                    false
            );
            return 0;
        }

        var server = sender.getServer();
        var world  = sender.serverLevel();

        int radius = cfg.radius > 0 ? cfg.radius : 100;
        int radiusSq = radius * radius;

        String senderName = sender.getGameProfile().getName();

        // Build MiniMessage line using config
        String mmString = cfg.format
                .replace("<sender>", senderName)
                .replace("<message>", raw)
                .replace("<radius>", String.valueOf(radius));

        Component adv = MM.deserialize(mmString);

        int delivered = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level() != world) continue;

            double dx = p.getX() - sender.getX();
            double dy = p.getY() - sender.getY();
            double dz = p.getZ() - sender.getZ();
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq <= radiusSq) {
                ((Audience) p).sendMessage(adv);
                delivered++;
            }
        }

        if (delivered == 0) {
            String nobody = cfg.nobodyHeard
                    .replace("<radius>", String.valueOf(radius));
            Component nobodyCmp = MM.deserialize(nobody);
            ((Audience) sender).sendMessage(nobodyCmp);
        }

        if (cfg.logToConsole) {
            String console = cfg.consoleFormat
                    .replace("<sender>", senderName)
                    .replace("<message>", raw)
                    .replace("<radius>", String.valueOf(radius));
            server.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal(console)
            );
        }

        return 1;
    }
}
