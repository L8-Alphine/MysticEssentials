package com.alphine.mysticessentials.vault;

import net.minecraft.world.item.ItemStack;

public final class VaultSelectorUiUsed {
    private VaultSelectorUiUsed() {}

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
}
