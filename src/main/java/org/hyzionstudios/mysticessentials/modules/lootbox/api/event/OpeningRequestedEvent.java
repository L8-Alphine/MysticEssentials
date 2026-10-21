package org.hyzionstudios.mysticessentials.modules.lootbox.api.event;

import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;

/**
 * A player confirmed an opening; nothing is spent yet. Cancelling it stops the
 * opening before any key is touched (for example an event or region rule). This
 * is the only LootBox event that can change what happens.
 */
public final class OpeningRequestedEvent implements MysticEvent.Cancellable {

    private final UUID player;
    private final String lootboxId;
    private final int revision;
    private final String crateId;
    private boolean cancelled;
    private String reason = "";

    public OpeningRequestedEvent(UUID player, String lootboxId, int revision, String crateId) {
        this.player = player;
        this.lootboxId = lootboxId;
        this.revision = revision;
        this.crateId = crateId;
    }

    public UUID player() {
        return player;
    }

    public String lootboxId() {
        return lootboxId;
    }

    public int revision() {
        return revision;
    }

    /** The crate used, or {@code null} when opened from a menu or command. */
    public String crateId() {
        return crateId;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    /** Shown to the player when cancelled. */
    public String reason() {
        return reason;
    }

    public void cancel(String reason) {
        this.cancelled = true;
        this.reason = reason == null ? "" : reason;
    }
}
