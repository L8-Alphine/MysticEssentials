package com.alphine.mysticessentials.commands.misc;

import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;

public class EnderChestCmd {

    public void register(CommandDispatcher<CommandSourceStack> d) {
        var root = Commands.literal("enderchest")
                // must be a player, must have self permission
                .requires(src -> src.getEntity() instanceof ServerPlayer
                        && Perms.has(src, PermNodes.ENDERCHEST_USE, 0))
                // /enderchest
                .executes(ctx -> openOwn(ctx.getSource()))
                // /enderchest <player>
                .then(Commands.argument("player", StringArgumentType.word())
                        .requires(src -> Perms.has(src, PermNodes.ENDERCHEST_OTHERS, 2))
                        .executes(ctx -> openOther(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "player")
                        )));

        CommandNode<CommandSourceStack> rootNode = d.register(root);
        d.register(Commands.literal("ec").redirect(rootNode));
        d.register(Commands.literal("echest").redirect(rootNode));
    }

    private int openOwn(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        openEnderChest(player, player, false);
        return 1;
    }

    private int openOther(CommandSourceStack src, String targetName) throws CommandSyntaxException {
        ServerPlayer viewer = src.getPlayerOrException();
        ServerPlayer target = viewer.getServer().getPlayerList().getPlayerByName(targetName);

        if (target == null) {
            viewer.displayClientMessage(
                    Component.literal("§cPlayer §f" + targetName + " §cis not online."),
                    false
            );
            return 0;
        }

        openEnderChest(viewer, target, true);
        return 1;
    }

    private void openEnderChest(ServerPlayer viewer, ServerPlayer target, boolean other) {
        var ender = target.getEnderChestInventory();

        MutableComponent title = Component.translatable("container.enderchest");
        if (other) {
            title = title.append(Component.literal(" - " + target.getGameProfile().getName()));
        }

        viewer.openMenu(new SimpleMenuProvider(
                (id, playerInv, p) -> ChestMenu.threeRows(id, playerInv, ender),
                title
        ));
    }
}
