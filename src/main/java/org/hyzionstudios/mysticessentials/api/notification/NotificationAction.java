package org.hyzionstudios.mysticessentials.api.notification;

import java.util.Locale;

/**
 * What happens when a player opens a notification from the Notification Center.
 *
 * <p>An action is <b>declarative</b> — a kind and a value — never a callback.
 * That matters because notifications outlive the code that created them: a
 * critical alert persists across a reconnect, and a mod that sent one may have
 * been reloaded or removed by the time the player clicks it. A stale action
 * simply does nothing.</p>
 *
 * @param kind  what sort of target {@code value} names
 * @param value the target: a command line, a URL, a snapshot id, a page id
 */
public record NotificationAction(Kind kind, String value) {

    public enum Kind {
        /** Nothing to open. */
        NONE,
        /** Run a command as the player, e.g. {@code /warp citadel}. */
        COMMAND,
        /** Open an external link. */
        URL,
        /** Open the ItemView for a shared item snapshot. */
        ITEM_VIEW,
        /** Switch the player to a chat channel. */
        CHANNEL,
        /** Open a named Mystic page (guild map, quest journal, …). */
        PAGE
    }

    private static final NotificationAction NONE = new NotificationAction(Kind.NONE, "");

    public static NotificationAction none() {
        return NONE;
    }

    public static NotificationAction command(String command) {
        if (command == null || command.isBlank()) {
            return NONE;
        }
        String normalized = command.trim();
        return new NotificationAction(Kind.COMMAND,
                normalized.startsWith("/") ? normalized : "/" + normalized);
    }

    public static NotificationAction url(String url) {
        return url == null || url.isBlank() ? NONE : new NotificationAction(Kind.URL, url.trim());
    }

    public static NotificationAction itemView(String snapshotId) {
        return snapshotId == null || snapshotId.isBlank()
                ? NONE : new NotificationAction(Kind.ITEM_VIEW, snapshotId.trim());
    }

    public static NotificationAction channel(String channelId) {
        return channelId == null || channelId.isBlank()
                ? NONE : new NotificationAction(Kind.CHANNEL, channelId.trim());
    }

    public static NotificationAction page(String pageId) {
        return pageId == null || pageId.isBlank()
                ? NONE : new NotificationAction(Kind.PAGE, pageId.trim());
    }

    public boolean isPresent() {
        return kind != Kind.NONE && value != null && !value.isBlank();
    }

    /** Serialized form for the history store: {@code kind:value}. */
    public String encode() {
        return isPresent() ? kind.name().toLowerCase(Locale.ROOT) + ":" + value : "";
    }

    /** Parses {@link #encode()}; anything unrecognised yields {@link #none()}. */
    public static NotificationAction decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return NONE;
        }
        int colon = encoded.indexOf(':');
        if (colon <= 0) {
            return NONE;
        }
        String kind = encoded.substring(0, colon).toUpperCase(Locale.ROOT);
        String value = encoded.substring(colon + 1);
        try {
            return new NotificationAction(Kind.valueOf(kind), value);
        } catch (IllegalArgumentException unknownKind) {
            return NONE;
        }
    }
}
