package com.alphine.mysticessentials.vault;

import net.minecraft.world.item.ItemStack;

import java.util.*;

public final class VaultProfile {
    public final UUID owner;

    /** vaultIndex (1-based) -> meta */
    public final Map<Integer, VaultMeta> metaByIndex = new HashMap<>();

    /** vaultIndex (1-based) -> slots list (index = slot). */
    public final Map<Integer, List<ItemStack>> itemsByIndex = new HashMap<>();

    public VaultProfile(UUID owner) {
        this.owner = owner;
    }
}
