package org.hyzionstudios.mysticessentials.modules.playervaults.api.results;

import org.hyzionstudios.mysticessentials.modules.playervaults.model.PlayerVault;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.VaultLock;

/**
 * Outcome of an attempt to open a vault (permission + lock + load resolved).
 * {@link Status#OPENED} carries the loaded {@link #vault}; failure statuses drive
 * the clean player-facing message shown instead of the UI.
 */
public record VaultOpenResult(Status status, PlayerVault vault, VaultLock holder) {

    public enum Status {
        OPENED,
        NO_PERMISSION,
        INVALID_NUMBER,
        LOCKED_ELSEWHERE,
        LOCKED_LOCALLY,
        STORAGE_UNAVAILABLE,
        DISABLED
    }

    public static VaultOpenResult opened(PlayerVault vault) {
        return new VaultOpenResult(Status.OPENED, vault, null);
    }

    public static VaultOpenResult of(Status status) {
        return new VaultOpenResult(status, null, null);
    }

    public static VaultOpenResult lockedElsewhere(VaultLock holder) {
        return new VaultOpenResult(Status.LOCKED_ELSEWHERE, null, holder);
    }

    public boolean opened() {
        return status == Status.OPENED;
    }
}
