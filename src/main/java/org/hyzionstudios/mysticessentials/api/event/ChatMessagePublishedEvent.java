package org.hyzionstudios.mysticessentials.api.event;

import java.util.UUID;

/**
 * Fired after a local player's public chat message has passed the full chat
 * pipeline (sanitization, item-link expansion, channel routing) and will be
 * delivered. External addons (e.g. MysticIdentity's Discord chat bridge)
 * subscribe via {@link EventBus}.
 *
 * <p>Fired only on the origin server: cross-server deliveries arriving over
 * Redis do not re-fire this event, so a network-wide subscriber sees each
 * message exactly once. Messages injected through
 * {@code ChatService.broadcastToChannel} never fire this event (loop
 * prevention for external bridges).</p>
 *
 * <p>{@code content} is the sanitized post-pipeline message body without the
 * channel format applied; {@code displayName} honours nicknames. {@code primaryGroup}
 * and {@code rankPrefix} snapshot the sender's permission rank at send time (empty when
 * no permission provider resolves them); the prefix may contain in-game color codes that
 * bridges must strip for their destination.</p>
 */
public record ChatMessagePublishedEvent(
    UUID senderUuid,
    String senderName,
    String displayName,
    String channelId,
    String channelDisplayName,
    String content,
    boolean crossServer,
    String primaryGroup,
    String rankPrefix
) implements MysticEvent {
}
