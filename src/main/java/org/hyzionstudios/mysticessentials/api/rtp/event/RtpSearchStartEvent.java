package org.hyzionstudios.mysticessentials.api.rtp.event;

import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;

/** Fired when the safe-destination search for a player's RTP begins. */
public record RtpSearchStartEvent(UUID playerId, String profileId) implements MysticEvent {
}
