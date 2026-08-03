package org.hyzionstudios.mysticessentials.api.event;

import java.util.UUID;

/**
 * Fired when a player is assigned or removed as a channel moderator of a temporary
 * channel (design bible §20.3). Informational; the change is already committed.
 */
public record ChannelModeratorChangedEvent(
    String channelId,
    UUID playerUuid,
    UUID actor,
    boolean assigned
) implements MysticEvent {
}
