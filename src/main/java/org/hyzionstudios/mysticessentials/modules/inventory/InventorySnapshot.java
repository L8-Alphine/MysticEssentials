package org.hyzionstudios.mysticessentials.modules.inventory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A point-in-time copy of a player's inventory, serialized to storage by the
 * Inventory module. Sections mirror the Hytale {@code Inventory} containers
 * (hotbar, storage, armor, utility, tools, backpack); only occupied slots are
 * recorded. Item metadata is kept as the BSON document's JSON string so any
 * item state survives the round trip.
 */
public final class InventorySnapshot {

    public String id;
    /** ISO-8601 capture time. */
    public String timestamp;
    /** What triggered the snapshot: Join, Leave, Death, Timed, Manual, PreRestore, PreClear. */
    public String cause;
    /** Section name &rarr; occupied slots. */
    public Map<String, List<SlotItem>> sections = new LinkedHashMap<>();

    public static final class SlotItem {
        public int slot;
        public String itemId;
        public int quantity;
        public double durability;
        public double maxDurability;
        /** JSON form of the item's BSON metadata; null when absent. */
        public String metadata;
    }

    public static InventorySnapshot create(String cause) {
        InventorySnapshot snapshot = new InventorySnapshot();
        snapshot.id = UUID.randomUUID().toString();
        snapshot.timestamp = Instant.now().toString();
        snapshot.cause = cause;
        return snapshot;
    }

    public int itemCount() {
        int count = 0;
        for (List<SlotItem> slots : sections.values()) {
            count += slots.size();
        }
        return count;
    }
}
