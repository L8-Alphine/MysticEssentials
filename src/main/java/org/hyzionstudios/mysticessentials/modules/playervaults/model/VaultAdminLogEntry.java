package org.hyzionstudios.mysticessentials.modules.playervaults.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One audit-log record of a staff action against a player's vault. Written for
 * admin opens, edits, restores, exports, and force unlocks. Kept per target
 * player and capped by {@code admin.maxLogEntriesPerPlayer}. Sensitive
 * serialized item data is intentionally <b>not</b> stored here — only counts and
 * summary fields — so logs can be shown in chat safely.
 */
public final class VaultAdminLogEntry {

    public String logId;
    public String actorUuid;
    public String actorName;
    public String targetUuid;
    public int vaultNumber;
    /** ADMIN_OPEN, ADMIN_EDIT, ADMIN_UNLOCK, ADMIN_RESTORE, ADMIN_EXPORT. */
    public String action;
    public String serverId;
    public long timestamp;
    /** Small, non-sensitive key/value details (mode, onlineTarget, item-count deltas). */
    public Map<String, String> details = new LinkedHashMap<>();

    public VaultAdminLogEntry() {
    }

    public static VaultAdminLogEntry create(UUID actor, String actorName, UUID target,
            int vaultNumber, String action, String serverId) {
        VaultAdminLogEntry entry = new VaultAdminLogEntry();
        entry.logId = UUID.randomUUID().toString();
        entry.actorUuid = actor == null ? "system" : actor.toString();
        entry.actorName = actorName == null ? "System" : actorName;
        entry.targetUuid = target.toString();
        entry.vaultNumber = vaultNumber;
        entry.action = action;
        entry.serverId = serverId;
        entry.timestamp = System.currentTimeMillis();
        return entry;
    }

    public VaultAdminLogEntry with(String key, String value) {
        details.put(key, value);
        return this;
    }
}
