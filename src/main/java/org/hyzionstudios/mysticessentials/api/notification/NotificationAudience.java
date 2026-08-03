package org.hyzionstudios.mysticessentials.api.notification;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Who should receive a notification.
 *
 * <p>An audience is a description, resolved against the online player list at
 * send time. Most kinds resolve inside this mod; the ones that depend on systems
 * this mod does not own — guilds, parties, regions — resolve through a
 * {@link #named(String, String) named} audience that the owning mod registers a
 * resolver for. That keeps the notification engine usable by MysticGuilds and
 * MysticParty without this mod having to know what a guild is.</p>
 */
public final class NotificationAudience {

    /** How an audience is resolved. */
    public enum Kind {
        /** Every online player. */
        ALL,
        /** An explicit set of player ids. */
        PLAYERS,
        /** Everyone holding a permission node. */
        PERMISSION,
        /** Everyone in a world. */
        WORLD,
        /** Everyone currently in a chat channel. */
        CHANNEL,
        /** Everyone within a radius of a point. */
        NEARBY,
        /** An arbitrary server-side test. */
        PREDICATE,
        /** Resolved by a registered external resolver ({@code guild}, {@code party}, …). */
        NAMED
    }

    private final Kind kind;
    private final Set<UUID> players;
    private final String value;
    private final String qualifier;
    private final double x;
    private final double y;
    private final double z;
    private final double radius;
    private final Predicate<PlayerRef> predicate;

    private NotificationAudience(Kind kind, Set<UUID> players, String value, String qualifier,
            double x, double y, double z, double radius, Predicate<PlayerRef> predicate) {
        this.kind = kind;
        this.players = players == null ? Set.of() : Set.copyOf(players);
        this.value = value;
        this.qualifier = qualifier;
        this.x = x;
        this.y = y;
        this.z = z;
        this.radius = radius;
        this.predicate = predicate;
    }

    public static NotificationAudience all() {
        return new NotificationAudience(Kind.ALL, null, null, null, 0, 0, 0, 0, null);
    }

    public static NotificationAudience player(UUID player) {
        return players(player == null ? Set.of() : Set.of(player));
    }

    public static NotificationAudience players(Collection<UUID> players) {
        return new NotificationAudience(Kind.PLAYERS, new LinkedHashSet<>(players), null, null,
                0, 0, 0, 0, null);
    }

    public static NotificationAudience permission(String permission) {
        return new NotificationAudience(Kind.PERMISSION, null, permission, null, 0, 0, 0, 0, null);
    }

    public static NotificationAudience world(String worldName) {
        return new NotificationAudience(Kind.WORLD, null, worldName, null, 0, 0, 0, 0, null);
    }

    public static NotificationAudience channel(String channelId) {
        return new NotificationAudience(Kind.CHANNEL, null, channelId, null, 0, 0, 0, 0, null);
    }

    public static NotificationAudience nearby(String worldName, double x, double y, double z,
            double radius) {
        return new NotificationAudience(Kind.NEARBY, null, worldName, null, x, y, z,
                Math.max(0, radius), null);
    }

    /** Everyone the predicate accepts. Evaluated on the sending thread, so keep it cheap. */
    public static NotificationAudience matching(Predicate<PlayerRef> predicate) {
        return new NotificationAudience(Kind.PREDICATE, null, null, null, 0, 0, 0, 0, predicate);
    }

    /** Staff, defined as holders of the staff notification permission. */
    public static NotificationAudience staff() {
        return permission("mysticessentials.notifications.staff");
    }

    /**
     * An audience only another mod can resolve, e.g. {@code named("guild", guildId)}.
     * Delivers to nobody until that mod registers a resolver for {@code type} —
     * silently, because a guild notification arriving on a server with no guild
     * mod is a no-op, not an error.
     */
    public static NotificationAudience named(String type, String id) {
        return new NotificationAudience(Kind.NAMED, null, id, type, 0, 0, 0, 0, null);
    }

    public static NotificationAudience guild(String guildId) {
        return named("guild", guildId);
    }

    public static NotificationAudience party(String partyId) {
        return named("party", partyId);
    }

    public static NotificationAudience region(String regionId) {
        return named("region", regionId);
    }

    public Kind kind() {
        return kind;
    }

    public Set<UUID> playerIds() {
        return players;
    }

    /** The permission, world, channel, or external id, depending on {@link #kind()}. */
    public String value() {
        return value;
    }

    /** The resolver type for {@link Kind#NAMED}. */
    public String qualifier() {
        return qualifier;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public double radius() {
        return radius;
    }

    public Predicate<PlayerRef> predicate() {
        return predicate;
    }

    @Override
    public String toString() {
        return switch (kind) {
            case ALL -> "all";
            case PLAYERS -> players.size() + " player(s)";
            case NAMED -> qualifier + ":" + value;
            case PREDICATE -> "predicate";
            default -> kind.name().toLowerCase(java.util.Locale.ROOT) + ":" + value;
        };
    }
}
