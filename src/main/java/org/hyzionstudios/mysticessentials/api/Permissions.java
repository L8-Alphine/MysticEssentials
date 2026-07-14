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
    public static final String TELEPORT_TP = "mysticessentials.teleport.tp";
    public static final String TELEPORT_TPHERE = "mysticessentials.teleport.tphere";
    public static final String TELEPORT_TPALL = "mysticessentials.teleport.tpall";
    public static final String TELEPORT_TOP = "mysticessentials.teleport.top";
    public static final String TELEPORT_BACK = "mysticessentials.teleport.back";
    public static final String TELEPORT_BYPASS_WARMUP = "mysticessentials.teleport.bypass.warmup";
    public static final String TELEPORT_BYPASS_COOLDOWN = "mysticessentials.teleport.bypass.cooldown";

    // ----- Random Teleport (Teleportation subsystem) --------------------------

    public static final String RTP_USE = "mysticessentials.teleport.rtp.use";
    public static final String RTP_CANCEL = "mysticessentials.teleport.rtp.cancel";
    public static final String RTP_STATUS = "mysticessentials.teleport.rtp.status";
    public static final String RTP_BIOME = "mysticessentials.teleport.rtp.biome";
    public static final String RTP_OTHERS = "mysticessentials.teleport.rtp.others";
    public static final String RTP_BYPASS_WARMUP = "mysticessentials.teleport.rtp.bypass.warmup";
    public static final String RTP_BYPASS_COOLDOWN = "mysticessentials.teleport.rtp.bypass.cooldown";
    public static final String RTP_BYPASS_COST = "mysticessentials.teleport.rtp.bypass.cost";
    public static final String RTP_BYPASS_COMBAT = "mysticessentials.teleport.rtp.bypass.combat";
    public static final String RTP_BYPASS_QUEUE = "mysticessentials.teleport.rtp.bypass.queue";
    public static final String RTP_BYPASS_LIMITS = "mysticessentials.teleport.rtp.bypass.limits";
    /** Grants every {@code mysticessentials.teleport.rtp.admin.*} node. */
    public static final String RTP_ADMIN = "mysticessentials.teleport.rtp.admin";
    public static final String RTP_ADMIN_RELOAD = "mysticessentials.teleport.rtp.admin.reload";
    public static final String RTP_ADMIN_EDIT = "mysticessentials.teleport.rtp.admin.edit";
    public static final String RTP_ADMIN_TEST = "mysticessentials.teleport.rtp.admin.test";
    public static final String RTP_ADMIN_DEBUG = "mysticessentials.teleport.rtp.admin.debug";
    public static final String RTP_ADMIN_CANCEL = "mysticessentials.teleport.rtp.admin.cancel";
    public static final String RTP_ADMIN_FORCE = "mysticessentials.teleport.rtp.admin.force";
    public static final String RTP_ADMIN_SPREAD = "mysticessentials.teleport.rtp.admin.spread";
    /** Dynamic: {@code mysticessentials.teleport.rtp.world.<world>}. */
    public static final String RTP_WORLD_BASE = "mysticessentials.teleport.rtp.world";
    /** Dynamic: {@code mysticessentials.teleport.rtp.profile.<profile>}. */
    public static final String RTP_PROFILE_BASE = "mysticessentials.teleport.rtp.profile";
    /** Dynamic: {@code mysticessentials.teleport.rtp.cooldown.<seconds>}. */
    public static final String RTP_COOLDOWN_BASE = "mysticessentials.teleport.rtp.cooldown";
    /** Dynamic: {@code mysticessentials.teleport.rtp.limit.daily.<n>}. */
    public static final String RTP_LIMIT_DAILY_BASE = "mysticessentials.teleport.rtp.limit.daily";
    /** Dynamic: {@code mysticessentials.teleport.rtp.limit.hourly.<n>}. */
    public static final String RTP_LIMIT_HOURLY_BASE = "mysticessentials.teleport.rtp.limit.hourly";
    /** Dynamic: {@code mysticessentials.teleport.rtp.priority.<n>} — queue/profile priority. */
    public static final String RTP_PRIORITY_BASE = "mysticessentials.teleport.rtp.priority";

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
    /** Attach items (consumed from the sender's inventory) to normal mail. */
    public static final String MAIL_ATTACH_ITEMS = "mysticessentials.mail.attach";
    /** Send admin announcements carrying item and command rewards. */
    public static final String MAIL_ANNOUNCE = "mysticessentials.mail.announce";

    // ----- Patch Notes -----------------------------------------------------------

    /** Open and read patch notes. */
    public static final String PATCHNOTES_VIEW = "mysticessentials.patchnotes.view";
    /** Grants every {@code mysticessentials.patchnotes.*} admin node. */
    public static final String PATCHNOTES_ADMIN = "mysticessentials.patchnotes.admin";
    public static final String PATCHNOTES_RELOAD = "mysticessentials.patchnotes.reload";
    public static final String PATCHNOTES_OPEN_OTHERS = "mysticessentials.patchnotes.open.others";
    public static final String PATCHNOTES_MARKREAD_OTHERS = "mysticessentials.patchnotes.markread.others";

    // ----- Announcements ---------------------------------------------------------

    public static final String ANNOUNCEMENT_BROADCAST = "mysticessentials.announcement.broadcast";
    public static final String ANNOUNCEMENT_ALERT = "mysticessentials.announcement.alert";

    // ----- AFK -------------------------------------------------------------------

    public static final String AFK_USE = "mysticessentials.afk.use";
    public static final String AFK_BYPASS_AUTO = "mysticessentials.afk.bypass.auto";
    public static final String AFK_REWARDS = "mysticessentials.afk.rewards";
    public static final String AFK_ZONE_ADMIN = "mysticessentials.afk.zone.admin";

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

    // ----- Tutorial -------------------------------------------------------------------

    /** Grants every {@code mysticessentials.tutorial.*} node. */
    public static final String TUTORIAL_ADMIN = "mysticessentials.tutorial.admin";
    public static final String TUTORIAL_LIST = "mysticessentials.tutorial.list";
    public static final String TUTORIAL_INFO = "mysticessentials.tutorial.info";
    public static final String TUTORIAL_PLAY = "mysticessentials.tutorial.play";
    public static final String TUTORIAL_PLAY_OTHERS = "mysticessentials.tutorial.play.others";
    public static final String TUTORIAL_STOP = "mysticessentials.tutorial.stop";
    public static final String TUTORIAL_STOP_OTHERS = "mysticessentials.tutorial.stop.others";
    public static final String TUTORIAL_SKIP = "mysticessentials.tutorial.skip";
    public static final String TUTORIAL_SKIP_OTHERS = "mysticessentials.tutorial.skip.others";
    public static final String TUTORIAL_RESET = "mysticessentials.tutorial.reset";
    public static final String TUTORIAL_COMPLETE = "mysticessentials.tutorial.complete";
    public static final String TUTORIAL_STATUS = "mysticessentials.tutorial.status";
    public static final String TUTORIAL_STATUS_OTHERS = "mysticessentials.tutorial.status.others";
    public static final String TUTORIAL_PAGE = "mysticessentials.tutorial.page";
    public static final String TUTORIAL_PAGE_OTHERS = "mysticessentials.tutorial.page.others";
    public static final String TUTORIAL_RELOAD = "mysticessentials.tutorial.reload";
    public static final String TUTORIAL_DEBUG = "mysticessentials.tutorial.debug";
    public static final String TUTORIAL_SCENE = "mysticessentials.tutorial.scene";
    public static final String TUTORIAL_BYPASS_FIRSTJOIN = "mysticessentials.tutorial.bypassfirstjoin";

    // ----- Custom Commands ---------------------------------------------------------------

    /** Grants every {@code mysticessentials.customcommands.*} admin node. */
    public static final String CUSTOMCOMMANDS_ADMIN = "mysticessentials.customcommands.admin";
    public static final String CUSTOMCOMMANDS_LIST = "mysticessentials.customcommands.list";
    public static final String CUSTOMCOMMANDS_INFO = "mysticessentials.customcommands.info";
    public static final String CUSTOMCOMMANDS_RELOAD = "mysticessentials.customcommands.reload";
    /** Gates {@code /customcommands enable|disable}. */
    public static final String CUSTOMCOMMANDS_MANAGE = "mysticessentials.customcommands.manage";
    public static final String CUSTOMCOMMANDS_TEST = "mysticessentials.customcommands.test";
    public static final String CUSTOMCOMMANDS_VALIDATE = "mysticessentials.customcommands.validate";
    /** Bypasses every custom command cooldown. */
    public static final String CUSTOMCOMMANDS_BYPASS_COOLDOWN =
            "mysticessentials.customcommands.bypass.cooldown";
    /** Dynamic: {@code mysticessentials.customcommands.command.<name>} — implicit per-command node. */
    public static final String CUSTOMCOMMANDS_COMMAND_BASE = "mysticessentials.customcommands.command";

    // ----- Player Vaults ---------------------------------------------------------------

    public static final String VAULTS_COMMAND = "mysticessentials.vaults.command";
    public static final String VAULTS_COMMAND_OPEN = "mysticessentials.vaults.command.open";
    public static final String VAULTS_COMMAND_EDIT = "mysticessentials.vaults.command.edit";
    public static final String VAULTS_COMMAND_LIST = "mysticessentials.vaults.command.list";
    public static final String VAULTS_COMMAND_RELOAD = "mysticessentials.vaults.command.reload";
    /** Dynamic: {@code mysticessentials.vaults.vault.<n>} — highest accessible vault number. */
    public static final String VAULTS_VAULT_BASE = "mysticessentials.vaults.vault";
    /** Dynamic: {@code mysticessentials.vaults.rows.<n>} — rows per vault (capped by config maxRows). */
    public static final String VAULTS_ROWS_BASE = "mysticessentials.vaults.rows";
    public static final String VAULTS_EDITOR = "mysticessentials.vaults.editor";
    public static final String VAULTS_EDITOR_NAME = "mysticessentials.vaults.editor.name";
    public static final String VAULTS_EDITOR_COLOR = "mysticessentials.vaults.editor.color";
    public static final String VAULTS_EDITOR_COLOR_HEX = "mysticessentials.vaults.editor.color.hex";
    public static final String VAULTS_EDITOR_ICON = "mysticessentials.vaults.editor.icon";
    public static final String VAULTS_EDITOR_DESCRIPTION = "mysticessentials.vaults.editor.description";
    public static final String VAULTS_EDITOR_RESET = "mysticessentials.vaults.editor.reset";
    /** Grants every {@code mysticessentials.vaults.admin.*} node. */
    public static final String VAULTS_ADMIN = "mysticessentials.vaults.admin";
    public static final String VAULTS_ADMIN_OPEN = "mysticessentials.vaults.admin.open";
    public static final String VAULTS_ADMIN_OPEN_OFFLINE = "mysticessentials.vaults.admin.open.offline";
    public static final String VAULTS_ADMIN_EDIT = "mysticessentials.vaults.admin.edit";
    public static final String VAULTS_ADMIN_READONLY = "mysticessentials.vaults.admin.readonly";
    public static final String VAULTS_ADMIN_UNLOCK = "mysticessentials.vaults.admin.unlock";
    public static final String VAULTS_ADMIN_RESTORE = "mysticessentials.vaults.admin.restore";
    public static final String VAULTS_ADMIN_VIEWLOGS = "mysticessentials.vaults.admin.viewlogs";
    /** View/recover storage beyond the owner's current row/vault limits (overflow). */
    public static final String VAULTS_ADMIN_BYPASSLIMIT = "mysticessentials.vaults.admin.bypasslimit";

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

    /**
     * {@code mysticessentials.customcommands.command.<name>} — implicit use node
     * for a custom command with permission mode {@code single} and no explicit node.
     */
    public static String customCommand(String commandName) {
        return CUSTOMCOMMANDS_COMMAND_BASE + "." + commandName.toLowerCase(java.util.Locale.ROOT);
    }

    /** {@code mysticessentials.customcommands.bypass.cooldown.<name>} — per-command cooldown bypass. */
    public static String customCommandCooldownBypass(String commandName) {
        return CUSTOMCOMMANDS_BYPASS_COOLDOWN + "." + commandName.toLowerCase(java.util.Locale.ROOT);
    }

    /** {@code mysticessentials.vaults.vault.<n>} — highest accessible vault number. */
    public static String vaultNumber(int n) {
        return VAULTS_VAULT_BASE + "." + n;
    }

    /** {@code mysticessentials.vaults.rows.<n>} — rows exposed per vault. */
    public static String vaultRows(int n) {
        return VAULTS_ROWS_BASE + "." + n;
    }

    /** {@code mysticessentials.teleport.rtp.world.<world>} — permission to RTP in a world. */
    public static String rtpWorld(String world) {
        return RTP_WORLD_BASE + "." + world.toLowerCase(java.util.Locale.ROOT);
    }

    /** {@code mysticessentials.teleport.rtp.profile.<profile>} — permission to use a profile. */
    public static String rtpProfile(String profile) {
        return RTP_PROFILE_BASE + "." + profile.toLowerCase(java.util.Locale.ROOT);
    }
}
