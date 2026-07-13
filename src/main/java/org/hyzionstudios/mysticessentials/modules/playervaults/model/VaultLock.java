package org.hyzionstudios.mysticessentials.modules.playervaults.model;

import java.util.UUID;

/**
 * The payload of a distributed vault edit lock. Serialized to JSON and stored as
 * the value of the Redis key
 * {@code mysticessentials:vaults:lock:<target_uuid>:<vault_number>}.
 *
 * <p>The {@link #lockToken} is a strong random string generated when the lock is
 * taken and kept only by the acquiring server/session. Renewal and release are
 * gated on the token still matching the stored value (compare-and-set via Lua in
 * {@code RedisBridge}), so a lock that expired by TTL and was re-acquired by a
 * different server can never be renewed or released by the previous holder — the
 * core protection against a crashed/slow server clobbering a fresh editor.</p>
 */
public final class VaultLock {

    public String ownerServerId;
    public String viewerUuid;
    public String targetUuid;
    public int vaultNumber;
    /** Open mode name (see {@code VaultOpenMode}); informational for admin displays. */
    public String mode;
    public long createdAt;
    public long expiresAt;
    public String lockToken;

    public VaultLock() {
    }

    public VaultLock(String ownerServerId, UUID viewerUuid, UUID targetUuid, int vaultNumber,
            String mode, long ttlSeconds, String lockToken) {
        this.ownerServerId = ownerServerId;
        this.viewerUuid = viewerUuid == null ? null : viewerUuid.toString();
        this.targetUuid = targetUuid.toString();
        this.vaultNumber = vaultNumber;
        this.mode = mode;
        this.createdAt = System.currentTimeMillis();
        this.expiresAt = this.createdAt + ttlSeconds * 1000L;
        this.lockToken = lockToken;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }

    /** A fresh, unguessable lock token. */
    public static String newToken() {
        return UUID.randomUUID().toString().replace("-", "")
                + Long.toHexString(System.nanoTime());
    }
}
