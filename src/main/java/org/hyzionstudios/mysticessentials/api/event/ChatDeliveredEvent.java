package org.hyzionstudios.mysticessentials.api.event;

import java.util.Set;
import java.util.UUID;

/**
 * Fired after a line another mod handed to {@code ChatService.deliver} (guild, officer,
 * party or settlement chat) went out on this server, for moderation tooling: chat logs,
 * spying, report evidence. Subscribe via {@link EventBus}.
 *
 * <p>It is not a {@link ChatMessagePublishedEvent}: these lines belong to the calling
 * mod's audience, so bridges must not relay them. When {@code shadowMuted} is
 * {@code true} the sender is shadow-muted and only they saw the line.</p>
 *
 * @param channelLabel the caller's label, e.g. the guild name (colour codes kept)
 * @param content      the delivered text, as plain text
 * @param recipients   everyone the line was sent to, the sender included
 */
public record ChatDeliveredEvent(
    UUID senderUuid,
    String senderName,
    String displayName,
    String channelLabel,
    String content,
    Set<UUID> recipients,
    boolean shadowMuted
) implements MysticEvent {

    public ChatDeliveredEvent {
        recipients = recipients == null ? Set.of() : Set.copyOf(recipients);
    }
}
