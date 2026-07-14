package org.hyzionstudios.mysticessentials.api.rtp.event;

import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;

/** Fired for each candidate coordinate rejected during a search (useful for debug tooling). */
public record RtpCandidateRejectedEvent(
        UUID playerId, String profileId, double x, double z, String reason) implements MysticEvent {
}
