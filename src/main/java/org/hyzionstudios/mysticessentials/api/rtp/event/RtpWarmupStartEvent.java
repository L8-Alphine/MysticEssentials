package org.hyzionstudios.mysticessentials.api.rtp.event;

import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;

/** Fired when a player's RTP warmup begins. */
public record RtpWarmupStartEvent(UUID playerId, String profileId, int warmupSeconds) implements MysticEvent {
}
