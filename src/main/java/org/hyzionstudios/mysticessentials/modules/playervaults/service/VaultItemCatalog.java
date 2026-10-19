package org.hyzionstudios.mysticessentials.modules.playervaults.service;

import java.util.List;

import org.hyzionstudios.mysticessentials.platform.ui.ItemCatalog;

/**
 * The vault editor's icon picker over the server's item registry. The search
 * itself lives in {@link ItemCatalog}, which the LootBox editors share; this
 * class keeps the vault's call sites and behaviour unchanged (no category
 * filter: any valid in-game item can be a vault icon).
 */
public final class VaultItemCatalog {

    private VaultItemCatalog() {
    }

    /** @return all item ids whose id contains {@code query} (case-insensitive), sorted, capped to {@code limit}. */
    public static List<String> search(String query, int limit) {
        return ItemCatalog.search(query, limit);
    }

    /** @return {@code true} if {@code itemId} is a known item in the registry. */
    public static boolean exists(String itemId) {
        return ItemCatalog.exists(itemId);
    }
}
