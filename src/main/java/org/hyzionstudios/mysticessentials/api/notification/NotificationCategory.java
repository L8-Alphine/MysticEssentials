package org.hyzionstudios.mysticessentials.api.notification;

import java.util.Locale;
import java.util.Objects;

/**
 * What kind of thing a notification is about — {@code event}, {@code guild},
 * {@code mention}, {@code maintenance}, and so on.
 *
 * <p>Deliberately <b>not</b> an enum. Categories are server-configurable and
 * mods add their own, so a closed set would force every new category through a
 * release of this mod. The constants below are only the well-known ids that ship
 * with default styling; {@link #of(String)} accepts anything.</p>
 *
 * <p>A category supplies presentation defaults (icon, accent, sound, default
 * profile, chat prefix) and is the unit players filter their notification
 * history and preferences by.</p>
 */
public final class NotificationCategory {

    public static final NotificationCategory GENERAL = of("general");
    public static final NotificationCategory ANNOUNCEMENT = of("announcement");
    public static final NotificationCategory EVENT = of("event");
    public static final NotificationCategory MAINTENANCE = of("maintenance");
    public static final NotificationCategory UPDATE = of("update");
    public static final NotificationCategory WARNING = of("warning");
    public static final NotificationCategory CRITICAL = of("critical");
    public static final NotificationCategory EMERGENCY = of("emergency");
    public static final NotificationCategory STAFF = of("staff");
    public static final NotificationCategory TUTORIAL = of("tutorial");
    public static final NotificationCategory MENTION = of("mention");
    public static final NotificationCategory MAIL = of("mail");
    public static final NotificationCategory TELEPORT = of("teleport");
    public static final NotificationCategory MESSAGE = of("message");
    public static final NotificationCategory CHANNEL = of("channel");
    public static final NotificationCategory QUEST = of("quest");
    public static final NotificationCategory GUILD = of("guild");
    public static final NotificationCategory ECONOMY = of("economy");
    public static final NotificationCategory REGION = of("region");
    public static final NotificationCategory WORLD = of("world");
    public static final NotificationCategory DUNGEON = of("dungeon");
    public static final NotificationCategory SYSTEM = of("system");

    private final String id;

    private NotificationCategory(String id) {
        this.id = id;
    }

    /** Normalizes and wraps a category id. Blank input yields the general category. */
    public static NotificationCategory of(String id) {
        if (id == null || id.isBlank()) {
            return new NotificationCategory("general");
        }
        return new NotificationCategory(id.trim().toLowerCase(Locale.ROOT));
    }

    public String id() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NotificationCategory other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return id;
    }
}
