package org.hyzionstudios.mysticessentials.api.chat;

/**
 * The outcome of {@link org.hyzionstudios.mysticessentials.api.service.ChatService#deliver}.
 *
 * @param status    what happened to the line
 * @param delivered how many players the line was sent to, the sender included
 * @param skipped   how many requested recipients, other than the sender, it was not sent
 *                  to: not online on this server, ignoring the sender, kept apart from
 *                  them by a managed account's policy, or the line was refused
 * @param reason    the mute reason when {@code status} is {@link Status#MUTED} (may be
 *                  empty), otherwise empty; never {@code null}
 */
public record ChatDeliveryResult(Status status, int delivered, int skipped, String reason) {

    public ChatDeliveryResult {
        status = status == null ? Status.UNAVAILABLE : status;
        reason = reason == null ? "" : reason;
    }

    /**
     * Whether the line went out as far as the sender can tell: {@code true} for
     * {@link Status#DELIVERED} and for {@link Status#SHADOW_MUTED}, which the sender
     * must not be able to tell apart from a delivered line.
     */
    public boolean isDelivered() {
        return status == Status.DELIVERED || status == Status.SHADOW_MUTED;
    }

    /** What happened to a delivered line. */
    public enum Status {
        /** Sent to the sender and every recipient that could be reached. */
        DELIVERED,
        /**
         * The sender is shadow-muted: the line was shown to them alone. Treat it as
         * delivered towards the sender, and relay it nowhere else.
         */
        SHADOW_MUTED,
        /** The sender is muted: nothing was sent, and the sender was told why. */
        MUTED,
        /** The sender is not online on this server: nothing was sent. */
        SENDER_OFFLINE,
        /** Nothing was left of the message once it was cleaned: nothing was sent. */
        EMPTY,
        /** The chat module is not running: nothing was sent. */
        UNAVAILABLE
    }
}
