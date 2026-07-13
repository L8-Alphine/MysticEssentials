package org.hyzionstudios.mysticessentials.modules.playervaults.api.results;

import org.hyzionstudios.mysticessentials.modules.playervaults.model.PlayerVault;

/**
 * Outcome of a versioned save. {@link Status#SAVED} carries the persisted vault
 * (with its incremented version); {@link Status#CONFLICT} carries the newer
 * stored vault that was preserved instead of being overwritten, so the caller
 * can reload the UI from truth.
 */
public record VaultSaveResult(Status status, PlayerVault vault) {

    public enum Status {
        /** Save accepted; {@link #vault} is the new persisted state. */
        SAVED,
        /** Version mismatch; {@link #vault} is the newer stored state (a conflict snapshot was created). */
        CONFLICT,
        /** Storage backend error; nothing was written. */
        STORAGE_ERROR,
        /** The edit lock was no longer held by this session; save refused. */
        LOCK_LOST
    }

    public static VaultSaveResult saved(PlayerVault vault) {
        return new VaultSaveResult(Status.SAVED, vault);
    }

    public static VaultSaveResult conflict(PlayerVault latest) {
        return new VaultSaveResult(Status.CONFLICT, latest);
    }

    public static VaultSaveResult storageError() {
        return new VaultSaveResult(Status.STORAGE_ERROR, null);
    }

    public static VaultSaveResult lockLost() {
        return new VaultSaveResult(Status.LOCK_LOST, null);
    }

    public boolean saved() {
        return status == Status.SAVED;
    }
}
