package org.hyzionstudios.mysticessentials.api.rtp.event;

import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;
import org.hyzionstudios.mysticessentials.api.model.MysticLocation;

/**
 * Fired when the search resolves a safe destination, before the teleport. A
 * listener may mutate the destination or cancel to reject it (the search then
 * fails with {@code RtpStatus.NO_DESTINATION}).
 */
public final class RtpDestinationFoundEvent implements MysticEvent.Cancellable {

    private final UUID playerId;
    private final String profileId;
    private MysticLocation destination;
    private final int attempts;
    private boolean cancelled;

    public RtpDestinationFoundEvent(UUID playerId, String profileId, MysticLocation destination, int attempts) {
        this.playerId = playerId;
        this.profileId = profileId;
        this.destination = destination;
        this.attempts = attempts;
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

    public void setDestination(MysticLocation destination) {
        this.destination = destination;
    }

    public int attempts() {
        return attempts;
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
