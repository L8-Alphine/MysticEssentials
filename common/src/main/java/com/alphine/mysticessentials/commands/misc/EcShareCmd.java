package com.alphine.mysticessentials.commands.misc;

import com.alphine.mysticessentials.inv.SnapshotEnderChestContainer;
import com.alphine.mysticessentials.util.MessagesUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

public class EcShareCmd {

    public void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("ecshare")
                // no perms – read-only snapshot view for anyone
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> openSnapshot(ctx.getSource(),
                                StringArgumentType.getString(ctx, "player")))));
    }

    private int openSnapshot(CommandSourceStack src, String name) throws CommandSyntaxException {
        ServerPlayer viewer = src.getPlayerOrException();
        ServerPlayer target = viewer.getServer().getPlayerList().getPlayerByName(name);

        if (target == null) {
            viewer.displayClientMessage(
                    MessagesUtil.msg("tp.player_not_found", Map.of("player", name)),
                    false
            );
            return 0;
        }

        // Snapshot the target's real ender chest
        var ec = target.getEnderChestInventory();
        ItemStack[] snapshot = new ItemStack[SnapshotEnderChestContainer.SIZE];
        for (int i = 0; i < snapshot.length; i++) {
            snapshot[i] = ec.getItem(i).copy();
        }

        SnapshotEnderChestContainer snap = new SnapshotEnderChestContainer(snapshot);

        Component title = Component.literal(
                target.getGameProfile().getName() + "'s Ender Chest"
        );

        viewer.openMenu(new SimpleMenuProvider(
                (id, playerInv, p) -> new ChestMenu(
                        MenuType.GENERIC_9x3,
                        id,
                        playerInv,
                        ensureRows(snap, 3),
                        3
                ),
                title
        ));

        return 1;
    }

    private static Container ensureRows(Container c, int rows) {
        int needed = rows * 9;
        if (c.getContainerSize() == needed) return c;
        SimpleContainer box = new SimpleContainer(needed);
        for (int i = 0; i < needed; i++) {
            box.setItem(i, i < c.getContainerSize() ? c.getItem(i).copy() : ItemStack.EMPTY);
        }
        return box;
    }
}
