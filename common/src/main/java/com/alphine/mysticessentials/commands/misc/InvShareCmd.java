package com.alphine.mysticessentials.commands.misc;

import com.alphine.mysticessentials.inv.InventoryIO;
import com.alphine.mysticessentials.inv.SnapshotInventoryContainer;
import com.alphine.mysticessentials.util.MessagesUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;

public class InvShareCmd {

    public void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("invshare")
                // no permission check – anyone can use; we only show read-only snapshot
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> openSnapshot(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "player")
                        ))));
    }

    private int openSnapshot(CommandSourceStack src, String name) throws CommandSyntaxException {
        ServerPlayer viewer = src.getPlayerOrException();
        ServerPlayer target = Objects.requireNonNull(viewer.getServer()).getPlayerList().getPlayerByName(name);

        if (target == null) {
            viewer.displayClientMessage(
                    MessagesUtil.msg("tp.player_not_found", Map.of("player", name)),
                    false
            );
            return 0;
        }

        // Capture snapshot via InventoryIO
        HolderLookup.Provider provider = target.level().registryAccess();
        Map<String, Object> payload = InventoryIO.captureToPayload(target);
        ItemStack[] stacks = InventoryIO.stacksFromPayload(provider, payload);

        // Read-only snapshot container (editable = false, no onSave)
        SnapshotInventoryContainer snap =
                new SnapshotInventoryContainer(stacks, false, null);

        Component title = Component.literal(
                target.getGameProfile().getName() + "'s Inventory"
        );

        viewer.openMenu(new SimpleMenuProvider(
                (id, playerInv, p) -> new ChestMenu(
                        MenuType.GENERIC_9x6,
                        id,
                        playerInv,
                        ensureRows(snap, 6),
                        6
                ) {
                    // IMPORTANT: prevent shift-click moving items into the snapshot
                    @Override
                    public @NotNull ItemStack quickMoveStack(Player player, int index) {
                        return ItemStack.EMPTY;
                    }
                },
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
