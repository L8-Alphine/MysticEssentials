package org.hyzionstudios.mysticessentials.api.notification;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * One thing a player should be told about, and how insistently to tell them.
 *
 * <p>A notification describes <b>content and intent</b>, not mechanics. The
 * sender says "this is a critical maintenance alert"; the delivery layer decides
 * that means chat plus a title plus a persistent banner, honouring the server's
 * profiles and the recipient's preferences. That is what lets mentions, guild
 * warnings, auction sales, and server restarts all run through one engine and
 * still feel consistent.</p>
 *
 * <p>The surface flags below are <i>overrides</i>. Leave them unset and the
 * category's profile decides — which is almost always what you want, because it
 * means a server admin can retune every event notification at once.</p>
 */
public final class Notification {

    private final String id;
    private final NotificationCategory category;
    private final NotificationPriority priority;

    private final String title;
    private final String subtitle;
    private final String message;

    private final String icon;
    private final String sound;

    private final Boolean showInChat;
    private final Boolean showAsTitle;
    private final Boolean showAsActionBar;
    private final Boolean showAsToast;
    private final Boolean showAsBanner;
    private final Boolean storeInHistory;

    private final Duration duration;
    private final Duration expiration;

    private final NotificationAction action;
    private final String source;
    private final boolean dismissible;
    private final boolean bypassPlayerPreferences;
    private final String chatPrefix;

    private Notification(Builder builder) {
        this.id = builder.id == null ? UUID.randomUUID().toString() : builder.id;
        this.category = builder.category == null ? NotificationCategory.GENERAL : builder.category;
        this.priority = builder.priority == null ? NotificationPriority.NORMAL : builder.priority;
        this.title = builder.title;
        this.subtitle = builder.subtitle;
        this.message = builder.message;
        this.icon = builder.icon;
        this.sound = builder.sound;
        this.showInChat = builder.showInChat;
        this.showAsTitle = builder.showAsTitle;
        this.showAsActionBar = builder.showAsActionBar;
        this.showAsToast = builder.showAsToast;
        this.showAsBanner = builder.showAsBanner;
        this.storeInHistory = builder.storeInHistory;
        this.duration = builder.duration;
        this.expiration = builder.expiration;
        this.action = builder.action == null ? NotificationAction.none() : builder.action;
        this.source = builder.source;
        this.dismissible = builder.dismissible;
        this.bypassPlayerPreferences = builder.bypassPlayerPreferences;
        this.chatPrefix = builder.chatPrefix;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A one-line notification at the given priority — the common case. */
    public static Notification simple(NotificationCategory category, NotificationPriority priority,
            String message) {
        return builder().category(category).priority(priority).message(message).build();
    }

    public String id() {
        return id;
    }

    public NotificationCategory category() {
        return category;
    }

    public NotificationPriority priority() {
        return priority;
    }

    /** Large headline text. Markup is permitted — this text comes from the server. */
    public Optional<String> title() {
        return present(title);
    }

    public Optional<String> subtitle() {
        return present(subtitle);
    }

    /** The chat body. Falls back to the title when unset. */
    public Optional<String> message() {
        return present(message);
    }

    /** The best single line to show: message, else title, else subtitle, else empty. */
    public String bestText() {
        return message().or(this::title).or(this::subtitle).orElse("");
    }

    public Optional<String> icon() {
        return present(icon);
    }

    /** Sound event id, overriding the category's default. */
    public Optional<String> sound() {
        return present(sound);
    }

    public Optional<Boolean> showInChat() {
        return Optional.ofNullable(showInChat);
    }

    public Optional<Boolean> showAsTitle() {
        return Optional.ofNullable(showAsTitle);
    }

    public Optional<Boolean> showAsActionBar() {
        return Optional.ofNullable(showAsActionBar);
    }

    public Optional<Boolean> showAsToast() {
        return Optional.ofNullable(showAsToast);
    }

    /** Whether to pin a persistent banner (the boss-bar equivalent). */
    public Optional<Boolean> showAsBanner() {
        return Optional.ofNullable(showAsBanner);
    }

    public Optional<Boolean> storeInHistory() {
        return Optional.ofNullable(storeInHistory);
    }

    /** How long on-screen surfaces stay visible. */
    public Optional<Duration> duration() {
        return Optional.ofNullable(duration);
    }

    /** How long the history entry survives before being pruned. */
    public Optional<Duration> expiration() {
        return Optional.ofNullable(expiration);
    }

    public NotificationAction action() {
        return action;
    }

    /** Who sent this, e.g. {@code mysticessentials:chat}. Shown in the history. */
    public Optional<String> source() {
        return present(source);
    }

    /** Whether a player may dismiss the banner before it expires. */
    public boolean dismissible() {
        return dismissible;
    }

    /**
     * Whether this notification reaches the recipient regardless of their own
     * preferences — muted category, disabled surfaces, do-not-disturb.
     *
     * <p>This is the staff/system override, and it is deliberately a per-send
     * decision rather than a permission the sender simply holds: a moderator
     * contacting someone who has muted the world should be able to get through,
     * but their ordinary chatter should not. Use it for moderation contact and
     * operational notices, not to make routine messages louder.</p>
     */
    public boolean bypassPlayerPreferences() {
        return bypassPlayerPreferences;
    }

    /**
     * Chat prefix for this one send, overriding the category's.
     *
     * <p>Exists so a sender that already owns a configured prefix — the
     * announcements module's {@code broadcastPrefix} and {@code alertPrefix} —
     * keeps using it instead of being silently retagged by the category. Applies
     * to the chat surface only; titles, toasts, and history entries are never
     * prefixed. Empty string means "no prefix at all", which is distinct from
     * unset.</p>
     */
    public Optional<String> chatPrefix() {
        return Optional.ofNullable(chatPrefix);
    }

    private static Optional<String> present(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    public Builder toBuilder() {
        return builder()
                .id(id).category(category).priority(priority)
                .title(title).subtitle(subtitle).message(message)
                .icon(icon).sound(sound)
                .showInChat(showInChat).showAsTitle(showAsTitle)
                .showAsActionBar(showAsActionBar).showAsToast(showAsToast)
                .showAsBanner(showAsBanner).storeInHistory(storeInHistory)
                .duration(duration).expiration(expiration)
                .action(action).source(source).dismissible(dismissible)
                .bypassPlayerPreferences(bypassPlayerPreferences)
                .chatPrefix(chatPrefix);
    }

    public static final class Builder {
        private String id;
        private NotificationCategory category;
        private NotificationPriority priority;
        private String title;
        private String subtitle;
        private String message;
        private String icon;
        private String sound;
        private Boolean showInChat;
        private Boolean showAsTitle;
        private Boolean showAsActionBar;
        private Boolean showAsToast;
        private Boolean showAsBanner;
        private Boolean storeInHistory;
        private Duration duration;
        private Duration expiration;
        private NotificationAction action;
        private String source;
        private boolean dismissible = true;
        private boolean bypassPlayerPreferences;
        private String chatPrefix;

        private Builder() {
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder category(NotificationCategory category) {
            this.category = category;
            return this;
        }

        public Builder category(String categoryId) {
            return category(NotificationCategory.of(categoryId));
        }

        public Builder priority(NotificationPriority priority) {
            this.priority = priority;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder subtitle(String subtitle) {
            this.subtitle = subtitle;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder icon(String icon) {
            this.icon = icon;
            return this;
        }

        public Builder sound(String sound) {
            this.sound = sound;
            return this;
        }

        public Builder showInChat(Boolean showInChat) {
            this.showInChat = showInChat;
            return this;
        }

        public Builder showAsTitle(Boolean showAsTitle) {
            this.showAsTitle = showAsTitle;
            return this;
        }

        public Builder showAsActionBar(Boolean showAsActionBar) {
            this.showAsActionBar = showAsActionBar;
            return this;
        }

        public Builder showAsToast(Boolean showAsToast) {
            this.showAsToast = showAsToast;
            return this;
        }

        public Builder showAsBanner(Boolean showAsBanner) {
            this.showAsBanner = showAsBanner;
            return this;
        }

        /** Alias matching the boss-bar vocabulary used by other platforms. */
        public Builder showAsBossBar(Boolean showAsBossBar) {
            return showAsBanner(showAsBossBar);
        }

        public Builder storeInHistory(Boolean storeInHistory) {
            this.storeInHistory = storeInHistory;
            return this;
        }

        public Builder duration(Duration duration) {
            this.duration = duration;
            return this;
        }

        public Builder durationSeconds(long seconds) {
            return duration(seconds > 0 ? Duration.ofSeconds(seconds) : null);
        }

        public Builder expiration(Duration expiration) {
            this.expiration = expiration;
            return this;
        }

        public Builder action(NotificationAction action) {
            this.action = action;
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public Builder dismissible(boolean dismissible) {
            this.dismissible = dismissible;
            return this;
        }

        /**
         * Delivers regardless of the recipient's preferences. Reserve this for
         * staff contact and operational notices — see
         * {@link Notification#bypassPlayerPreferences()}.
         */
        public Builder bypassPlayerPreferences(boolean bypassPlayerPreferences) {
            this.bypassPlayerPreferences = bypassPlayerPreferences;
            return this;
        }

        /**
         * Overrides the category's chat prefix for this send. Pass {@code null} to
         * use the category's, or {@code ""} for no prefix at all.
         */
        public Builder chatPrefix(String chatPrefix) {
            this.chatPrefix = chatPrefix;
            return this;
        }

        public Notification build() {
            return new Notification(this);
        }
    }
}
