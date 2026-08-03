package org.hyzionstudios.mysticessentials.modules.chat.mention;

import org.hyzionstudios.mysticessentials.core.notification.NotificationSounds;

/**
 * Persisted settings for {@code modules/chat/mentions.json}.
 *
 * <p>The defaults are tuned to make mentions useful without making them a
 * harassment tool: exact names only, a handful per message, a cooldown per
 * target, and a separate, much stricter budget for mass mentions.</p>
 */
public final class MentionConfig {

    public boolean enabled = true;
    /** The character that introduces a mention. */
    public String prefix = "@";

    public Matching matching = new Matching();
    public Limits limits = new Limits();
    public Notifications notifications = new Notifications();
    public Rules rules = new Rules();
    public MassMentions massMentions = new MassMentions();

    public static final class Matching {
        public boolean caseSensitive = false;
        /**
         * Require the typed name to equal a player's name exactly. Leaving this on
         * is what stops {@code @Aeth} from pinging Aether and {@code @Aether} from
         * pinging AetherPlayer.
         */
        public boolean exactNameRequired = true;
        public boolean allowDisplayNames = false;
        public boolean allowNicknames = false;
    }

    public static final class Limits {
        /** Minimum gap between any two mentions from one sender. */
        public int senderGlobalCooldownSeconds = 5;
        /** Minimum gap before the same sender may mention the same person again. */
        public int sameTargetCooldownSeconds = 15;
        /** Minimum gap between mention sounds for one recipient. */
        public int recipientSoundCooldownSeconds = 3;
        public int maxMentionsPerMessage = 3;
        public int maxMentionsPerMinute = 10;
    }

    public static final class Notifications {
        /** Highlight the mention for the mentioned player specifically. */
        public boolean chatHighlight = true;
        public boolean titleEnabled = true;
        public boolean subtitleEnabled = true;
        public boolean actionbarEnabled = false;
        public boolean soundEnabled = true;
        public String sound = NotificationSounds.ANNOUNCEMENT;

        public String title = "You were mentioned!";
        public String subtitle = "{sender} mentioned you in {channel}";

        /** Markup wrapped around the mention as the mentioned player sees it. */
        public String highlightFormat = "&e&l{prefix}{name}&r";
        /**
         * Markup for everyone else. The default drops the prefix, so the line
         * reads naturally to bystanders while still naming the person.
         */
        public String bystanderFormat = "&f{name}&r";
        /**
         * Render each recipient their own copy of the line so only the mentioned
         * player sees the highlight. Turning this off sends one shared line —
         * cheaper, and the only option if a downstream mod needs the engine's own
         * single-message delivery path.
         */
        public boolean perViewerRendering = true;
    }

    public static final class Rules {
        public boolean allowSelfMention = false;
        public boolean ignoredPlayersCanNotNotify = true;
        public boolean mutedPlayersCanNotNotify = true;
        /** Whether a vanished player can be mentioned (and thereby revealed). */
        public boolean vanishedPlayersReceiveMentions = false;
        /**
         * Let holders of {@code mysticessentials.chat.mention.bypass-settings}
         * reach a player regardless of that player's scope, block list, and
         * do-not-disturb. On by default so moderators can contact someone who has
         * muted the world — which is precisely when contacting them matters.
         * Set false on servers that want player settings to be absolute.
         */
        public boolean staffBypassPlayerSettings = true;
        /** Offline mentions require a delivery store; off until that exists. */
        public boolean offlineMentionsEnabled = false;
    }

    /** {@code @everyone} and friends: separate names, separate permissions, harder limits. */
    public static final class MassMentions {
        public boolean enabled = true;
        public int cooldownSeconds = 300;
        public String everyoneKeyword = "everyone";
        public String onlineKeyword = "online";
        public String staffKeyword = "staff";
        public String channelKeyword = "channel";
    }

    /** Restores blanked-out blocks and clamps out-of-range values after an edit. */
    public MentionConfig normalized() {
        MentionConfig defaults = new MentionConfig();
        if (prefix == null || prefix.isBlank()) {
            prefix = defaults.prefix;
        }
        if (matching == null) {
            matching = defaults.matching;
        }
        if (limits == null) {
            limits = defaults.limits;
        }
        if (notifications == null) {
            notifications = defaults.notifications;
        }
        if (rules == null) {
            rules = defaults.rules;
        }
        if (massMentions == null) {
            massMentions = defaults.massMentions;
        }
        if (notifications.highlightFormat == null || notifications.highlightFormat.isBlank()) {
            notifications.highlightFormat = defaults.notifications.highlightFormat;
        }
        if (notifications.bystanderFormat == null || notifications.bystanderFormat.isBlank()) {
            notifications.bystanderFormat = defaults.notifications.bystanderFormat;
        }
        if (notifications.sound == null || notifications.sound.isBlank()
                || "SFX_UI_Notification".equals(notifications.sound)) {
            notifications.sound = defaults.notifications.sound;
        }
        limits.maxMentionsPerMessage = clamp(limits.maxMentionsPerMessage, 1, 20, 3);
        limits.maxMentionsPerMinute = clamp(limits.maxMentionsPerMinute, 1, 200, 10);
        limits.senderGlobalCooldownSeconds = clamp(limits.senderGlobalCooldownSeconds, 0, 3600, 5);
        limits.sameTargetCooldownSeconds = clamp(limits.sameTargetCooldownSeconds, 0, 3600, 15);
        limits.recipientSoundCooldownSeconds =
                clamp(limits.recipientSoundCooldownSeconds, 0, 3600, 3);
        massMentions.cooldownSeconds = clamp(massMentions.cooldownSeconds, 0, 86_400, 300);
        return this;
    }

    private static int clamp(int value, int min, int max, int fallback) {
        return value < min || value > max ? fallback : value;
    }
}
