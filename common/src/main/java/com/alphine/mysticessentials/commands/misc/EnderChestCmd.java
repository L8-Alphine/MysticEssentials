package com.alphine.mysticessentials.commands.misc;

import com.alphine.mysticessentials.inv.SnapshotEnderChestContainer;
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
import net.minecraft.world.item.ItemStack;

public class EnderChestCmd {

    public void register(CommandDispatcher<CommandSourceStack> d) {
        var root = Commands.literal("enderchest")
                // must be a player, must have self permission
                .requires(src -> src.getEntity() instanceof ServerPlayer
                        && Perms.has(src, PermNodes.ENDERCHEST_USE, 0))
                // /enderchest  -> open own real ender chest
                .executes(ctx -> openOwn(ctx.getSource()))
                // /enderchest <player>
                .then(Commands.argument("player", StringArgumentType.word())
                        // you need at least ENDERCHEST_OTHERS to *view* someone else
                        .requires(src -> Perms.has(src, PermNodes.ENDERCHEST_OTHERS, 1))
                        .executes(ctx -> openOther(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "player")
                        )));

        CommandNode<CommandSourceStack> rootNode = d.register(root);
        d.register(Commands.literal("ec").redirect(rootNode));
        d.register(Commands.literal("echest").redirect(rootNode));
    }

    // ------------------------------------------------------------------------
    // /enderchest  (self)
    // ------------------------------------------------------------------------

    private int openOwn(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        openEnderChest(player, player, false);
        return 1;
    }

    // ------------------------------------------------------------------------
    // /enderchest <player>
    // ------------------------------------------------------------------------

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

    /**
     * Open an ender chest:
     *  - if other == false (self): always opens real EC, modifiable.
     *  - if other == true:
     *      - if viewer has ENDERCHEST_OTHERS_MODIFY -> open real EC (modifiable)
     *      - else -> open a SnapshotEnderChestContainer (view-only)
     */
    private void openEnderChest(ServerPlayer viewer, ServerPlayer target, boolean other) {
        var ender = target.getEnderChestInventory();

        // Title: "Ender Chest" or "Ender Chest - Name"
        MutableComponent title = Component.translatable("container.enderchest");
        if (other) {
            title = title.append(Component.literal(" - " + target.getGameProfile().getName()));
        }

        // Can the viewer actually MODIFY the target's real ender chest?
        boolean canModifyTarget =
                !other || Perms.has(viewer, PermNodes.ENDERCHEST_OTHERS_MODIFY, 2);

        viewer.openMenu(new SimpleMenuProvider(
                (id, playerInv, p) -> {
                    if (canModifyTarget) {
                        // Self, or has modify permission → real ender chest
                        return ChestMenu.threeRows(id, playerInv, ender);
                    } else {
                        // View-only snapshot: copy the contents into a read-only container
                        ItemStack[] snapshot = new ItemStack[SnapshotEnderChestContainer.SIZE];
                        for (int i = 0; i < SnapshotEnderChestContainer.SIZE; i++) {
                            ItemStack stack = ender.getItem(i);
                            snapshot[i] = stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
                        }
                        SnapshotEnderChestContainer container = new SnapshotEnderChestContainer(snapshot);
                        return ChestMenu.threeRows(id, playerInv, container);
                    }
                },
                title
        ));
    }
}
