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
    public Glyphs glyphs = new Glyphs();
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

    public static final class Glyphs {
        public boolean enabled = true;
        public boolean registerCommonAssets = true;
        public boolean emitPrivateUseCodepoints = true;
        public boolean allowRawUnicodeSymbols = true;
        public boolean stripUnsafeInvisibleCharacters = true;
        public String fallbackWhenMissing = "text";
        public Map<String, String> permissions = defaultGlyphPermissions();
    }

    public static final class Channels {
        public boolean enabled = true;
        public String defaultSpeak = "global";
        public List<String> defaultJoin = new ArrayList<>(List.of("global"));
        public boolean allowTemporaryChannels = true;
        public int temporaryChannelDefaultMinutes = 120;
        public String createTemporaryPermission = "mysticessentials.chat.channel.create.temp";
        public List<Channel> channels = defaultChannels();
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

    private static Map<String, String> defaultGlyphPermissions() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("emoji", "mysticessentials.chat.emoji.use");
        map.put("custom", "mysticessentials.chat.emoji.custom");
        map.put("unicode", "mysticessentials.chat.unicode.symbols");
        map.put("staff", "mysticessentials.chat.emoji.staff");
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
