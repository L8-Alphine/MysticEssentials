package org.hyzionstudios.mysticessentials.modules.playervaults.event;

import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;

/**
 * Fired around a backup restore. Cancellable in the {@link Phase#PRE} phase so a
 * restore can be vetoed before any data changes.
 */
public final class PlayerVaultRestoreEvent implements MysticEvent.Cancellable {

    public enum Phase {
        PRE, POST
    }

    private final UUID actorUuid;
    private final UUID ownerUuid;
    private final int vaultNumber;
    private final String backupId;
    private final Phase phase;
    private boolean cancelled;

    public PlayerVaultRestoreEvent(UUID actorUuid, UUID ownerUuid, int vaultNumber,
            String backupId, Phase phase) {
        this.actorUuid = actorUuid;
        this.ownerUuid = ownerUuid;
        this.vaultNumber = vaultNumber;
        this.backupId = backupId;
        this.phase = phase;
    }

    public UUID getActorUuid() {
        return actorUuid;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public int getVaultNumber() {
        return vaultNumber;
    }

    public String getBackupId() {
        return backupId;
    }

    public Phase getPhase() {
        return phase;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled && phase == Phase.PRE;
    }
}
