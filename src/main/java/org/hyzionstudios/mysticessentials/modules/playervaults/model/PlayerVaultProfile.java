package org.hyzionstudios.mysticessentials.modules.playervaults.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lightweight per-player index of vault ownership. Loaded on demand (never
 * eagerly for every player on join, per the performance rules) and kept small so
 * it is cheap to cache: it records identity, timestamps, and which vault numbers
 * the player has actually created data for. Vault <i>contents</i> live in
 * separate {@link PlayerVault} documents and are lazy-loaded only when a specific
 * vault is opened.
 */
public final class PlayerVaultProfile {

    public String playerUuid;
    public String lastKnownName;
    public long createdAt;
    public long updatedAt;

    /** Vault numbers this player has ever materialized (has stored data for). */
    public List<Integer> vaultNumbers = new ArrayList<>();

    /** Bumped whenever a vault's metadata or membership changes (cache invalidation aid). */
    public int metadataVersion;

    public PlayerVaultProfile() {
    }

    public PlayerVaultProfile(UUID playerUuid, String lastKnownName) {
        this.playerUuid = playerUuid.toString();
        this.lastKnownName = lastKnownName;
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Records that {@code vaultNumber} now has data, bumping the metadata version if new. */
    public void trackVault(int vaultNumber) {
        if (!vaultNumbers.contains(vaultNumber)) {
            vaultNumbers.add(vaultNumber);
            metadataVersion++;
        }
        updatedAt = System.currentTimeMillis();
    }
}
