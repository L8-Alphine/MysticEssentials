package org.hyzionstudios.mysticessentials.api.rtp.event;

import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;
import org.hyzionstudios.mysticessentials.api.model.MysticLocation;

/**
 * Fired immediately before the final teleport move, after revalidation. A
 * listener may cancel to abort the move (the request ends as
 * {@code RtpStatus.CANCELLED}; the player is refunded).
 */
public final class RtpPreTeleportEvent implements MysticEvent.Cancellable {

    private final UUID playerId;
    private final String profileId;
    private final MysticLocation destination;
    private boolean cancelled;

    public RtpPreTeleportEvent(UUID playerId, String profileId, MysticLocation destination) {
        this.playerId = playerId;
        this.profileId = profileId;
        this.destination = destination;
    }

    public UUID playerId() {
        return playerId;
    }

    public String profileId() {
        return profileId;
    }

    public MysticLocation destination() {
        return destination;
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
