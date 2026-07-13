package org.hyzionstudios.mysticessentials.modules.playervaults.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A single numbered vault owned by one player: its cosmetic {@link VaultMetadata},
 * its allowed row count, and the flat list of stored {@link VaultItemStack items}.
 *
 * <p><b>Versioning is the anti-dupe backbone.</b> {@link #version} increments on
 * every accepted save. A save carries the version the editor captured when the
 * vault was opened; if it no longer matches the stored version, another writer
 * won the race and the stale save is rejected (see the storage/service layer).
 * Older cached data therefore can never clobber newer stored data.</p>
 *
 * <p>{@link #rows} records the row count the vault was <i>last saved</i> with.
 * Because items are addressed by absolute slot, a permission downgrade simply
 * makes high-slot items unreachable ("overflow") without deleting them — they
 * stay in {@link #items} until permissions are restored or an admin recovers
 * them.</p>
 */
public final class PlayerVault {

    /** Schema version of this persisted record, for future migrations. */
    public int schemaVersion = 1;

    public String ownerUuid;
    public int vaultNumber;

    public VaultMetadata metadata = new VaultMetadata();

    /** Row count this vault was last persisted with (max slot = rows * slotsPerRow). */
    public int rows;

    /** Occupied slots only; empty slots are absent. */
    public List<VaultItemStack> items = new ArrayList<>();

    /** Monotonic optimistic-concurrency version; bumped on every accepted save. */
    public long version;

    public long createdAt;
    public long updatedAt;
    public long lastOpenedAt;
    public String lastOpenedServer;

    public PlayerVault() {
    }

    public PlayerVault(String ownerUuid, int vaultNumber, int rows) {
        this.ownerUuid = ownerUuid;
        this.vaultNumber = vaultNumber;
        this.rows = rows;
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** @return the highest slot index (exclusive) reachable within {@code allowedRows}. */
    public static int slotCeiling(int allowedRows, int slotsPerRow) {
        return Math.max(0, allowedRows) * Math.max(1, slotsPerRow);
    }

    /** @return the number of occupied slots, including overflow beyond the current row limit. */
    public int totalItemCount() {
        return items.size();
    }

    /** @return the number of occupied slots reachable within {@code allowedRows}. */
    public int accessibleItemCount(int allowedRows, int slotsPerRow) {
        int ceiling = slotCeiling(allowedRows, slotsPerRow);
        int count = 0;
        for (VaultItemStack item : items) {
            if (item.slot < ceiling) {
                count++;
            }
        }
        return count;
    }

    /** @return {@code true} if any occupied slot sits beyond {@code allowedRows} (hidden overflow). */
    public boolean hasOverflow(int allowedRows, int slotsPerRow) {
        int ceiling = slotCeiling(allowedRows, slotsPerRow);
        for (VaultItemStack item : items) {
            if (item.slot >= ceiling) {
                return true;
            }
        }
        return false;
    }

    /** Deep-copies this vault (used for backups and conflict snapshots). */
    public PlayerVault copy() {
        PlayerVault copy = new PlayerVault();
        copy.schemaVersion = schemaVersion;
        copy.ownerUuid = ownerUuid;
        copy.vaultNumber = vaultNumber;
        copy.metadata = metadata == null ? new VaultMetadata() : metadata.copy();
        copy.rows = rows;
        copy.version = version;
        copy.createdAt = createdAt;
        copy.updatedAt = updatedAt;
        copy.lastOpenedAt = lastOpenedAt;
        copy.lastOpenedServer = lastOpenedServer;
        copy.items = new ArrayList<>(items.size());
        for (VaultItemStack item : items) {
            copy.items.add(item.copy());
        }
        return copy;
    }
}
