package com.alphine.mysticessentials.vault;

import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.util.ItemUtil;
import com.alphine.mysticessentials.util.MessagesUtil;
import com.alphine.mysticessentials.util.StackTextUtil;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class VaultSelectorUi {

    private final VaultService vaults;
    private final VaultOpen vaultOpen;

    public VaultSelectorUi(VaultService vaults, VaultOpen vaultOpen) {
        this.vaults = vaults;
        this.vaultOpen = vaultOpen;
    }

    public void open(ServerPlayer viewer, UUID targetOwner) {
        if (!MEConfig.INSTANCE.features.enableVaultSystem) return;
        if (!MEConfig.INSTANCE.vaults.enabled) return;
        if (!VaultPerms.canUse(viewer)) return;

        boolean others = !viewer.getUUID().equals(targetOwner);
        if (others && !VaultPerms.canOpenOthers(viewer)) return;

        viewer.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (id, inv, p) -> new VaultSelectorMenu(id, inv, viewer, targetOwner, vaults, vaultOpen, 0),
                MessagesUtil.msg("vault.ui.selector.title")
        ));
    }

    public static int estimateUsedSlots(VaultProfile profile, int index, int available) {
        var items = profile.itemsByIndex.get(index);
        if (items == null) return 0;

        int used = 0;
        int limit = Math.min(items.size(), available);
        for (int i = 0; i < limit; i++) {
            ItemStack it = items.get(i);
            if (it != null && !it.isEmpty()) used++;
        }
        return used;
    }

    public static void populatePaged(net.minecraft.world.Container container,
                                     ServerPlayer viewer,
                                     VaultProfile profile,
                                     VaultService vaults,
                                     int page) {

        for (int i = 0; i < 54; i++) container.setItem(i, ItemStack.EMPTY);

        int maxVaults = VaultPerms.resolveMaxVaults(viewer);
        int startIndex = (Math.max(0, page) * VaultSelectorMenu.PAGE_SIZE) + 1;

        for (int slot = 0; slot < VaultSelectorMenu.PAGE_SIZE; slot++) {
            int vaultIndex = startIndex + slot;

            if (vaultIndex > maxVaults) {
                ItemStack locked = ItemUtil.fromId(MEConfig.INSTANCE.vaults.lockedItem, 1);
                StackTextUtil.setName(locked, MessagesUtil.msg("vault.ui.selector.locked.name"));
                // no lore for locked
                container.setItem(slot, locked);
                continue;
            }

            VaultMeta meta = vaults.meta(profile, vaultIndex);

            String itemId = vaults.resolveDisplayItemId(meta);
            if (!VaultPerms.canUseDisplayItem(viewer, itemId)) itemId = MEConfig.INSTANCE.vaults.defaultDisplayItem;

            ItemStack icon = ItemUtil.fromId(itemId, 1);

            String base = vaults.resolveBaseName(meta);
            StackTextUtil.setName(icon, MessagesUtil.msg("vault.ui.selector.vault.name", Map.of(
                    "name", base,
                    "index", vaultIndex
            )));

            int rows = VaultPerms.resolveVaultRows(viewer);
            int available = rows * 9;
            int used = estimateUsedSlots(profile, vaultIndex, available);

            StackTextUtil.setLore(icon, List.of(MessagesUtil.msg("vault.ui.selector.vault.lore", Map.of(
                    "used", used,
                    "available", available
            ))));

            container.setItem(slot, icon);
        }
    }
}
