package org.hyzionstudios.mysticessentials.api.notification;

import java.util.Locale;

/**
 * How insistently a notification should interrupt the player.
 *
 * <p>Priority selects a delivery <i>profile</i> (which surfaces are used) and
 * decides how much control a player has over suppressing it. The escalation is
 * deliberate: routine information stays in chat, and only genuine emergencies
 * are allowed to take over the screen.</p>
 */
public enum NotificationPriority {

    /** Routine information. Chat, optionally a toast, never an intrusive sound. */
    LOW("low"),

    /** Ordinary broadcasts. Chat, action bar, a light sound. */
    NORMAL("normal"),

    /** Events, updates, warnings. Chat, title and subtitle, sound, and history. */
    IMPORTANT("important"),

    /**
     * Restarts, emergencies, mandatory information. Everything IMPORTANT uses
     * plus a persistent banner, and — unless the server explicitly allows it —
     * players cannot switch these off.
     */
    CRITICAL("critical");

    private final String id;

    NotificationPriority(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** Whether this priority outranks {@code other}. */
    public boolean atLeast(NotificationPriority other) {
        return ordinal() >= other.ordinal();
    }

    /** Parses a config or command value, defaulting to {@link #NORMAL}. */
    public static NotificationPriority parse(String value) {
        return parse(value, NORMAL);
    }

    public static NotificationPriority parse(String value, NotificationPriority fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (NotificationPriority priority : values()) {
            if (priority.id.equals(normalized)) {
                return priority;
            }
        }
        return switch (normalized) {
            case "info", "minor" -> LOW;
            case "warn", "warning", "event" -> IMPORTANT;
            case "emergency", "urgent", "alert" -> CRITICAL;
            default -> fallback;
        };
    }
}
