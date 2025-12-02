package com.alphine.mysticessentials.commands.chat;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class IgnoreCmd {

    public void register(CommandDispatcher<CommandSourceStack> d) {
        var root = Commands.literal("ignore")
                .requires(src -> src.getEntity() instanceof ServerPlayer)
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(this::toggleIgnore));

        d.register(root);
    }

    private int toggleIgnore(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer sender = ctx.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");

        if (sender.getUUID().equals(target.getUUID())) {
            sender.displayClientMessage(Component.literal("§cYou cannot ignore yourself."), false);
            return 0;
        }

        boolean nowIgnored = MysticEssentialsCommon.get().privateMessages.toggleIgnore(sender, target);

        if (nowIgnored) {
            sender.displayClientMessage(
                    Component.literal("§eYou are now ignoring §f" + target.getName().getString() + "§e."), false);
        } else {
            sender.displayClientMessage(
                    Component.literal("§aYou are no longer ignoring §f" + target.getName().getString() + "§a."), false);
        }

        return 1;
    }
}
