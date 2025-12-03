package com.alphine.mysticessentials.perm;

import java.util.Locale;

public final class PermNodes {
    public static final String JUMP_USE = "messentials.jump.use";

    private PermNodes() { }

    // ========= Homes =========
    public static final String HOME_SET   = "messentials.home.set";
    public static final String HOME_USE   = "messentials.home.use";
    public static final String HOME_DEL   = "messentials.home.del";
    public static final String HOME_LIST  = "messentials.home.list";
    public static final String HOME_OTHERS= "messentials.home.others"; // /home <home> <player>
    public static final String HOME_MULTIPLE = "messentials.home.multiple"; // allows multiple homes with append (e.g., +.group, +.5)

    // ========= Warps / Spawn =========
    public static final String WARP_USE   = "messentials.warp.use";
    public static final String WARP_SET   = "messentials.warp.set";
    public static final String WARP_DEL   = "messentials.warp.del";
    public static final String WARP_LIST  = "messentials.warp.list";

    public static final String SPAWN_USE  = "messentials.spawn.use";
    public static final String SPAWN_SET  = "messentials.spawn.set";

    // ========= Teleportation =========
    public static final String TP_USE       = "messentials.tp.use";        // /tp <player>
    public static final String TPHERE_USE   = "messentials.tphere.use";    // /tphere <player>
    public static final String TPO_USE      = "messentials.tpo.use";       // /tpo <offlineName>
    public static final String TPA_USE      = "messentials.tpa.use";
    public static final String TPACCEPT_USE = "messentials.tpaccept.use";
    public static final String TPDENY_USE   = "messentials.tpdeny.use";
    public static final String TPAUTO_USE   = "messentials.tpauto.use";
    public static final String TPP_TOGGLE   = "messentials.tptoggle.use";  // privacy toggle
    public static final String BACK_USE     = "messentials.back.use";
    public static final String DEATHBACK_USE = "messentials.deathback.use";
    public static final String TELEPORT_COOLDOWN = "messentials.teleport.cooldown";
    public static final String TELEPORT_COOLDOWN_BYPASS = "messentials.teleport.cooldown.bypass";
    public static final String TELEPORT_WARMUP = "messentials.teleport.warmup";
    public static final String TELEPORT_WARMUP_BYPASS = "messentials.teleport.warmup.bypass";

    // ========= Misc =========
    public static final String GAMEMODE_USE    = "messentials.gamemode";
    public static final String GAMEMODE_CREATIVE = "messentials.gamemode.creative";
    public static final String GAMEMODE_ADVENTURE = "messentials.gamemode.adventure";
    public static final String GAMEMODE_SURVIVAL = "messentials.gamemode.survival";
    public static final String GAMEMODE_SPECTATOR = "messentials.gamemode.spectator";
    public static final String GAMEMODE_OTHERS = "messentials.gamemode.others";
    public static final String HEAL_USE          = "messentials.heal";
    public static final String HEAL_USE_OTHERS = "messentials.heal.others";
    public static final String FEED_USE          = "messentials.feed";
    public static final String FEED_USE_OTHERS = "messentials.feed.others";
    public static final String TIME_SET          = "messentials.time.set";
    public static final String WEATHER_SET       = "messentials.weather.set";
    public static final String DAY_USE           = "messentials.day.use";
    public static final String NIGHT_USE         = "messentials.night.use";
    public static final String RAIN_USE          = "messentials.rain.use";
    public static final String CLEAR_WEATHER_USE = "messentials.clearweather.use";
    public static final String FLY_USE           = "messentials.fly";
    public static final String FLY_SAFE_LOGIN = "messentials.fly.safelogon";
    public static final String FLY_USE_OTHERS = "messentials.fly.others";
    public static final String GOD_USE           = "messentials.god";
    public static final String GOD_USE_OTHERS = "messentials.god.others";
    public static final String SPEED_USE         = "messentials.speed";
    public static final String INVSEE_USE        = "messentials.invsee";
    public static final String INVSEE_EDIT   = "messentials.invsee.edit";
    public static final String INVSEE_EDIT_OFFLINE = "messentials.invsee.edit.offline";
    public static final String INVSEE_EXEMPT = "messentials.invsee.exempt";
    public static final String WORKBENCH_USE     = "messentials.workbench.use";
    public static final String ANVIL_USE         = "messentials.anvil.use";
    public static final String ENCHANT_USE       = "messentials.enchant.use";
    public static final String NEAR_USE          = "messentials.near.use";
    public static final String PLAYTIME = "messentials.playtime";
    public static final String PLAYTIME_OTHERS = "messentials.playtime.others";
    public static final String PLAYTIME_ADMIN = "messentials.playtime.admin";

    // Enderchest Access
    public static final String ENDERCHEST_USE        = "messentials.enderchest";
    public static final String ENDERCHEST_OTHERS     = "messentials.enderchest.others";
    public static final String ENDERCHEST_OTHERS_MODIFY = "messentials.enderchest.others.modify";

    // Vault System
    public static final String VAULT_USE = "messentials.vault";
    public static final String VAULT_OTHERS = "messentials.vault.others";
    public static final String VAULT_AMOUNT_BASE = "messentials.vault.amount"; // append with .<number>
    public static String vaultAmountNode(int amount) { return VAULT_AMOUNT_BASE + "." + amount; }
    public static final String VAULT_ROW_BASE = "messentials.vault.row"; // append with .<rowcode> Only 1 - 6
    public static String vaultSizeNode(String rowCode) { return VAULT_ROW_BASE + "." + rowCode.toLowerCase(Locale.ROOT); }
    public static final String VAULT_ITEM_ALLOW_BASE = "messentials.vault.item"; // append with .<itemid>
    public static String vaultItemAllowNode(String itemId) { return VAULT_ITEM_ALLOW_BASE + "." + itemId.toLowerCase(Locale.ROOT).replace(":", "_"); }
    public static final String VAULT_RENAME = "messentials.vault.rename";
    public static final String VAULT_RENAME_COLOR_BASE = "messentials.vault.rename.color";
    public static String vaultRenameColorNode(char code) { return VAULT_RENAME_COLOR_BASE + "." + Character.toLowerCase(code); }
    public static final String VAULT_RENAME_FORMAT_BASE = "messentials.vault.rename.format";
    public static String vaultRenameFormatNode(char code) { return VAULT_RENAME_FORMAT_BASE + "." + Character.toLowerCase(code); }
    public static final String VAULT_RESET = "messentials.vault.reset"; // allows resetting vaults - includes clearing all items and renaming back to default
    public static final String VAULT_RESET_EXEMPT = "messentials.vault.reset.exempt"; // exempts you from having your vault reset by other admins
    public static final String VAULT_RESET_ALL = "messentials.vault.reset.all"; // allows resetting all vaults on the server

    // Chat System
    public static final String MSG_SEND = "messentials.msg.send";
    public static final String MSG_RECEIVE = "messentials.msg.receive";
    public static final String MSG_SPY = "messentials.msg.spy";
    public static final String CHAT_COLOR_BASE = "messentials.chat.color"; // append with .<colorcode>
    public static String chatColorNode(char code) { return CHAT_COLOR_BASE + "." + Character.toLowerCase(code); }
    public static final String CHAT_FORMAT_BASE = "messentials.chat.format"; // append with .<formatcode>
    public static String chatFormatNode(char code) { return CHAT_FORMAT_BASE + "." + Character.toLowerCase(code); }
    public static final String CHAT_CHANNEL_BASE = "messentials.chat.channel"; // append with .<channelid>
    public static String chatChannelNode(String channelId) { return CHAT_CHANNEL_BASE + "." + channelId.toLowerCase(Locale.ROOT); }
    public static final String CHAT_COLOR_ALL  = "messentials.chat.color.*";
    public static final String CHAT_FORMAT_ALL = "messentials.chat.format.*";
    public static final String CHAT_CHANNEL_ALL = "messentials.chat.channel.*";
    public static final String CHAT_BROADCAST = "messentials.chat.broadcast"; // allows broadcasting messages to all players
    public static final String CHAT_SHOUT = "messentials.chat.shout"; // allows shouting messages to nearby players
    public static final String CHAT_CLEAR = "messentials.chat.clear"; // allows clearing the chat for all players

    // Repair System
    public static final String REPAIR_USE        = "messentials.repair";
    public static final String REPAIR_ENCHANTED  = "messentials.repair.enchanted";

    // Kill System
    public static final String KILL_USE          = "messentials.kill";
    public static final String KILL_PLAYERS      = "messentials.kill.players"; // players only
    public static final String KILL_MOBS         = "messentials.kill.mobs"; // living entities except players
    public static final String KILL_ENTITIES     = "messentials.kill.entities"; // all entities, including items, vehicles, etc.
    public static final String KILL_ALL          = "messentials.kill.all"; // all entities including players
    public static final String KILL_EXEMPT       = "messentials.kill.exempt"; // exempts you from being killed by /kill all

    // Sleep Permission
    public static final String SLEEP = "messentials.sleep"; // allows the use of the command /rest to reset your insomnia timer

    // Recipe
    public static final String RECIPE_USE        = "messentials.recipe"; // allows the use of /recipe <item> to view the recipe of an item

    // Item Rename System
    public static final String RENAME_USE        = "messentials.rename"; // allows the use of /rename <name> to rename the item in your hand - Includes color codes
    public static final String RENAME_LORE       = "messentials.rename.lore"; // allows the use of /lore [line number] <lore> to set the lore of the item in your hand - Includes color codes
    public static final String RENAME_ENCHANTED  = "messentials.rename.enchanted"; // allows renaming/setting lore on enchanted items
    public static final String RENAME_UNIQUE     = "messentials.rename.unique"; // allows renaming/setting lore on items with custom NBT data


    // AFK System
    public static final String AFK_USE           = "messentials.afk.use";
    public static final String AFK_EXEMPT        = "messentials.afk.exempt";
    public static final String AFK_KICK_BYPASS   = "messentials.afk.kickbypass";
    public static final String AFK_MESSAGE_SET   = "messentials.afk.message.set"; // Allows you to set a custom AFK message if pinged in chat
    public static final String AFK_MESSAGE_USE   = "messentials.afk.message.use"; // Allows you to see custom AFK messages when pinging someone in chat
    public static final String AFK_MESSAGE_EXEMPT = "messentials.afk.message.exempt"; // Exempts you from being seen as AFK when pinged in chat
    public static final String AFK_AUTO_ENABLE   = "messentials.afk.autoenable"; // Allows automatic AFK status after a period of inactivity
    public static final String AFK_SET_POOL     = "messentials.afk.pool.set"; // Allows setting AFK pools for random teleportation
    public static final String AFK_POOL_BASE   = "messentials.afk.pool"; // Base permission for AFK pools (append with .<poolname>)
    public static String afkPoolNode(String pool) { return AFK_POOL_BASE + "." + pool.toLowerCase(Locale.ROOT); }

    // ========= Kits =========
    public static final String KIT_LIST                  = "messentials.kits";                  // /kits (list)
    public static final String KIT_CREATE                = "messentials.kits.create";           // /createkit
    public static final String KIT_DELETE                = "messentials.kits.delete";           // /delkit
    public static final String KIT_OTHERS                = "messentials.kits.others";           // /kits <kits> <player>
    public static final String KIT_BYPASS_COOLDOWN       = "messentials.kits.bypass.cooldown";
    public static final String KIT_BYPASS_COOLDOWN_OTHERS= "messentials.kits.bypass.cooldown.others";
    // Per-kits access gate: "messentials.kits.<kitname>" (lowercased)
    public static String kitNode(String kit) { return "messentials.kits." + kit.toLowerCase(java.util.Locale.ROOT); }

    // ========= Moderation =========
    public static final String FREEZE_USE    = "messentials.freeze.use";
    public static final String JAIL_USE      = "messentials.jail.use";
    public static final String JAIL_SET      = "messentials.jail.set";
    public static final String JAIL_DEL      = "messentials.jail.del";
    public static final String MOD_RELOAD    = "messentials.mod.reload";
    public static final String KICK_USE      = "messentials.kick.use";
    public static final String BAN_USE       = "messentials.ban.use";
    public static final String TEMPBAN_USE   = "messentials.tempban.use";
    public static final String IPBAN_USE     = "messentials.ipban.use";
    public static final String TEMIPBAN_USE  = "messentials.tempipban.use";
    public static final String BANLIST_USE   = "messentials.banlist.use";
    public static final String BAN_EXEMPT_BASE = "messentials.ban.exempt"; // append with .tempban, .ipban, etc.
    public static final String UNBAN_USE     = "messentials.unban.use";
    public static final String MUTE_USE      = "messentials.mute.use";
    public static final String UNMUTE_USE    = "messentials.unmute.use";
    public static final String WARN_USE      = "messentials.warn.use";
    public static final String PARDON_USE    = "messentials.pardon.use"; // remove warnings
    public static final String HISTORY_USE   = "messentials.history.use";

    // ----- Warnings -----
    /** View your own warnings via `/warnings list` (or `/warnings`). */
    public static final String WARNINGS_LIST_SELF    = "messentials.warnings.list.self";
    /** View another player’s warnings via `/warnings list <player>`. */
    public static final String WARNINGS_LIST_OTHERS  = "messentials.warnings.list.others";
    /** Clear warnings (self/others depending on command rules). */
    public static final String WARNINGS_CLEAR        = "messentials.warnings.clear";

    // Back-compat / generic aliases (useful if commands already check these)
    public static final String WARNINGS_USE          = "messentials.warnings.use";      // generic access to /warnings root
    public static final String WARNINGS_LIST         = "messentials.warnings.list";     // alias for list permission


    // ========= Catch-alls =========
    public static final String ADMIN = "messentials.admin";
    public static final String ALL   = "messentials.*";
}
