package org.hyzionstudios.mysticessentials.modules.playervaults.event;

import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;
import org.hyzionstudios.mysticessentials.modules.playervaults.api.VaultOpenMode;

/**
 * Fired when staff opens another player's vault. Cancellable so integrations can
 * block sensitive access. Fires in addition to (before) {@link PlayerVaultOpenEvent}.
 */
public final class PlayerVaultAdminOpenEvent implements MysticEvent.Cancellable {

    private final UUID actorUuid;
    private final UUID targetUuid;
    private final int vaultNumber;
    private final VaultOpenMode mode;
    private final boolean onlineTarget;
    private boolean cancelled;

    public PlayerVaultAdminOpenEvent(UUID actorUuid, UUID targetUuid, int vaultNumber,
            VaultOpenMode mode, boolean onlineTarget) {
        this.actorUuid = actorUuid;
        this.targetUuid = targetUuid;
        this.vaultNumber = vaultNumber;
        this.mode = mode;
        this.onlineTarget = onlineTarget;
    }

    public UUID getActorUuid() {
        return actorUuid;
    }

    public UUID getTargetUuid() {
        return targetUuid;
    }

    public int getVaultNumber() {
        return vaultNumber;
    }

    public VaultOpenMode getMode() {
        return mode;
    }

    public boolean isOnlineTarget() {
        return onlineTarget;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
