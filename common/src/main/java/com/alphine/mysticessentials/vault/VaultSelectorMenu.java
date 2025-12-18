package com.alphine.mysticessentials.vault;

import com.alphine.mysticessentials.util.ItemUtil;
import com.alphine.mysticessentials.util.StackTextUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

public final class VaultSelectorMenu extends ChestMenu {
    private final ServerPlayer viewer;
    private final UUID targetOwner;
    private final VaultService vaults;
    private final VaultOpen vaultOpen;

    private int page; // 0-based

    public static final int SIZE = 54;

    // 0..44 are vault buttons
    public static final int PAGE_SIZE = 45;

    // Nav row slots (45..53)
    public static final int NAV_ROW_START = 45;
    public static final int PREV_SLOT = 45;
    public static final int INFO_SLOT = 49;
    public static final int NEXT_SLOT = 51;
    public static final int CLOSE_SLOT = 53;

    public VaultSelectorMenu(int containerId,
                             Inventory inv,
                             ServerPlayer viewer,
                             UUID targetOwner,
                             VaultService vaults,
                             VaultOpen vaultOpen,
                             int page) {
        super(MenuType.GENERIC_9x6, containerId, inv, new SimpleContainer(SIZE), 6);
        this.viewer = viewer;
        this.targetOwner = targetOwner;
        this.vaults = vaults;
        this.vaultOpen = vaultOpen;
        this.page = Math.max(0, page);

        refresh();
    }

    private void refresh() {
        VaultProfile profile = vaults.profile(targetOwner);
        VaultSelectorUi.populatePaged(this.getContainer(), viewer, profile, vaults, page);

        // Nav filler (optional)
        for (int i = NAV_ROW_START; i < SIZE; i++) {
            if (i == PREV_SLOT || i == INFO_SLOT || i == NEXT_SLOT || i == CLOSE_SLOT) continue;
            ItemStack filler = ItemUtil.fromId("minecraft:gray_stained_glass_pane", 1);
            StackTextUtil.setName(filler, Component.literal(" "));
            this.getContainer().setItem(i, filler);
        }

        // Prev
        ItemStack prev = ItemUtil.fromId("minecraft:arrow", 1);
        StackTextUtil.setName(prev, Component.literal("« Previous Page"));
        this.getContainer().setItem(PREV_SLOT, prev);

        // Info
        int maxVaults = VaultPerms.resolveMaxVaults(viewer);
        int totalPages = Math.max(1, (int) Math.ceil(maxVaults / (double) PAGE_SIZE));
        int shownPage = Math.min(page + 1, totalPages);

        ItemStack info = ItemUtil.fromId("minecraft:paper", 1);
        StackTextUtil.setName(info, Component.literal("Page " + shownPage + " / " + totalPages));
        this.getContainer().setItem(INFO_SLOT, info);

        // Next
        ItemStack next = ItemUtil.fromId("minecraft:arrow", 1);
        StackTextUtil.setName(next, Component.literal("Next Page »"));
        this.getContainer().setItem(NEXT_SLOT, next);

        // Close
        ItemStack close = ItemUtil.fromId("minecraft:barrier", 1);
        StackTextUtil.setName(close, Component.literal("Close"));
        this.getContainer().setItem(CLOSE_SLOT, close);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!(player instanceof ServerPlayer sp)) return;

        // Hard block any item manipulation
        if (clickType == ClickType.QUICK_MOVE
                || clickType == ClickType.SWAP
                || clickType == ClickType.CLONE
                || clickType == ClickType.THROW
                || clickType == ClickType.PICKUP_ALL) {
            return;
        }

        // Nav row
        if (slotId == CLOSE_SLOT) {
            sp.closeContainer();
            return;
        }
        if (slotId == PREV_SLOT) {
            if (page > 0) {
                page--;
                refresh();
            }
            return;
        }
        if (slotId == NEXT_SLOT) {
            int maxVaults = VaultPerms.resolveMaxVaults(sp);
            int totalPages = Math.max(1, (int) Math.ceil(maxVaults / (double) PAGE_SIZE));
            if (page + 1 < totalPages) {
                page++;
                refresh();
            }
            return;
        }
        if (slotId >= NAV_ROW_START) {
            return; // ignore other nav slots
        }

        // Vault grid area
        if (slotId < 0 || slotId >= PAGE_SIZE) return;

        int vaultIndex = (page * PAGE_SIZE) + (slotId + 1);

        int maxVaults = VaultPerms.resolveMaxVaults(sp);
        if (vaultIndex > maxVaults) return; // locked

        boolean rightClick = (clickType == ClickType.PICKUP && button == 1);
        boolean leftClick  = (clickType == ClickType.PICKUP && button == 0);

        if (rightClick) {
            VaultSettingsUi.open(sp, targetOwner, vaultIndex, page);
            return;
        }

        if (leftClick) {
            vaultOpen.openVault(sp, targetOwner, vaultIndex);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
