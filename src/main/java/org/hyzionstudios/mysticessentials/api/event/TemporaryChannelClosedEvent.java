package org.hyzionstudios.mysticessentials.api.event;

/**
 * Fired when a temporary chat channel is removed on this server — closed by its owner,
 * expired, or cleared because the server emptied.
 */
public record TemporaryChannelClosedEvent(
    String channelId
) implements MysticEvent {
}
