package org.hyzionstudios.mysticessentials.modules.playervaults.model;

import java.util.UUID;

/**
 * Records a rejected save caused by a version mismatch. When an editor tries to
 * save with a stale expected version, the current stored vault stays the source
 * of truth and the editor's <i>attempted</i> state is preserved here instead of
 * being discarded, so staff can later inspect or selectively restore items.
 *
 * <p>This is what makes conflict handling non-lossy: the loser of a save race
 * never overwrites the winner, but the loser's work is not thrown away either.</p>
 */
public final class VaultConflictSnapshot {

    public String conflictId;
    public String ownerUuid;
    public int vaultNumber;
    /** Who attempted the losing save (UUID string), or {@code "system"}. */
    public String actorUuid;
    public String sourceServerId;
    /** Version the editor expected (captured at open). */
    public long expectedVersion;
    /** Version actually found in storage at save time. */
    public long latestVersion;
    public long createdAt;
    /** The state the editor tried to save (deep copy). */
    public PlayerVault attemptedState;

    public VaultConflictSnapshot() {
    }

    public static VaultConflictSnapshot of(PlayerVault attempted, long expectedVersion,
            long latestVersion, String actorUuid, String serverId) {
        VaultConflictSnapshot snapshot = new VaultConflictSnapshot();
        snapshot.conflictId = UUID.randomUUID().toString();
        snapshot.ownerUuid = attempted.ownerUuid;
        snapshot.vaultNumber = attempted.vaultNumber;
        snapshot.actorUuid = actorUuid == null ? "system" : actorUuid;
        snapshot.sourceServerId = serverId;
        snapshot.expectedVersion = expectedVersion;
        snapshot.latestVersion = latestVersion;
        snapshot.createdAt = System.currentTimeMillis();
        snapshot.attemptedState = attempted.copy();
        return snapshot;
    }
}
