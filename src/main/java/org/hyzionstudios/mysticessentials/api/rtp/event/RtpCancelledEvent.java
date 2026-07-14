package org.hyzionstudios.mysticessentials.api.rtp.event;

import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;
import org.hyzionstudios.mysticessentials.api.rtp.RtpCancelReason;

/** Fired when a player's in-progress RTP is cancelled. */
public record RtpCancelledEvent(UUID playerId, String profileId, RtpCancelReason reason) implements MysticEvent {
}
