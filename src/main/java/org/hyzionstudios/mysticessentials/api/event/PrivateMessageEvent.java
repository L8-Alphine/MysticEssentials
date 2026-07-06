package org.hyzionstudios.mysticessentials.api.event;

import java.util.UUID;

/**
 * Fired whenever a private message is delivered on this server, including messages
 * arriving from another server over Redis ({@code crossServer} true). External addons
 * (e.g. Mystic Moderation social spy) subscribe via {@link EventBus}.
 *
 * <p>{@code fromUuid} is null for console/server senders; {@code toUuid} is never null
 * because delivery requires a resolved local target.</p>
 */
public record PrivateMessageEvent(
    UUID fromUuid,
    String fromName,
    UUID toUuid,
    String toName,
    String message,
    boolean crossServer
) implements MysticEvent {
}
