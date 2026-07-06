package org.hyzionstudios.mysticessentials.api;

/**
 * Central registry of every permission node Mystic Essentials uses. All
 * command gates, feature checks, and dynamic node prefixes live here so the
 * full permission surface can be read (and documented) in one place.
 *
 * <p>Dynamic nodes (limits, per-name gates) are exposed as {@code *_BASE}
 * prefixes plus builder methods, e.g. {@link #homeLimit(int)} &rarr;
 * {@code mysticessentials.home.limit.5} or {@link #kit(String)} &rarr;
 * {@code mysticessentials.kit.starter}.</p>
 *
 * <p>See {@code PERMISSIONS.md} in the repository root for the generated
 * operator-facing reference.</p>
 */
public final class Permissions {

    private Permissions() {
    }

    /** Root prefix for every node. */
    public static final String ROOT = "mysticessentials";

    // ----- Core --------------------------------------------------------------

    public static final String RELOAD = "mysticessentials.reload";
    public static final String MIGRATE = "mysticessentials.migrate";

    // ----- Teleportation -------------------------------------------------------

    public static final String TELEPORT_TPA = "mysticessentials.teleport.tpa";
    public static final String TELEPORT_TPHERE = "mysticessentials.teleport.tphere";
    public static final String TELEPORT_TPALL = "mysticessentials.teleport.tpall";
    public static final String TELEPORT_TOP = "mysticessentials.teleport.top";
    public static final String TELEPORT_BACK = "mysticessentials.teleport.back";
    public static final String TELEPORT_BYPASS_WARMUP = "mysticessentials.teleport.bypass.warmup";
    public static final String TELEPORT_BYPASS_COOLDOWN = "mysticessentials.teleport.bypass.cooldown";

    // ----- Spawn & Homes -------------------------------------------------------

    public static final String SPAWN_USE = "mysticessentials.spawn.use";
    public static final String SPAWN_SET = "mysticessentials.spawn.set";
    public static final String HOME_USE = "mysticessentials.home.use";
    public static final String HOME_SET = "mysticessentials.home.set";
    /** Dynamic: {@code mysticessentials.home.limit.<n>} / {@code .unlimited}. */
    public static final String HOME_LIMIT_BASE = "mysticessentials.home.limit";

    // ----- Warps ---------------------------------------------------------------

    public static final String WARP_USE = "mysticessentials.warp.use";
    public static final String WARP_SET = "mysticessentials.warp.set";
    public static final String PLAYERWARP_USE = "mysticessentials.playerwarp.use";
    public static final String PLAYERWARP_CREATE = "mysticessentials.playerwarp.create";
    public static final String PLAYERWARP_ADMIN = "mysticessentials.playerwarp.admin";
    /** Dynamic: {@code mysticessentials.playerwarp.limit.<n>} / {@code .unlimited}. */
    public static final String PLAYERWARP_LIMIT_BASE = "mysticessentials.playerwarp.limit";

    // ----- Mail ----------------------------------------------------------------

    public static final String MAIL_USE = "mysticessentials.mail.use";
    public static final String MAIL_SEND = "mysticessentials.mail.send";
    public static final String MAIL_SEND_OFFLINE = "mysticessentials.mail.send.offline";
    public static final String MAIL_SEND_ALL = "mysticessentials.mail.send.all";

    // ----- Announcements ---------------------------------------------------------

    public static final String ANNOUNCEMENT_BROADCAST = "mysticessentials.announcement.broadcast";
    public static final String ANNOUNCEMENT_ALERT = "mysticessentials.announcement.alert";

    // ----- AFK -------------------------------------------------------------------

    public static final String AFK_USE = "mysticessentials.afk.use";
    public static final String AFK_BYPASS_AUTO = "mysticessentials.afk.bypass.auto";
    public static final String AFK_REWARDS = "mysticessentials.afk.rewards";

    // ----- Chat ------------------------------------------------------------------

    public static final String CHAT_PRIVATE_MESSAGE = "mysticessentials.chat.private.message";
    public static final String CHAT_PRIVATE_REPLY = "mysticessentials.chat.private.reply";
    public static final String CHAT_SOCIALSPY = "mysticessentials.chat.socialspy";
    public static final String CHAT_SOCIALSPY_EXEMPT = "mysticessentials.chat.socialspy.exempt";
    public static final String CHAT_CHANNEL_CREATE_TEMP = "mysticessentials.chat.channel.create.temp";
    /** Dynamic: {@code mysticessentials.chat.channel.<id>[.speak|.listen|.moderator]}. */
    public static final String CHAT_CHANNEL_BASE = "mysticessentials.chat.channel";
    public static final String CHAT_COLOR_LEGACY = "mysticessentials.chat.color.legacy";
    public static final String CHAT_COLOR_HEX = "mysticessentials.chat.color.hex";
    public static final String CHAT_COLOR_GRADIENT = "mysticessentials.chat.color.gradient";
    public static final String CHAT_COLOR_RAINBOW = "mysticessentials.chat.color.rainbow";
    public static final String CHAT_COLOR_MINIMESSAGE = "mysticessentials.chat.color.minimessage";
    public static final String CHAT_COLOR_LINKS = "mysticessentials.chat.color.links";
    public static final String CHAT_EMOJI_USE = "mysticessentials.chat.emoji.use";
    public static final String CHAT_EMOJI_CUSTOM = "mysticessentials.chat.emoji.custom";
    public static final String CHAT_EMOJI_STAFF = "mysticessentials.chat.emoji.staff";
    public static final String CHAT_UNICODE_SYMBOLS = "mysticessentials.chat.unicode.symbols";

    // ----- Kits ------------------------------------------------------------------

    public static final String KIT_USE = "mysticessentials.kit.use";
    public static final String KIT_ADMIN = "mysticessentials.kit.admin";
    public static final String KIT_BYPASS_COOLDOWN = "mysticessentials.kit.bypass.cooldown";
    /** Dynamic: {@code mysticessentials.kit.<name>} for kits with {@code requirePermission}. */
    public static final String KIT_BASE = "mysticessentials.kit";

    // ----- Flight ----------------------------------------------------------------

    public static final String FLY_USE = "mysticessentials.fly.use";
    public static final String FLY_OTHERS = "mysticessentials.fly.others";
    public static final String FLY_UNLIMITED = "mysticessentials.fly.unlimited";
    public static final String FLY_FREE = "mysticessentials.fly.free";

    // ----- Inventory ---------------------------------------------------------------

    public static final String INVENTORY_CLEAR = "mysticessentials.inventory.clear";
    public static final String INVENTORY_CLEAR_OTHERS = "mysticessentials.inventory.clear.others";
    public static final String INVENTORY_CLEAR_ALL = "mysticessentials.inventory.clear.all";
    public static final String INVENTORY_RESTORE = "mysticessentials.inventory.restore";
    /** Players with this node survive {@code /clearinv all} wipes. */
    public static final String INVENTORY_PROTECT = "mysticessentials.inventory.protect";

    // ----- Nicknames ----------------------------------------------------------------

    public static final String NICK_USE = "mysticessentials.nick.use";
    public static final String NICK_COLOR = "mysticessentials.nick.color";
    public static final String NICK_OTHERS = "mysticessentials.nick.others";

    // ----- Dynamic node builders ------------------------------------------------------

    /** {@code mysticessentials.home.limit.<n>} — numeric home limit. */
    public static String homeLimit(int n) {
        return HOME_LIMIT_BASE + "." + n;
    }

    /** {@code mysticessentials.playerwarp.limit.<n>} — numeric player-warp limit. */
    public static String playerWarpLimit(int n) {
        return PLAYERWARP_LIMIT_BASE + "." + n;
    }

    /** {@code mysticessentials.kit.<name>} — access to a specific kit. */
    public static String kit(String kitName) {
        return KIT_BASE + "." + kitName.toLowerCase(java.util.Locale.ROOT);
    }

    /** {@code mysticessentials.chat.channel.<id>} — join a specific channel. */
    public static String chatChannel(String channelId) {
        return CHAT_CHANNEL_BASE + "." + channelId.toLowerCase(java.util.Locale.ROOT);
    }
}
