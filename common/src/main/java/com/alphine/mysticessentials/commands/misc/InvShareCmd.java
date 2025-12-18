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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;

public class InvShareCmd {

    public void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("invshare")
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

//        HolderLookup.Provider provider = target.level().registryAccess();
//        Map<String, Object> payload = InventoryIO.captureToPayload(target);
        ItemStack[] stacks = snapshotStacks(target);
        SnapshotInventoryContainer snap = new SnapshotInventoryContainer(stacks, false, null);

        Component title = Component.literal(target.getGameProfile().getName() + "'s Inventory");

        viewer.openMenu(new SimpleMenuProvider(
                (id, playerInv, p) -> new LockedChestMenu(id, playerInv, ensureRows(snap, 6), 6),
                title
        ));

        return 1;
    }

    /**
     * Menu is VIEW-ONLY.
     * We do NOT clear carried stack (cursor item), we just block every interaction
     * and resync the container so the client never "moves" anything.
     */
    private static final class LockedChestMenu extends ChestMenu {

        LockedChestMenu(int containerId, Inventory playerInv, Container snap, int rows) {
            super(MenuType.GENERIC_9x6, containerId, playerInv, snap, rows);
        }

        @Override
        public void clicked(int slotId, int button, @NotNull ClickType clickType, @NotNull Player player) {
            // Eat ALL interactions; do not call super.
            // Re-sync server -> client so ghost moves get reverted visually.
            this.broadcastChanges();
        }

        @Override
        public boolean canDragTo(@NotNull Slot slot) {
            return false;
        }

        @Override
        public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean canTakeItemForPickAll(@NotNull ItemStack stack, @NotNull Slot slot) {
            return false;
        }
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

    private static ItemStack[] snapshotStacks(ServerPlayer target) {
        ItemStack[] out = new ItemStack[41];
        java.util.Arrays.fill(out, ItemStack.EMPTY);

        var inv = target.getInventory();

        // main 0..35
        for (int i = 0; i < 36; i++) out[i] = inv.getItem(i).copy();

        // armor (keep your chosen order)
        for (int i = 0; i < 4; i++) out[36 + i] = inv.armor.get(i).copy();

        // offhand
        out[40] = inv.offhand.get(0).copy();

        return out;
    }

}
