package org.hyzionstudios.mysticessentials.api.rtp.event;

import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;
import org.hyzionstudios.mysticessentials.api.rtp.RtpStatus;

/** Fired when a player's RTP fails (no destination, world unavailable, error, ...). */
public record RtpFailedEvent(UUID playerId, String profileId, RtpStatus status, String detail) implements MysticEvent {
}
