package org.hyzionstudios.mysticessentials.api.notification;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * The single engine behind every player-facing notice: mentions, broadcasts,
 * alerts, quest updates, guild warnings, party invitations, economy notices,
 * mail delivery, moderation notices, tutorial prompts, and server restarts.
 *
 * <p>Callers describe <i>what</i> happened and <i>who</i> should know
 * ({@link Notification} + {@link NotificationAudience}); this service decides
 * which surfaces to use, applies the server's profiles, honours each recipient's
 * preferences, and stores what should be reviewable later. Routing everything
 * through one place is what makes the rules — critical alerts cannot be
 * suppressed, do-not-disturb is respected everywhere, history is consistent —
 * hold across every mod in the ecosystem instead of being reimplemented badly in
 * each.</p>
 *
 * <p>Sending is safe from any thread and never throws: a delivery surface that
 * fails is logged and skipped, so one broken sound asset cannot stop a restart
 * warning from reaching players.</p>
 */
public interface NotificationService {

    /** Sends {@code notification} to everyone {@code audience} resolves to. */
    void send(Notification notification, NotificationAudience audience);

    /** Convenience for the very common single-recipient case. */
    default void send(Notification notification, UUID player) {
        send(notification, NotificationAudience.player(player));
    }

    /**
     * Removes a pinned banner early — for a countdown that finished or an event
     * that was cancelled. No-op if it is not currently shown.
     */
    void clearBanner(String notificationId, NotificationAudience audience);

    // ----- History ---------------------------------------------------------------

    /** A player's stored notifications, newest first. */
    List<NotificationRecord> history(UUID player);

    /** Unread stored notifications for a player, newest first. */
    List<NotificationRecord> unread(UUID player);

    /** Marks one record read. @return whether a matching unread record existed. */
    boolean markRead(UUID player, String notificationId);

    /** Marks every stored record read. @return how many changed. */
    int markAllRead(UUID player);

    /** Removes one record from a player's history. */
    boolean dismiss(UUID player, String notificationId);

    // ----- External audiences -------------------------------------------------------

    /**
     * Registers a resolver for a {@link NotificationAudience#named(String, String)}
     * type, letting another mod make its own grouping addressable — for example
     * {@code registerAudienceResolver("guild", id -> membersOf(id))}.
     *
     * <p>Re-registering a type replaces the previous resolver, so a mod reload
     * does not leave a stale one behind.</p>
     */
    void registerAudienceResolver(String type, Function<String, Collection<PlayerRef>> resolver);

    /** Removes a resolver. @return whether one was registered. */
    boolean unregisterAudienceResolver(String type);

    // ----- Notification Center filters ------------------------------------------------

    /**
     * Adds a tab to the Notification Center's filter row.
     *
     * <p>Use this for any grouping built on a concept this mod does not own —
     * guild, party, auction. Mystic Essentials ships only {@code all},
     * {@code unread}, {@code mentions}, and {@code system}; a filter nobody
     * registers is not shown, so players never get a tab that can only ever be
     * empty.</p>
     *
     * <p>Re-registering the same id replaces the previous filter, so a mod reload
     * does not accumulate duplicates.</p>
     */
    void registerFilter(NotificationFilter filter);

    /** Removes a filter by id. @return whether one was registered. */
    boolean unregisterFilter(String filterId);

    /**
     * The currently available filters, in display order. Excludes any whose
     * {@code isAvailable()} is false.
     */
    List<NotificationFilter> filters();
}
