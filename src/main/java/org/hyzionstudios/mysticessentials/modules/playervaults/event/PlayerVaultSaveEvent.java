package org.hyzionstudios.mysticessentials.modules.playervaults.event;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.PlayerVault;

/**
 * Fired around a vault save. In the {@link Phase#PRE} phase it is cancellable —
 * a listener may veto the write, and cancellation is guaranteed to leave the
 * current stored data untouched (it never partially commits). {@link Phase#POST}
 * is informational and not cancellable.
 */
public final class PlayerVaultSaveEvent implements MysticEvent.Cancellable {

    public enum Phase {
        PRE, POST
    }

    private final PlayerVault vault;
    private final Phase phase;
    private final long expectedVersion;
    private boolean cancelled;

    public PlayerVaultSaveEvent(PlayerVault vault, Phase phase, long expectedVersion) {
        this.vault = vault;
        this.phase = phase;
        this.expectedVersion = expectedVersion;
    }

    public PlayerVault getVault() {
        return vault;
    }

    public Phase getPhase() {
        return phase;
    }

    public long getExpectedVersion() {
        return expectedVersion;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        // Only the PRE phase is vetoable; POST cancellation is ignored.
        this.cancelled = cancelled && phase == Phase.PRE;
    }
}
