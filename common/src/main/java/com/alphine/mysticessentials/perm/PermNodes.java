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
    public static final String DEATHBACK_USE= "messentials.deathback.use";

    // ========= Misc =========
    public static final String CREATIVE_USE     = "messentials.creative.use";
    public static final String HEAL_USE          = "messentials.heal.use";
    public static final String FEED_USE          = "messentials.feed.use";
    public static final String TIME_SET          = "messentials.time.set";
    public static final String WEATHER_SET       = "messentials.weather.set";
    public static final String DAY_USE           = "messentials.day.use";
    public static final String NIGHT_USE         = "messentials.night.use";
    public static final String RAIN_USE          = "messentials.rain.use";
    public static final String CLEAR_WEATHER_USE = "messentials.clearweather.use";
    public static final String FLY_USE           = "messentials.fly.use";
    public static final String GOD_USE           = "messentials.god.use";
    public static final String SPEED_USE         = "messentials.speed.use";
    public static final String INVSEE_USE        = "messentials.invsee.use";
    public static final String INVSEE_EDIT   = "messentials.invsee.edit";
    public static final String INVSEE_EXEMPT = "messentials.invsee.exempt";
    public static final String WORKBENCH_USE     = "messentials.workbench.use";
    public static final String ANVIL_USE         = "messentials.anvil.use";
    public static final String ENCHANT_USE       = "messentials.enchant.use";
    public static final String NEAR_USE          = "messentials.near.use";

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
