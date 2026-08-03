package org.hyzionstudios.mysticessentials.api.notification;

import java.time.Instant;

/**
 * A stored notification in a player's Notification Center.
 *
 * <p>Records are what a player reads <i>after</i> the moment has passed, so they
 * keep the text and the action but none of the delivery mechanics — a record
 * never re-plays a sound or re-takes the screen.</p>
 *
 * @param id           unique id, used as the read/dismiss key
 * @param category     the notification's category
 * @param priority     the notification's priority
 * @param title        headline, or empty
 * @param message      body text
 * @param source       the mod or system that sent it
 * @param action       what opening it does
 * @param receivedAt   when it arrived
 * @param expiresAt    when it should be pruned, or {@code null} to keep until evicted
 * @param read         whether the player has seen it
 */
public record NotificationRecord(String id, NotificationCategory category,
        NotificationPriority priority, String title, String message, String source,
        NotificationAction action, Instant receivedAt, Instant expiresAt, boolean read) {

    /** A copy marked read or unread. */
    public NotificationRecord withRead(boolean value) {
        return new NotificationRecord(id, category, priority, title, message, source, action,
                receivedAt, expiresAt, value);
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /** The best single line to list this record by. */
    public String heading() {
        return title == null || title.isBlank() ? message : title;
    }
}
