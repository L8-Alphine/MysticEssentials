package org.hyzionstudios.mysticessentials.platform.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;

/**
 * Read-only view over the server's item registry, shared by the item pickers
 * (the vault editor's icon search and the LootBox editors). Item ids are the
 * keys of {@link Item#getAssetMap()}: the same catalogue the game builds every
 * item from, so any valid in-game item can be chosen.
 *
 * <p>Items carry creative-library categories of the form {@code Root.Child}
 * ({@code Items.Weapons}, {@code Furniture.Beds}); a category filter keeps the
 * items at or below it, so {@code Furniture} and {@code Furniture.} both match
 * {@code Furniture.Beds}.</p>
 *
 * <p>Every call is defensive: if the asset store is not ready (or the API shape
 * changes) it returns empty rather than throwing, so the picker still opens.</p>
 */
public final class ItemCatalog {

    private ItemCatalog() {
    }

    /** @return all item ids whose id contains {@code query} (case-insensitive), sorted, capped to {@code limit}. */
    public static List<String> search(String query, int limit) {
        return search(query, null, limit);
    }

    /**
     * Like {@link #search(String, int)}, keeping only items in {@code category}
     * (see {@link #inCategory}); a null or blank category keeps every item.
     */
    public static List<String> search(String query, String category, int limit) {
        Map<String, Item> catalogue = catalogue();
        if (catalogue.isEmpty()) {
            return List.of();
        }
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        boolean filtered = category != null && !category.isBlank();
        List<String> matches = new ArrayList<>();
        for (Map.Entry<String, Item> entry : catalogue.entrySet()) {
            String id = entry.getKey();
            if (id == null) {
                continue;
            }
            if (!needle.isEmpty() && !id.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            if (filtered && !inCategory(categories(entry.getValue()), category)) {
                continue;
            }
            matches.add(id);
        }
        matches.sort(String.CASE_INSENSITIVE_ORDER);
        int cap = Math.max(1, limit);
        return matches.size() > cap ? new ArrayList<>(matches.subList(0, cap)) : matches;
    }

    /** @return {@code true} if {@code itemId} is a known item in the registry. */
    public static boolean exists(String itemId) {
        return itemId != null && !itemId.isBlank() && catalogue().containsKey(itemId);
    }

    /** @return the item's categories ({@code Items.Weapons}), empty when it has none or is unknown. */
    public static List<String> categories(String itemId) {
        return itemId == null ? List.of() : categories(catalogue().get(itemId));
    }

    /**
     * Whether one of {@code categories} is {@code filter} or sits below it,
     * ignoring case and a trailing dot on the filter. Engine-free.
     */
    public static boolean inCategory(Collection<String> categories, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        String root = filter.trim().toLowerCase(Locale.ROOT);
        while (root.endsWith(".")) {
            root = root.substring(0, root.length() - 1);
        }
        if (root.isEmpty()) {
            return true;
        }
        for (String category : categories) {
            if (category == null) {
                continue;
            }
            String value = category.toLowerCase(Locale.ROOT);
            if (value.equals(root) || value.startsWith(root + ".")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> categories(Item item) {
        try {
            String[] categories = item == null ? null : item.getCategories();
            return categories == null ? List.of() : Arrays.asList(categories);
        } catch (Throwable t) {
            return List.of();
        }
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
