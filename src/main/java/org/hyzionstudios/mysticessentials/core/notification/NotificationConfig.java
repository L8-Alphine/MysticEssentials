package org.hyzionstudios.mysticessentials.core.notification;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.hyzionstudios.mysticessentials.api.notification.NotificationPriority;

/**
 * Persisted settings for {@code modules/core/notifications.json}: the delivery
 * profiles, the category catalogue, and the history rules.
 *
 * <p>The split between <b>profiles</b> and <b>categories</b> is what makes this
 * tunable in practice. A profile says which surfaces a notification uses; a
 * category says what a kind of notification looks like and which profile it
 * defaults to. Retuning every event notification on a server is one edit to the
 * {@code important} profile, not an edit per sender.</p>
 */
public final class NotificationConfig {

    public boolean enabled = true;

    public History history = new History();
    public Critical critical = new Critical();

    /** Delivery profiles by id. Priorities map onto these by default. */
    public Map<String, Profile> profiles = defaultProfiles();

    /** Category catalogue by id. Unknown categories fall back to {@code general}. */
    public Map<String, Category> categories = defaultCategories();

    public static final class History {
        public boolean enabled = true;
        /** Stored notifications kept per player. */
        public int maximumPerPlayer = 50;
        /** Keep critical notifications across a reconnect even once read. */
        public boolean persistCritical = true;
        /** How long a routine stored notification survives. */
        public int defaultExpirationHours = 24;
    }

    public static final class Critical {
        /**
         * Whether players may switch critical alerts off. Off by default: a
         * restart warning that a player has muted is a restart warning that did
         * not happen.
         */
        public boolean allowPlayerDisable = false;
    }

    /** Which surfaces a notification uses, and for how long. */
    public static final class Profile {
        public boolean chat = true;
        public boolean title = false;
        public boolean subtitle = false;
        public boolean actionbar = false;
        public boolean toast = false;
        public boolean banner = false;
        public boolean sound = false;
        public boolean history = false;

        public int fadeInMillis = 200;
        public int stayMillis = 3000;
        public int fadeOutMillis = 600;
        /** How long a pinned banner stays before it clears itself. */
        public int durationSeconds = 6;
        /** Whether a player may dismiss the banner early. */
        public boolean dismissible = true;

        public Profile() {
        }

        private Profile chat(boolean value) {
            this.chat = value;
            return this;
        }

        private Profile titles(boolean value) {
            this.title = value;
            this.subtitle = value;
            return this;
        }

        private Profile actionbar(boolean value) {
            this.actionbar = value;
            return this;
        }

        private Profile toast(boolean value) {
            this.toast = value;
            return this;
        }

        private Profile banner(boolean value) {
            this.banner = value;
            return this;
        }

        private Profile sound(boolean value) {
            this.sound = value;
            return this;
        }

        private Profile history(boolean value) {
            this.history = value;
            return this;
        }

        private Profile timing(int fadeIn, int stay, int fadeOut) {
            this.fadeInMillis = fadeIn;
            this.stayMillis = stay;
            this.fadeOutMillis = fadeOut;
            return this;
        }
    }

    /** Presentation defaults for a kind of notification. */
    public static final class Category {
        public String displayName;
        public String icon;
        public String accent = "#7a9cc6";
        public String sound;
        /** Profile id used when the sender does not override the surfaces. */
        public String defaultProfile = "broadcast";
        /** Minimum priority; a sender asking for less is raised to this. */
        public String minimumPriority = "low";
        /** Chat prefix prepended to the message, with markup. */
        public String chatPrefix = "";
        /** Whether players may turn this category off entirely. */
        public boolean playerDisableable = true;

        public Category() {
        }

        Category(String displayName, String accent, String defaultProfile, String chatPrefix) {
            this.displayName = displayName;
            this.accent = accent;
            this.defaultProfile = defaultProfile;
            this.chatPrefix = chatPrefix;
        }
    }

    // ----- Lookup -----------------------------------------------------------------

    /** The category definition for {@code id}, falling back to {@code general}. */
    public Category category(String id) {
        if (categories != null) {
            Category found = categories.get(normalize(id));
            if (found != null) {
                return found;
            }
            Category general = categories.get("general");
            if (general != null) {
                return general;
            }
        }
        return new Category("General", "#7a9cc6", "broadcast", "&8[&7Notice&8] &r");
    }

    /**
     * The profile for a category at a given priority. An explicit priority always
     * wins over the category's default profile, so {@code /alert critical} is
     * critical even in a category that normally broadcasts.
     */
    public Profile profileFor(Category category, NotificationPriority priority) {
        String id = switch (priority) {
            case LOW -> "chat-only";
            case NORMAL -> category == null ? "broadcast" : category.defaultProfile;
            case IMPORTANT -> "important";
            case CRITICAL -> "critical";
        };
        return profile(id);
    }

    public Profile profile(String id) {
        if (profiles != null) {
            Profile found = profiles.get(normalize(id));
            if (found != null) {
                return found;
            }
            Profile broadcast = profiles.get("broadcast");
            if (broadcast != null) {
                return broadcast;
            }
        }
        return new Profile();
    }

    private static String normalize(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    /** Restores blanked-out blocks and clamps out-of-range values after an edit. */
    public NotificationConfig normalized() {
        NotificationConfig defaults = new NotificationConfig();
        if (history == null) {
            history = defaults.history;
        }
        if (critical == null) {
            critical = defaults.critical;
        }
        if (profiles == null || profiles.isEmpty()) {
            profiles = defaults.profiles;
        } else {
            defaults.profiles.forEach(profiles::putIfAbsent);
        }
        if (categories == null || categories.isEmpty()) {
            categories = defaults.categories;
        } else {
            defaults.categories.forEach(categories::putIfAbsent);
        }
        // Migrate the placeholder id shipped by the first notification preview.
        // It never existed in Hytale's AssetMap, so keeping it would make an
        // upgraded server silently lose mention sounds forever.
        categories.forEach((id, category) -> {
            if (category != null && "SFX_UI_Notification".equals(category.sound)) {
                category.sound = defaults.category(id).sound;
            }
        });
        history.maximumPerPlayer = clamp(history.maximumPerPlayer, 1, 500, 50);
        history.defaultExpirationHours = clamp(history.defaultExpirationHours, 1, 24 * 30, 24);
        profiles.values().forEach(profile -> {
            profile.fadeInMillis = clamp(profile.fadeInMillis, 0, 10_000, 200);
            profile.stayMillis = clamp(profile.stayMillis, 100, 60_000, 3000);
            profile.fadeOutMillis = clamp(profile.fadeOutMillis, 0, 10_000, 600);
            profile.durationSeconds = clamp(profile.durationSeconds, 1, 3600, 6);
        });
        return this;
    }

    private static int clamp(int value, int min, int max, int fallback) {
        return value < min || value > max ? fallback : value;
    }

    // ----- Defaults ------------------------------------------------------------------

    private static Map<String, Profile> defaultProfiles() {
        Map<String, Profile> map = new LinkedHashMap<>();
        map.put("chat-only", new Profile()
                .chat(true).titles(false).actionbar(false).toast(false)
                .banner(false).sound(false).history(false));
        map.put("broadcast", new Profile()
                .chat(true).titles(false).actionbar(true).toast(true)
                .banner(false).sound(true).history(true));
        map.put("important", new Profile()
                .chat(true).titles(true).actionbar(false).toast(true)
                .banner(false).sound(true).history(true)
                .timing(200, 4000, 750));
        Profile criticalProfile = new Profile()
                .chat(true).titles(true).actionbar(false).toast(true)
                .banner(true).sound(true).history(true)
                .timing(100, 6000, 1000);
        criticalProfile.dismissible = false;
        criticalProfile.durationSeconds = 60;
        map.put("critical", criticalProfile);
        return map;
    }

    private static Map<String, Category> defaultCategories() {
        Map<String, Category> map = new LinkedHashMap<>();
        map.put("general", category("General", "#7a9cc6", "chat-only",
                "&8[&7Notice&8] &r", NotificationSounds.QUIET));
        map.put("announcement", category("Announcement", "#4AA3FF", "broadcast",
                "&8[&bAnnouncement&8] &r", NotificationSounds.ANNOUNCEMENT));
        map.put("event", category("Event", "#C45CFF", "important",
                "&8[&dEvent&8] &r", NotificationSounds.ANNOUNCEMENT));
        map.put("maintenance", category("Maintenance", "#FFB347", "critical",
                "&8[&6Maintenance&8] &r", NotificationSounds.CRITICAL));
        map.put("update", category("Update", "#4AA3FF", "broadcast",
                "&8[&bUpdate&8] &r", NotificationSounds.ANNOUNCEMENT));
        map.put("warning", category("Warning", "#FFCA3A", "important",
                "&8[&eWarning&8] &r", NotificationSounds.ALERT));

        Category criticalCategory =
                new Category("Critical Alert", "#FF4B4B", "critical", "&8[&cCritical Alert&8] &r");
        criticalCategory.sound = NotificationSounds.CRITICAL;
        criticalCategory.minimumPriority = "critical";
        criticalCategory.playerDisableable = false;
        map.put("critical", criticalCategory);

        Category emergency =
                new Category("Emergency", "#FF4B4B", "critical", "&8[&4Emergency&8] &r");
        emergency.sound = NotificationSounds.CRITICAL;
        emergency.minimumPriority = "critical";
        emergency.playerDisableable = false;
        map.put("emergency", emergency);

        map.put("staff", category("Staff", "#8FD48F", "broadcast",
                "&8[&aStaff&8] &r", NotificationSounds.QUIET));
        map.put("tutorial", category("Tutorial", "#9FB0C4", "chat-only",
                "&8[&7Tutorial&8] &r", NotificationSounds.QUIET));

        Category mention = new Category("Mention", "#E8A93B", "important", "");
        mention.sound = NotificationSounds.ANNOUNCEMENT;
        map.put("mention", mention);

        map.put("mail", category("Mail", "#E8C97A", "broadcast", "", NotificationSounds.QUIET));
        map.put("teleport", category("Teleport", "#80C7FF", "broadcast", "",
                NotificationSounds.ANNOUNCEMENT));
        map.put("message", category("Message", "#D68CFF", "broadcast", "",
                NotificationSounds.QUIET));
        map.put("channel", category("Channel", "#70C1B3", "broadcast", "",
                NotificationSounds.QUIET));
        map.put("quest", category("Quest", "#E8C97A", "broadcast",
                "&8[&eQuest&8] &r", NotificationSounds.ANNOUNCEMENT));
        map.put("guild", category("Guild", "#5599FF", "important",
                "&8[&9Guild&8] &r", NotificationSounds.ALERT));
        map.put("economy", category("Economy", "#F0B429", "chat-only",
                "&8[&6Economy&8] &r", NotificationSounds.QUIET));
        map.put("region", category("Region", "#9FB0C4", "chat-only", "",
                NotificationSounds.QUIET));
        map.put("world", category("World", "#9FB0C4", "broadcast", "",
                NotificationSounds.ANNOUNCEMENT));
        map.put("dungeon", category("Dungeon", "#C45CFF", "important",
                "&8[&5Dungeon&8] &r", NotificationSounds.ALERT));
        map.put("system", category("System", "#96A9BE", "chat-only",
                "&8[&7System&8] &r", NotificationSounds.QUIET));
        return map;
    }

    private static Category category(String displayName, String accent, String profile,
            String chatPrefix, String sound) {
        Category category = new Category(displayName, accent, profile, chatPrefix);
        category.sound = sound;
        return category;
    }
}
