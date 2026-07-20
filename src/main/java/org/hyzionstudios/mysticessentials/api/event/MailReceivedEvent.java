package org.hyzionstudios.mysticessentials.api.event;

import java.util.UUID;

/**
 * Fired on this server when a mail lands in a player's inbox (online or offline
 * recipient). External addons (e.g. MysticIdentity's Discord mail notifications)
 * subscribe via {@link EventBus}. {@code bodyPreview} is truncated, plain text;
 * {@code hasRewards} distinguishes item mail from plain mail.
 */
public record MailReceivedEvent(
    UUID recipient,
    String senderName,
    String bodyPreview,
    boolean hasRewards,
    boolean recipientOnline
) implements MysticEvent {
}
