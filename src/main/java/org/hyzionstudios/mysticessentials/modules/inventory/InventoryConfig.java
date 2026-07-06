package org.hyzionstudios.mysticessentials.modules.inventory;

/**
 * Persisted settings for {@code modules/inventory/config.json}. Snapshots are
 * taken on join/leave/death (each toggleable) and optionally on a timer, and
 * are capped per player (oldest dropped first).
 */
public final class InventoryConfig {

    public int configVersion = 1;

    public boolean snapshotOnJoin = true;
    public boolean snapshotOnLeave = true;
    public boolean snapshotOnDeath = true;

    /** Minutes between automatic snapshots of online players; 0 disables. */
    public int timedSnapshotMinutes = 0;

    /** Maximum stored snapshots per player; oldest are dropped first. */
    public int maxSnapshotsPerPlayer = 24;
}
