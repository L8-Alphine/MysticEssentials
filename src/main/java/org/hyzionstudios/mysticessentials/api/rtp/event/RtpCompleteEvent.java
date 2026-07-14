package org.hyzionstudios.mysticessentials.api.rtp.event;

import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;
import org.hyzionstudios.mysticessentials.api.model.MysticLocation;

/** Fired after a player has been successfully random-teleported. */
public record RtpCompleteEvent(
        UUID playerId, String profileId, MysticLocation destination,
        int attempts, long durationMillis) implements MysticEvent {
}
