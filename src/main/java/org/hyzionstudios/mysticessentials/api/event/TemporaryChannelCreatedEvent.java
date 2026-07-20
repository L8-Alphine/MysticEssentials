package org.hyzionstudios.mysticessentials.api.event;

import java.util.UUID;

/**
 * Fired when a temporary chat channel is created on this server. The owner is an implicit
 * first member (no separate membership event is fired for them). External addons (e.g.
 * MysticIdentity's Discord temp-room bridge) subscribe via {@link EventBus}.
 */
public record TemporaryChannelCreatedEvent(
    String channelId,
    UUID ownerUuid
) implements MysticEvent {
}
