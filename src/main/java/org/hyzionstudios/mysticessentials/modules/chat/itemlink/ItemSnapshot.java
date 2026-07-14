package org.hyzionstudios.mysticessentials.modules.chat.itemlink;

import java.util.List;

/**
 * An immutable, read-only snapshot of a shared item taken at the moment the
 * sender typed the item tag. It intentionally does <b>not</b> retain a live
 * {@code ItemStack} or any inventory-compatible copy — the details UI renders
 * from these plain fields only, so the sender changing or dropping the item
 * later cannot change what recipients see, and inspection can never mint a
 * duplicate item (design goal: "completely read-only").
 *
 * <p>Private metadata is dropped during capture; only the fields below survive.</p>
 */
public final class ItemSnapshot {

    /** One rendered statistic line in the details UI (e.g. "Damage" / "42-58"). */
    public record Stat(String label, String value) {
    }

    public final String id;
    public final String itemId;
    public final int quantity;

    /** Client-translatable display name key, or {@code null} when unresolved. */
    public final String translationKey;
    /** Best-effort custom name pulled from item metadata, or {@code null}. */
    public final String customName;
    /** Client-translatable description/lore key, or {@code null}. */
    public final String descriptionKey;

    public final int qualityIndex;
    public final String rarityName;
    public final String rarityColor;
    public final int itemLevel;
    public final String category;
    public final String subCategory;

    public final double durability;
    public final double maxDurability;
    public final boolean unbreakable;

    public final List<Stat> stats;

    public final String sharedByUuid;
    public final String sharedByName;
    public final String channelName;
    public final String worldName;
    public final long capturedAtEpochMs;

    ItemSnapshot(String id, String itemId, int quantity, String translationKey, String customName,
            String descriptionKey, int qualityIndex, String rarityName, String rarityColor,
            int itemLevel, String category, String subCategory, double durability, double maxDurability,
            boolean unbreakable, List<Stat> stats, String sharedByUuid, String sharedByName,
            String channelName, String worldName, long capturedAtEpochMs) {
        this.id = id;
        this.itemId = itemId;
        this.quantity = quantity;
        this.translationKey = translationKey;
        this.customName = customName;
        this.descriptionKey = descriptionKey;
        this.qualityIndex = qualityIndex;
        this.rarityName = rarityName;
        this.rarityColor = rarityColor;
        this.itemLevel = itemLevel;
        this.category = category;
        this.subCategory = subCategory;
        this.durability = durability;
        this.maxDurability = maxDurability;
        this.unbreakable = unbreakable;
        this.stats = stats == null ? List.of() : List.copyOf(stats);
        this.sharedByUuid = sharedByUuid;
        this.sharedByName = sharedByName;
        this.channelName = channelName;
        this.worldName = worldName;
        this.capturedAtEpochMs = capturedAtEpochMs;
    }

    /** A short, safe label for the chat name and history rows (custom name, else prettified id). */
    public String plainName() {
        if (customName != null && !customName.isBlank()) {
            return customName;
        }
        return prettifyId(itemId);
    }

    /** Whether this item exposes durability worth showing (breakable, non-zero max). */
    public boolean hasDurability() {
        return !unbreakable && maxDurability > 0;
    }

    static String prettifyId(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return "Unknown Item";
        }
        String base = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        String[] parts = base.split("[_\\s]+");
        StringBuilder out = new StringBuilder(base.length());
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                out.append(part.substring(1));
            }
        }
        return out.length() == 0 ? base : out.toString();
    }
}
