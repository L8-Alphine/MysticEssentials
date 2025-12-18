package com.alphine.mysticessentials.vault;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.util.ItemUtil;
import com.alphine.mysticessentials.util.MessagesUtil;
import com.alphine.mysticessentials.util.StackTextUtil;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class VaultSettingsMenu extends ChestMenu {

    private final ServerPlayer viewer;
    private final UUID targetOwner;
    private final int vaultIndex;
    private final int returnPage;

    private final VaultService vaults;
    private final VaultOpen vaultOpen;

    // Slots
    private static final int PREVIEW = 13;

    private static final int RENAME = 29;
    private static final int SET_ICON = 31;
    private static final int RESET_VAULT = 33;

    private static final int BACK = 45;
    private static final int RESET_ALL = 47; // admin only
    private static final int CLOSE = 53;

    public VaultSettingsMenu(int containerId,
                             Inventory inv,
                             ServerPlayer viewer,
                             UUID targetOwner,
                             int vaultIndex,
                             int returnPage) {
        super(MenuType.GENERIC_9x6, containerId, inv, new SimpleContainer(54), 6);
        this.viewer = viewer;
        this.targetOwner = targetOwner;
        this.vaultIndex = vaultIndex;
        this.returnPage = Math.max(0, returnPage);

        var common = MysticEssentialsCommon.get();
        this.vaults = common.vaultService;
        this.vaultOpen = common.vaultOpen;

        refresh();
    }

    private void refresh() {
        // Clear
        for (int i = 0; i < 54; i++) this.getContainer().setItem(i, ItemStack.EMPTY);

        // Filler
        ItemStack filler = ItemUtil.fromId("minecraft:gray_stained_glass_pane", 1);
        StackTextUtil.setName(filler, MessagesUtil.msg("vault.ui.common.filler"));
        for (int i = 0; i < 54; i++) {
            if (i == PREVIEW || i == RENAME || i == SET_ICON || i == RESET_VAULT || i == BACK || i == RESET_ALL || i == CLOSE) continue;
            this.getContainer().setItem(i, filler.copy());
        }

        // Preview (vault icon + name + used/available)
        VaultProfile profile = vaults.profile(targetOwner);
        VaultMeta meta = vaults.meta(profile, vaultIndex);

        String base = vaults.resolveBaseName(meta);
        String itemId = vaults.resolveDisplayItemId(meta);
        if (!VaultPerms.canUseDisplayItem(viewer, itemId)) itemId = MEConfig.INSTANCE.vaults.defaultDisplayItem;

        ItemStack preview = ItemUtil.fromId(itemId, 1);

        StackTextUtil.setName(preview, MessagesUtil.msg("vault.ui.selector.vault.name", Map.of(
                "name", base,
                "index", vaultIndex
        )));

        int rows = VaultPerms.resolveVaultRows(viewer);
        int available = rows * 9;
        int used = VaultSelectorUi.estimateUsedSlots(profile, vaultIndex, available);

        StackTextUtil.setLore(preview, List.of(
                MessagesUtil.msg("vault.ui.settings.preview.slots", Map.of("used", used, "available", available)),
                MessagesUtil.msg("vault.ui.common.blank"),
                MessagesUtil.msg("vault.ui.settings.preview.left"),
                MessagesUtil.msg("vault.ui.settings.preview.right")
        ));
        this.getContainer().setItem(PREVIEW, preview);

        // Rename
        ItemStack rename = ItemUtil.fromId("minecraft:name_tag", 1);
        StackTextUtil.setName(rename, MessagesUtil.msg("vault.ui.settings.rename.name"));
        StackTextUtil.setLore(rename, List.of(MessagesUtil.msg("vault.ui.settings.rename.lore")));
        this.getContainer().setItem(RENAME, rename);

        // Set Icon
        ItemStack icon = ItemUtil.fromId("minecraft:item_frame", 1);
        StackTextUtil.setName(icon, MessagesUtil.msg("vault.ui.settings.icon.name"));
        StackTextUtil.setLore(icon, List.of(MessagesUtil.msg("vault.ui.settings.icon.lore")));
        this.getContainer().setItem(SET_ICON, icon);

        // Reset vault
        ItemStack reset = ItemUtil.fromId("minecraft:lava_bucket", 1);
        StackTextUtil.setName(reset, MessagesUtil.msg("vault.ui.settings.reset.name"));
        StackTextUtil.setLore(reset, List.of(
                MessagesUtil.msg("vault.ui.settings.reset.lore1"),
                MessagesUtil.msg("vault.ui.settings.reset.lore2")
        ));
        this.getContainer().setItem(RESET_VAULT, reset);

        // Back
        ItemStack back = ItemUtil.fromId("minecraft:arrow", 1);
        StackTextUtil.setName(back, MessagesUtil.msg("vault.ui.common.back"));
        this.getContainer().setItem(BACK, back);

        // Reset All (admin only)
        if (viewer.hasPermissions(2) || VaultPerms.canResetAll(viewer)) {
            ItemStack resetAll = ItemUtil.fromId("minecraft:tnt", 1);
            StackTextUtil.setName(resetAll, MessagesUtil.msg("vault.ui.settings.resetall.name"));
            StackTextUtil.setLore(resetAll, List.of(
                    MessagesUtil.msg("vault.ui.settings.resetall.lore1"),
                    MessagesUtil.msg("vault.ui.settings.resetall.lore2")
            ));
            this.getContainer().setItem(RESET_ALL, resetAll);
        }

        // Close
        ItemStack close = ItemUtil.fromId("minecraft:barrier", 1);
        StackTextUtil.setName(close, MessagesUtil.msg("vault.ui.common.close"));
        this.getContainer().setItem(CLOSE, close);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!(player instanceof ServerPlayer sp)) return;

        // Block most inventory manipulation in this GUI
        if (clickType == ClickType.QUICK_MOVE
                || clickType == ClickType.SWAP
                || clickType == ClickType.CLONE
                || clickType == ClickType.THROW
                || clickType == ClickType.PICKUP_ALL) {
            return;
        }

        boolean leftClick = (clickType == ClickType.PICKUP && button == 0);
        if (!leftClick) return;

        if (slotId == CLOSE) {
            sp.closeContainer();
            return;
        }

        if (slotId == BACK) {
            sp.openMenu(new net.minecraft.world.SimpleMenuProvider(
                    (id, inv, p) -> new VaultSelectorMenu(id, inv, sp, targetOwner, vaults, vaultOpen, returnPage),
                    MessagesUtil.msg("vault.ui.selector.title")
            ));
            return;
        }

        if (slotId == PREVIEW) {
            vaultOpen.openVault(sp, targetOwner, vaultIndex);
            return;
        }

        if (slotId == RENAME) {
            if (!VaultPerms.canRename(sp)) return;
            VaultRenameUi.open(sp, targetOwner, vaultIndex, returnPage);
            return;
        }

        if (slotId == SET_ICON) {
            ItemStack hand = sp.getMainHandItem();
            if (hand == null || hand.isEmpty()) return;

            String id = ItemUtil.getItemId(hand);
            if (!VaultPerms.canUseDisplayItem(sp, id)) return;

            VaultProfile profile = vaults.profile(targetOwner);
            VaultMeta meta = vaults.meta(profile, vaultIndex);
            meta.displayItemId = id;
            vaults.save(profile);

            refresh();
            return;
        }

        if (slotId == RESET_VAULT) {
            if (!VaultPerms.canReset(sp)) return;

            // Respect exempt when resetting others (check TARGET, not viewer)
            if (!sp.getUUID().equals(targetOwner) && VaultPerms.isResetExempt(sp)) return;

            vaults.resetVault(targetOwner, vaultIndex, true, true);
            refresh();
            return;
        }

        if (slotId == RESET_ALL) {
            if (!VaultPerms.canResetAll(sp)) return;

            vaults.resetAll(targetOwner);

            sp.openMenu(new net.minecraft.world.SimpleMenuProvider(
                    (id, inv, p) -> new VaultSelectorMenu(id, inv, sp, targetOwner, vaults, vaultOpen, 0),
                    MessagesUtil.msg("vault.ui.selector.title")
            ));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
