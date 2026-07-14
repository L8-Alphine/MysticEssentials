package org.hyzionstudios.mysticessentials.api.rtp.event;

import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;

/**
 * Fired when an RTP request is made, before any checks run. Cancellable — a
 * listener setting cancelled aborts the request with {@code RtpStatus.CANCELLED}.
 */
public final class RtpRequestEvent implements MysticEvent.Cancellable {

    private final UUID playerId;
    private final String profileId;
    private final UUID actorId;
    private boolean cancelled;

    public RtpRequestEvent(UUID playerId, String profileId, UUID actorId) {
        this.playerId = playerId;
        this.profileId = profileId;
        this.actorId = actorId;
    }

    public UUID playerId() {
        return playerId;
    }

    public String profileId() {
        return profileId;
    }

    public UUID actorId() {
        return actorId;
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
