package org.hyzionstudios.mysticessentials.modules.playervaults.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;

/**
 * Read-only view over the server's item registry, used by the vault editor's
 * icon picker. Item ids are the keys of {@link Item#getAssetMap()} — the same
 * catalogue the game builds every item from — so any valid in-game item can be
 * chosen as a vault icon.
 *
 * <p>Every call is defensive: if the asset store is not ready (or the API shape
 * changes) it returns empty rather than throwing, so the editor still opens.</p>
 */
public final class VaultItemCatalog {

    private VaultItemCatalog() {
    }

    /** @return all item ids whose id contains {@code query} (case-insensitive), sorted, capped to {@code limit}. */
    public static List<String> search(String query, int limit) {
        Map<String, Item> catalogue = catalogue();
        if (catalogue.isEmpty()) {
            return List.of();
        }
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String id : catalogue.keySet()) {
            if (id == null) {
                continue;
            }
            if (needle.isEmpty() || id.toLowerCase(Locale.ROOT).contains(needle)) {
                matches.add(id);
            }
        }
        matches.sort(String.CASE_INSENSITIVE_ORDER);
        int cap = Math.max(1, limit);
        return matches.size() > cap ? new ArrayList<>(matches.subList(0, cap)) : matches;
    }

    /** @return {@code true} if {@code itemId} is a known item in the registry. */
    public static boolean exists(String itemId) {
        return itemId != null && !itemId.isBlank() && catalogue().containsKey(itemId);
    }

    private static Map<String, Item> catalogue() {
        try {
            Map<String, Item> map = Item.getAssetMap().getAssetMap();
            return map == null ? Map.of() : map;
        } catch (Throwable t) {
            return Map.of();
        }
    }
}
