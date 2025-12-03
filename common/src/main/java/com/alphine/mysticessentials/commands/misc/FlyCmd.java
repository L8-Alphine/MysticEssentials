package com.alphine.mysticessentials.commands.misc;

import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.util.MessagesUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

public class FlyCmd {

    public void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("fly")
                // must be a player, must have base fly permission
                .requires(src -> src.getEntity() instanceof ServerPlayer
                        && Perms.has(src, PermNodes.FLY_USE, 2))

                // /fly
                .executes(ctx -> {
                    ServerPlayer self = ctx.getSource().getPlayerOrException();
                    toggleFlySelf(ctx.getSource(), self);
                    return 1;
                })

                // /fly <player>
                .then(Commands.argument("player", StringArgumentType.word())
                        .requires(src -> Perms.has(src, PermNodes.FLY_USE_OTHERS, 2))
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            ServerPlayer executor = src.getPlayerOrException();
                            String targetName = StringArgumentType.getString(ctx, "player");

                            ServerPlayer target = src.getServer().getPlayerList().getPlayerByName(targetName);
                            if (target == null) {
                                executor.displayClientMessage(
                                        Component.literal("§cPlayer §f" + targetName + " §cis not online."),
                                        false
                                );
                                return 0;
                            }

                            toggleFlyOther(src, executor, target);
                            return 1;
                        })
                )
        );
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private void toggleFlySelf(CommandSourceStack src, ServerPlayer player) {
        boolean enabled = !player.getAbilities().mayfly;

        player.getAbilities().mayfly = enabled;
        player.getAbilities().flying = enabled;
        player.onUpdateAbilities();

        // Localized message to the player
        player.displayClientMessage(
                MessagesUtil.msg(enabled ? "fly.on" : "fly.off"),
                false
        );
    }

    private void toggleFlyOther(CommandSourceStack src, ServerPlayer executor, ServerPlayer target) {
        boolean enabled = !target.getAbilities().mayfly;

        target.getAbilities().mayfly = enabled;
        target.getAbilities().flying = enabled;
        target.onUpdateAbilities();

        // Tell the target using your localized messages
        target.displayClientMessage(
                MessagesUtil.msg(enabled ? "fly.on" : "fly.off"),
                false
        );

        // Notify the executor with a simple confirmation line
        String mode = enabled ? "enabled" : "disabled";
        executor.displayClientMessage(
                Component.literal("§aFlight " + mode + " for §f" + target.getGameProfile().getName() + "§a."),
                false
        );
    }
}
