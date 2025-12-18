package com.alphine.mysticessentials.vault;

import java.util.UUID;

public interface VaultStore {
    VaultProfile load(UUID playerId);
    void save(VaultProfile profile);

    /**
     * Reset a specific vault for a player.
     * @param clearItems clears all items
     * @param resetMeta resets name + icon to defaults
     */
    void resetVault(UUID playerId, int vaultIndex, boolean clearItems, boolean resetMeta);

    /** Reset all vaults for a player (may delete file). */
    void resetAll(UUID playerId);
}
