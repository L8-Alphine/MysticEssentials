package org.hyzionstudios.mysticessentials.modules.playervaults.service;

import java.util.List;
import java.util.Locale;

import org.hyzionstudios.mysticessentials.modules.playervaults.config.PlayerVaultConfig;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.VaultItemStack;

import com.hypixel.hytale.server.core.inventory.ItemStack;

/**
 * Converts between live Hytale {@link ItemStack}s and the persistence-neutral
 * {@link VaultItemStack}, and enforces the item blacklist. Full BSON metadata is
 * preserved as its JSON string on the way out and rebuilt on the way in, so no
 * item state is lost or fabricated — a prerequisite for dupe-free round trips.
 */
public final class VaultItemCodec {

    private VaultItemCodec() {
    }

    /** Serializes an occupied slot's item; returns {@code null} for empty slots. */
    public static VaultItemStack toStored(int slot, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        var metadata = stack.getMetadata();
        String json = metadata == null || metadata.isEmpty() ? null : metadata.toJson();
        return new VaultItemStack(slot, stack.getItemId(), stack.getQuantity(),
                stack.getDurability(), stack.getMaxDurability(), json);
    }

    /** Rebuilds a live item from stored data, restoring full metadata. */
    public static ItemStack toLive(VaultItemStack stored) {
        org.bson.BsonDocument metadata = stored.metadata == null || stored.metadata.isBlank()
                ? new org.bson.BsonDocument()
                : org.bson.BsonDocument.parse(stored.metadata);
        return new ItemStack(stored.itemId, Math.max(1, stored.quantity),
                stored.durability, stored.maxDurability, metadata);
    }

    /** @return {@code true} if this item id may not be stored in a vault. */
    public static boolean isBlockedForStorage(String itemId, PlayerVaultConfig config) {
        if (!config.preventStorageOfBlacklistedItems || itemId == null) {
            return false;
        }
        return containsIgnoreCase(config.blockedItemIds, itemId);
    }

    /** @return {@code true} if this item id may not be used as a vault icon. */
    public static boolean isBlockedForIcon(String itemId, PlayerVaultConfig config) {
        if (itemId == null || itemId.isBlank()) {
            return true;
        }
        if (!config.allowAnyItemAsIcon) {
            // Restrictive mode: only items NOT on the icon blacklist are allowed, and
            // icons must also never be a storage-blocked item.
            return containsIgnoreCase(config.blockedIconItemIds, itemId)
                    || isBlockedForStorage(itemId, config);
        }
        return containsIgnoreCase(config.blockedIconItemIds, itemId);
    }

    private static boolean containsIgnoreCase(List<String> values, String value) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        String needle = value.toLowerCase(Locale.ROOT);
        for (String candidate : values) {
            if (candidate != null && candidate.toLowerCase(Locale.ROOT).equals(needle)) {
                return true;
            }
        }
        return false;
    }
}
