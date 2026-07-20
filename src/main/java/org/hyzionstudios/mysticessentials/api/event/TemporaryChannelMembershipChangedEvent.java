package org.hyzionstudios.mysticessentials.api.event;

import java.util.UUID;

/**
 * Fired when a player joins or leaves a temporary chat channel on this server, including
 * implicit leaves on disconnect. Only temporary channels fire this — membership changes on
 * configured channels do not.
 */
public record TemporaryChannelMembershipChangedEvent(
    String channelId,
    UUID playerUuid,
    boolean joined
) implements MysticEvent {
}
