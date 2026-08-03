package org.hyzionstudios.mysticessentials.modules.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persisted settings for {@code modules/chat/config.json}. Mirrors the chat
 * design: a default format, priority-ordered permission-gated formats, and the
 * permission nodes that gate each colour style a player may use in chat.
 */
public final class ChatConfig {

    public boolean formatChat = true;
    public int maxMessageLength = 256;
    public Boolean autoLinkPlainUrls = true;
    public String autoLinkPermission = null;
    public String defaultFormat = "{luckperms_prefix}{display_name} &8» &f{message}";
    public List<Format> formats = defaultFormats();
    public Map<String, String> messageColorPermissions = defaultColorPermissions();
    public PrivateMessaging privateMessaging = new PrivateMessaging();
    public Channels channels = new Channels();

    public static final class Format {
        public String id;
        public int priority;
        public String permission;
        public String format;

        public Format() {
        }

        public Format(int priority, String permission, String format) {
            this.id = permission == null ? "default" : permission.substring(permission.lastIndexOf('.') + 1);
            this.priority = priority;
            this.permission = permission;
            this.format = format;
        }
    }

    public static final class PrivateMessaging {
        public boolean enabled = true;
        public boolean allowCrossServer = true;
        public boolean offlineToMail = true;
        public boolean socialSpyEnabled = true;
        public String messagePermission = "mysticessentials.chat.private.message";
        public String replyPermission = "mysticessentials.chat.private.reply";
        public String socialSpyPermission = "mysticessentials.chat.socialspy";
        public String socialSpyExemptPermission = "mysticessentials.chat.socialspy.exempt";
    }

    public static final class Channels {
        public boolean enabled = true;
        public String defaultSpeak = "global";
        public List<String> defaultJoin = new ArrayList<>(List.of("global"));
        public boolean allowTemporaryChannels = true;
        public int temporaryChannelDefaultMinutes = 120;
        public String createTemporaryPermission = "mysticessentials.chat.channel.create.temp";
        public List<Channel> channels = defaultChannels();
        public Roster roster = new Roster();
        public TempManagement tempManagement = new TempManagement();
    }

    /**
     * Temporary-channel management settings (design bible §22 {@code temporary-channels}).
     * Governs ownership transfer, owner-disconnect recovery and what channel
     * moderators are permitted to do.
     */
    public static final class TempManagement {
        public OwnershipTransfer ownershipTransfer = new OwnershipTransfer();
        public OwnerDisconnect ownerDisconnect = new OwnerDisconnect();
        public Moderation moderation = new Moderation();
    }

    /** Ownership-transfer flow (§9). */
    public static final class OwnershipTransfer {
        public boolean enabled = true;
        public boolean targetMustAccept = true;
        public int requestExpirationSeconds = 60;
        /** Role the previous owner takes after a transfer: {@code CHANNEL_MODERATOR|MEMBER|LISTENER|REMOVE}. */
        public String previousOwnerRole = "CHANNEL_MODERATOR";
    }

    /** Owner-disconnect grace + succession policy (§10). */
    public static final class OwnerDisconnect {
        public int gracePeriodSeconds = 300;
        /** {@code KEEP_OWNERSHIP|PROMOTE_MODERATOR|PROMOTE_OLDEST_MEMBER|CLOSE_CHANNEL}. */
        public String successionMode = "PROMOTE_MODERATOR";
        /** Applied when {@link #successionMode} cannot produce an owner. */
        public String fallbackMode = "PROMOTE_OLDEST_MEMBER";
    }

    /** Default channel-moderator abilities (§8.3), each individually configurable. */
    public static final class Moderation {
        public boolean moderatorsCanRemoveMembers = true;
        public boolean moderatorsCanMuteMembers = true;
        public boolean moderatorsCanChangeParticipation = true;
        public boolean moderatorsCanPromoteModerators = false;
        public boolean moderatorsCanCloseChannel = false;
        public boolean moderatorsCanBanMembers = false;
    }

    /**
     * Channel roster settings (design bible §22 {@code channel-roster} + {@code member-tags}).
     * Phase 1 covers the member list, role/tag resolution and the compact + expanded
     * views; later phases add activity, transfer and cross-server keys.
     */
    public static final class Roster {
        public boolean enabled = true;
        /** Node required to open a roster; blank/null means everyone may view. */
        public String viewPermission = "mysticessentials.channel.members.view";
        /** Node that marks a player as server staff for the {@code STAFF} tag (resolved live). */
        public String staffPermission = "mysticessentials.chat.staff";
        /** Show the member's LuckPerms group / server rank beneath the channel tag (§4.3). */
        public boolean showServerRanks = true;
        /** Group channel owners and moderators into a separate management section (§5.3). */
        public boolean groupAuthorityMembers = true;
        /** Allow a smaller secondary tag (e.g. an owner who is also staff shows {@code STAFF}) (§4.2). */
        public boolean allowSecondaryTags = true;
        public int maximumSecondaryTags = 1;
        /** Soft cap on rows shown in the compact roster before the "open full list" hint (§25). */
        public int maximumVisibleMembers = 50;

        public Tag owner = new Tag("OWNER", 100, "#E8A93B");
        public Tag channelModerator = new Tag("CH MOD", 80, "#3FB6A8");
        public Tag staff = new Tag("STAFF", 60, "#D46A6A");
        public Tag member = new Tag("MEMBER", 10, "#7a9cc6");
        public Activity activity = new Activity();
    }

    /** Live-activity indicator settings (design bible §22 {@code channel-roster.activity}). */
    public static final class Activity {
        public boolean enabled = true;
        /** Show a member as "Recently active" for this many seconds after their last message. */
        public int recentTextActivitySeconds = 30;
        /** Query the voice provider for a live speaking indicator (no-op without a provider). */
        public boolean activeSpeakerIndicator = true;
        /** Typing has no client signal in 0.5.6; kept for config compatibility, off by default. */
        public boolean typingIndicator = false;
    }

    /** A configurable channel tag: display text, priority and swatch colour (§4.1). */
    public static final class Tag {
        public String text;
        public int priority;
        public String color;

        public Tag() {
        }

        public Tag(String text, int priority, String color) {
            this.text = text;
            this.priority = priority;
            this.color = color;
        }
    }

    public static final class Channel {
        public String id;
        public String displayName;
        public boolean enabled = true;
        public String scope = "server";
        public String format;
        /**
         * Per-LuckPerms-group format overrides for this channel: primary group
         * name (lowercase) &rarr; format. Falls back to {@link #format} when the
         * speaker's group has no entry (or LuckPerms is absent).
         */
        public Map<String, String> groupFormats = new LinkedHashMap<>();
        public String prefix;
        public List<String> aliases = new ArrayList<>();
        public String password;
        public String joinPermission;
        public String speakPermission;
        public String listenPermission;
        public String moderatorPermission;
        public boolean crossServer;
        public String redisTopic;
        public int radiusBlocks = 0;
        /** When {@code true}, new members cannot join until unlocked (§8.2 lock). */
        public boolean locked;

        public Channel() {
        }

        public Channel(String id, String displayName, String scope, String format) {
            this.id = id;
            this.displayName = displayName;
            this.scope = scope;
            this.format = format;
        }
    }

    private static List<Format> defaultFormats() {
        List<Format> list = new ArrayList<>();
        list.add(new Format(100, "mysticessentials.chat.format.owner",
                "<gradient:#7b2cff:#00d4ff>&lOWNER</gradient> {display_name} &8» <#ffffff>{message}"));
        return list;
    }

    private static Map<String, String> defaultColorPermissions() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("legacy", "mysticessentials.chat.color.legacy");
        map.put("hex", "mysticessentials.chat.color.hex");
        map.put("gradient", "mysticessentials.chat.color.gradient");
        map.put("rainbow", "mysticessentials.chat.color.rainbow");
        map.put("minimessage", "mysticessentials.chat.color.minimessage");
        map.put("links", "mysticessentials.chat.color.links");
        return map;
    }

    private static List<Channel> defaultChannels() {
        List<Channel> list = new ArrayList<>();
        Channel global = new Channel("global", "Global", "server",
                "&8[&aG&8] {luckperms_prefix}{display_name} &8» &f{message}");
        global.prefix = "&8[&aG&8]";
        // Example per-LuckPerms-group override: players whose primary group is
        // "admin" get this format in the global channel.
        global.groupFormats.put("admin", "&8[&aG&8] &4[Admin] {luckperms_prefix}{display_name} &8» &f{message}");
        global.aliases = new ArrayList<>(List.of("g", "global"));
        global.moderatorPermission = "mysticessentials.chat.channel.global.moderator";
        list.add(global);

        Channel staff = new Channel("staff", "Staff", "permission",
                "&8[&bStaff&8] &f{display_name}: &b{message}");
        staff.prefix = "&8[&bStaff&8]";
        staff.aliases = new ArrayList<>(List.of("sc", "schat", "staffchat"));
        staff.joinPermission = "mysticessentials.chat.channel.staff";
        staff.speakPermission = "mysticessentials.chat.channel.staff.speak";
        staff.listenPermission = "mysticessentials.chat.channel.staff.listen";
        staff.moderatorPermission = "mysticessentials.chat.channel.staff.moderator";
        staff.crossServer = true;
        staff.redisTopic = "staff";
        list.add(staff);

        return list;
    }
}
