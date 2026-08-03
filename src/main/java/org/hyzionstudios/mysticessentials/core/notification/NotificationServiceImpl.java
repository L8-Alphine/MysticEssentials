package org.hyzionstudios.mysticessentials.core.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.notification.Notification;
import org.hyzionstudios.mysticessentials.api.notification.NotificationAction;
import org.hyzionstudios.mysticessentials.api.notification.NotificationAudience;
import org.hyzionstudios.mysticessentials.api.notification.NotificationFilter;
import org.hyzionstudios.mysticessentials.api.notification.NotificationPriority;
import org.hyzionstudios.mysticessentials.api.notification.NotificationRecord;
import org.hyzionstudios.mysticessentials.api.notification.NotificationService;
import org.hyzionstudios.mysticessentials.api.service.ChatService;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.message.MysticText;
import org.hyzionstudios.mysticessentials.platform.Conversions;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * The concrete notification engine: resolves an audience, applies the category's
 * profile, honours each recipient's preferences, delivers across every enabled
 * surface, and records what should be reviewable later.
 *
 * <p>The order of those steps is deliberate. Preferences are consulted
 * <i>per recipient</i>, so one player's do-not-disturb never affects another's
 * delivery, and history is written even when every visible surface was
 * suppressed — a player in do-not-disturb should still find the guild warning
 * waiting in their Notification Center rather than never learning of it.</p>
 */
public final class NotificationServiceImpl implements NotificationService {

    private final MysticCore core;
    private volatile NotificationConfig config;

    private final NotificationStore store;
    private final NotificationDelivery delivery;

    /** Resolvers other mods registered for {@code guild}, {@code party}, and the like. */
    private final Map<String, Function<String, Collection<PlayerRef>>> audienceResolvers =
            new ConcurrentHashMap<>();

    /** Notification Center tabs, seeded with the ones this mod can define alone. */
    private final Map<String, NotificationFilter> filters = new ConcurrentHashMap<>();

    public NotificationServiceImpl(MysticCore core, NotificationConfig config) {
        this.core = core;
        this.config = config == null ? new NotificationConfig().normalized() : config;
        this.store = new NotificationStore(core, this.config);
        this.delivery = new NotificationDelivery(core, this.config);
        registerBuiltInFilters();
    }

    /**
     * The filters this mod can define without help. Notably absent: guild, party,
     * and anything else built on a concept it does not own — those are registered
     * by the mod that owns them, so a server without that mod shows no tab rather
     * than a tab that can only ever be empty.
     */
    private void registerBuiltInFilters() {
        registerFilter(new BuiltInFilter("all", "All", 0, record -> true));
        registerFilter(new BuiltInFilter("unread", "Unread", 10, record -> !record.read()));
        registerFilter(new BuiltInFilter("mentions", "Mentions", 20,
                record -> "mention".equals(record.category().id())));
        // "System" groups the server's own operational categories — what a player
        // means when they want to filter out the social traffic.
        registerFilter(new BuiltInFilter("system", "System", 30, record -> switch (
                record.category().id()) {
            case "system", "maintenance", "update", "critical", "emergency", "warning",
                    "announcement" -> true;
            default -> record.priority() == NotificationPriority.CRITICAL;
        }));
    }

    /** A filter with a fixed rule and no external system behind it. */
    private record BuiltInFilter(String id, String displayName, int sortOrder,
            java.util.function.Predicate<NotificationRecord> rule) implements NotificationFilter {

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getDisplayName() {
            return displayName;
        }

        @Override
        public int getSortOrder() {
            return sortOrder;
        }

        @Override
        public boolean matches(NotificationRecord record) {
            return rule.test(record);
        }
    }

    public void updateConfig(NotificationConfig config) {
        this.config = config == null ? new NotificationConfig().normalized() : config;
        this.store.updateConfig(this.config);
        this.delivery.updateConfig(this.config);
    }

    public NotificationConfig config() {
        return config;
    }

    /** Preferences for one player, loaded from their profile on first access. */
    public NotificationPreferences preferences(UUID player) {
        return store.preferences(player);
    }

    /** Persists a player's preferences after an edit through the settings UI. */
    public void savePreferences(UUID player) {
        store.savePreferences(player);
    }

    /** Flushes and drops a player's cached notification state on disconnect. */
    public void unload(UUID player) {
        store.unload(player);
    }

    // ----- Sending -----------------------------------------------------------------

    @Override
    public void send(Notification notification, NotificationAudience audience) {
        if (notification == null || audience == null || !config.enabled) {
            return;
        }
        try {
            NotificationConfig.Category category = config.category(notification.category().id());
            Notification effective = applyCategoryFloor(notification, category);
            NotificationConfig.Profile profile = config.profileFor(category, effective.priority());
            boolean critical = effective.priority() == NotificationPriority.CRITICAL;

            for (PlayerRef recipient : resolve(audience)) {
                deliverTo(recipient, effective, category, profile, critical);
            }
        } catch (Throwable t) {
            // A notification is never important enough to take down its caller —
            // and the callers here include the chat pipeline and module lifecycle.
            core.log(Level.WARNING, "[notifications] Send failed for '"
                    + notification.category() + "': " + t);
        }
    }

    private void deliverTo(PlayerRef recipient, Notification notification,
            NotificationConfig.Category category, NotificationConfig.Profile profile,
            boolean critical) {
        UUID uuid = recipient.getUuid();
        NotificationPreferences preferences = store.preferences(uuid);

        // Three ways past a player's own settings, in order of scope: an explicit
        // per-send staff override, a category the server marked non-disableable,
        // or a critical priority. Anything else defers to the recipient.
        boolean allowed = notification.bypassPlayerPreferences()
                || !category.playerDisableable
                || preferences.allows(notification.category().id(), critical,
                        config.critical.allowPlayerDisable);

        if (allowed) {
            // Send every visible surface from the player's world thread. Several
            // producers complete on storage/HTTP/Redis threads, while Hytale's
            // title and message APIs only render reliably from the owning world.
            // Centralising the hop here keeps third-party callers safe too.
            core.platform().runOnEntityThread(recipient, (entityStore, entity, world) ->
                    delivery.deliver(recipient, notification, category, profile, preferences,
                            critical || notification.bypassPlayerPreferences()));
        }
        // Recorded regardless of whether anything was shown: history exists
        // precisely for the notifications a player did not see live.
        if (notification.storeInHistory().orElse(profile.history)) {
            store.record(uuid, toRecord(notification, category));
        }
    }

    /**
     * Raises a notification to its category's minimum priority. A category
     * declared {@code critical} stays critical even if a caller (or a
     * mistyped command) asked for something gentler.
     */
    private Notification applyCategoryFloor(Notification notification,
            NotificationConfig.Category category) {
        NotificationPriority floor =
                NotificationPriority.parse(category.minimumPriority, NotificationPriority.LOW);
        if (notification.priority().atLeast(floor)) {
            return notification;
        }
        return notification.toBuilder().priority(floor).build();
    }

    private NotificationRecord toRecord(Notification notification,
            NotificationConfig.Category category) {
        Duration expiry = notification.expiration()
                .orElseGet(() -> Duration.ofHours(Math.max(1, config.history.defaultExpirationHours)));
        return new NotificationRecord(
                notification.id(),
                notification.category(),
                notification.priority(),
                MysticText.stripMarkup(notification.title().orElse(category.displayName == null
                        ? "" : category.displayName)),
                MysticText.stripMarkup(notification.bestText()),
                notification.source().orElse("mysticessentials"),
                notification.action(),
                Instant.now(),
                Instant.now().plus(expiry),
                false);
    }

    @Override
    public void clearBanner(String notificationId, NotificationAudience audience) {
        if (audience == null) {
            return;
        }
        for (PlayerRef recipient : resolve(audience)) {
            delivery.clearBanner(recipient);
        }
    }

    // ----- Audience resolution --------------------------------------------------------

    /**
     * Expands an audience into the online players it names. Deduplicated by UUID,
     * because a player who matches two clauses should be told once.
     */
    private Collection<PlayerRef> resolve(NotificationAudience audience) {
        Map<UUID, PlayerRef> out = new LinkedHashMap<>();
        try {
            switch (audience.kind()) {
                case ALL -> core.platform().onlinePlayers().forEach(p -> put(out, p));
                case PLAYERS -> audience.playerIds().forEach(id ->
                        core.platform().findPlayer(id).ifPresent(p -> put(out, p)));
                case PERMISSION -> forEachOnline(out, player ->
                        audience.value() == null || player.hasPermission(audience.value()));
                case WORLD -> forEachOnline(out, player ->
                        audience.value() != null
                                && audience.value().equals(
                                        Conversions.resolveWorldName(player.getWorldUuid())));
                case CHANNEL -> resolveChannel(out, audience.value());
                case NEARBY -> resolveNearby(out, audience);
                case PREDICATE -> forEachOnline(out, player ->
                        audience.predicate() != null && audience.predicate().test(player));
                case NAMED -> resolveNamed(out, audience);
            }
        } catch (Throwable t) {
            core.log(Level.WARNING, "[notifications] Audience '" + audience + "' failed to "
                    + "resolve; delivering to nobody: " + t);
        }
        return out.values();
    }

    private void forEachOnline(Map<UUID, PlayerRef> out,
            java.util.function.Predicate<PlayerRef> test) {
        for (PlayerRef player : core.platform().onlinePlayers()) {
            if (player != null && safeTest(test, player)) {
                put(out, player);
            }
        }
    }

    private static boolean safeTest(java.util.function.Predicate<PlayerRef> test, PlayerRef player) {
        try {
            return test.test(player);
        } catch (Throwable t) {
            return false;
        }
    }

    private void resolveChannel(Map<UUID, PlayerRef> out, String channelId) {
        ChatService chat = core.getChatService();
        if (chat == null || channelId == null) {
            return;
        }
        String wanted = channelId.toLowerCase(Locale.ROOT);
        forEachOnline(out, player ->
                wanted.equalsIgnoreCase(chat.currentChannel(player.getUuid())));
    }

    private void resolveNearby(Map<UUID, PlayerRef> out, NotificationAudience audience) {
        double radiusSquared = audience.radius() * audience.radius();
        forEachOnline(out, player -> {
            if (audience.value() != null
                    && !audience.value().equals(Conversions.resolveWorldName(player.getWorldUuid()))) {
                return false;
            }
            var position = player.getTransform().getPosition();
            double dx = position.x() - audience.x();
            double dy = position.y() - audience.y();
            double dz = position.z() - audience.z();
            return dx * dx + dy * dy + dz * dz <= radiusSquared;
        });
    }

    /**
     * Delegates to whichever mod owns this grouping. With no resolver registered
     * the audience is empty and the send is a silent no-op — a guild alert on a
     * server with no guild mod is nothing to warn about.
     */
    private void resolveNamed(Map<UUID, PlayerRef> out, NotificationAudience audience) {
        Function<String, Collection<PlayerRef>> resolver =
                audienceResolvers.get(normalize(audience.qualifier()));
        if (resolver == null) {
            return;
        }
        Collection<PlayerRef> resolved = resolver.apply(audience.value());
        if (resolved != null) {
            resolved.forEach(player -> put(out, player));
        }
    }

    private static void put(Map<UUID, PlayerRef> out, PlayerRef player) {
        if (player != null) {
            out.put(player.getUuid(), player);
        }
    }

    @Override
    public void registerAudienceResolver(String type,
            Function<String, Collection<PlayerRef>> resolver) {
        if (type == null || type.isBlank() || resolver == null) {
            return;
        }
        audienceResolvers.put(normalize(type), resolver);
        core.log(Level.INFO, "[notifications] Registered audience resolver '"
                + normalize(type) + "'.");
    }

    @Override
    public boolean unregisterAudienceResolver(String type) {
        return type != null && audienceResolvers.remove(normalize(type)) != null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    // ----- Filters -----------------------------------------------------------------------

    @Override
    public void registerFilter(NotificationFilter filter) {
        if (filter == null) {
            return;
        }
        String id = safeFilterId(filter);
        if (id.isEmpty()) {
            return;
        }
        filters.put(id, filter);
    }

    @Override
    public boolean unregisterFilter(String filterId) {
        return filterId != null && filters.remove(normalize(filterId)) != null;
    }

    @Override
    public List<NotificationFilter> filters() {
        List<NotificationFilter> out = new ArrayList<>();
        for (NotificationFilter filter : filters.values()) {
            if (safeIsAvailable(filter)) {
                out.add(filter);
            }
        }
        out.sort(java.util.Comparator
                .comparingInt(NotificationServiceImpl::safeSortOrder)
                .thenComparing(NotificationServiceImpl::safeFilterName));
        return out;
    }

    /** A registered filter by id, or empty when nothing currently provides it. */
    public Optional<NotificationFilter> filter(String filterId) {
        return filterId == null ? Optional.empty()
                : Optional.ofNullable(filters.get(normalize(filterId)));
    }

    /** Whether {@code record} passes {@code filter}; a throwing filter matches nothing. */
    public boolean matches(NotificationFilter filter, NotificationRecord record) {
        if (filter == null) {
            return true;
        }
        try {
            return filter.matches(record);
        } catch (Throwable t) {
            logSafely("[notifications] Filter '" + safeFilterId(filter)
                    + "' threw; treating the record as no match: " + t);
            return false;
        }
    }

    /**
     * Logs without being able to throw. Containment blocks call this instead of
     * {@link MysticCore#log} directly: a catch clause that can itself fail turns
     * a contained third-party fault into an uncontained one, which defeats the
     * point of catching it.
     */
    private void logSafely(String message) {
        try {
            if (core != null) {
                core.log(Level.FINE, message);
            }
        } catch (Throwable ignored) {
            // Nothing useful left to do; the caller's fallback still applies.
        }
    }

    // A third-party filter is untrusted code on the page-render path: a throw from
    // any accessor costs that tab, never the page.

    static String safeFilterId(NotificationFilter filter) {
        try {
            String id = filter.getId();
            return id == null ? "" : normalize(id);
        } catch (Throwable t) {
            return "";
        }
    }

    static String safeFilterName(NotificationFilter filter) {
        try {
            String name = filter.getDisplayName();
            return name == null || name.isBlank() ? safeFilterId(filter) : name;
        } catch (Throwable t) {
            return safeFilterId(filter);
        }
    }

    private static int safeSortOrder(NotificationFilter filter) {
        try {
            return filter.getSortOrder();
        } catch (Throwable t) {
            return 100;
        }
    }

    private static boolean safeIsAvailable(NotificationFilter filter) {
        try {
            return filter.isAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    // ----- History ---------------------------------------------------------------------

    @Override
    public List<NotificationRecord> history(UUID player) {
        return store.history(player);
    }

    @Override
    public List<NotificationRecord> unread(UUID player) {
        List<NotificationRecord> out = new ArrayList<>();
        for (NotificationRecord record : store.history(player)) {
            if (!record.read()) {
                out.add(record);
            }
        }
        return out;
    }

    @Override
    public boolean markRead(UUID player, String notificationId) {
        return store.markRead(player, notificationId);
    }

    @Override
    public int markAllRead(UUID player) {
        return store.markAllRead(player);
    }

    @Override
    public boolean dismiss(UUID player, String notificationId) {
        return store.dismiss(player, notificationId);
    }

    /** Looks up one stored record, for opening it from the Notification Center. */
    public Optional<NotificationRecord> find(UUID player, String notificationId) {
        return store.find(player, notificationId);
    }

    /**
     * Performs a record's action for a player. Unresolvable actions are ignored
     * rather than reported, because a stale action on an old notification is
     * expected, not exceptional.
     */
    public void runAction(PlayerRef player, NotificationAction action) {
        if (player == null || action == null || !action.isPresent()) {
            return;
        }
        try {
            switch (action.kind()) {
                case COMMAND -> core.platform().dispatchPlayerCommand(player, action.value());
                case ITEM_VIEW -> core.platform()
                        .dispatchPlayerCommand(player, "/itemview " + action.value());
                case CHANNEL -> core.platform()
                        .dispatchPlayerCommand(player, "/channel " + action.value());
                case URL -> core.getMessageService().send(player,
                        "<link:" + action.value() + ">&b" + action.value() + "</link>");
                case PAGE -> core.platform().dispatchPlayerCommand(player, "/" + action.value());
                case NONE -> { /* nothing to open */ }
            }
        } catch (Throwable t) {
            core.log(Level.FINE, "[notifications] Action " + action.encode() + " failed: " + t);
        }
    }
}
