package org.hyzionstudios.mysticessentials.core.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed model of {@code mods/MysticEssentials/config.json}. Field names map
 * directly to JSON keys via Gson. Defaults here define the file that is written
 * on first run.
 */
public final class MainConfig {

    public int configVersion = 1;
    public Storage storage = new Storage();
    public Integrations integrations = new Integrations();
    public UpdateNotifier updateNotifier = new UpdateNotifier();
    public PlayerList playerList = new PlayerList();
    public Map<String, Boolean> modules = defaultModules();

    private static Map<String, Boolean> defaultModules() {
        Map<String, Boolean> map = new LinkedHashMap<>();
        map.put("mail", true);
        map.put("spawn", true);
        map.put("teleportation", true);
        map.put("warps", true);
        map.put("announcements", true);
        map.put("afk", true);
        map.put("chat", true);
        map.put("greetings", true);
        map.put("kits", true);
        map.put("flight", true);
        map.put("inventory", true);
        map.put("nick", true);
        map.put("patchnotes", true);
        map.put("portals", true);
        // Tutorial ships disabled: enable here AND in modules/tutorial/config.json.
        map.put("tutorial", false);
        // Custom Commands ships disabled: enable here AND in modules/customcommands/config.json.
        map.put("customcommands", false);
        // Player Vaults ships disabled: enable here AND in modules/playervaults/config.json.
        map.put("playervaults", false);
        // CustomGUIs & CustomDialogs ships disabled.
        map.put("customcontent", false);
        return map;
    }

    /** @return whether a module is enabled; unknown modules default to {@code false}. */
    public boolean isModuleEnabled(String id) {
        return modules != null && modules.getOrDefault(id, false);
    }

    public static final class Storage {
        public String provider = "json";
        public Mysql mysql = new Mysql();
        public Redis redis = new Redis();
    }

    public static final class Mysql {
        public String host = "localhost";
        public int port = 3306;
        public String database = "mystic_essentials";
        public String username = "root";
        public String password = "password";
        public int poolSize = 10;
    }

    public static final class Redis {
        public boolean enabled = false;
        public String host = "localhost";
        public int port = 6379;
        public String password = "";
        public String serverId = "survival-1";
        public String networkId = "mystic-network";
    }

    public static final class Integrations {
        public boolean luckPerms = true;
        public boolean placeholderAPI = true;
        public boolean vaultUnlocked = true;
        public boolean mysticVanish = true;
        public boolean mysticModeration = true;
    }

    /** Core update checks and permission-gated join notifications. */
    public static final class UpdateNotifier {
        public boolean enabled = true;
        public boolean notifyOnJoin = true;
        public int checkIntervalHours = 12;
    }

    /**
     * Decoration of the names shown in the client's <b>Server Players</b> list
     * (the map screen roster). The client renders that field as plain text, so
     * colour/format markup in the resolved name is stripped before it is sent.
     */
    public static final class PlayerList {
        /** Master switch. When off, the engine's plain usernames are left alone. */
        public boolean enabled = true;
        /**
         * The listed name. {@code {display_name}} is the nickname set through
         * {@code /nick} falling back to the username; {@code {player_name}} is
         * always the real username. Any registered Mystic placeholder works,
         * including {@code {luckperms_prefix}}, {@code {luckperms_suffix}} and
         * {@code {group}}.
         */
        public String format = "{luckperms_prefix}{display_name}{luckperms_suffix}";
        /** Marks AFK players in the list (requires the {@code afk} module). */
        public boolean showAfk = true;
        /** Applied to AFK players; {@code {name}} is the name produced by {@link #format}. */
        public String afkFormat = "{name} (AFK)";
        /** How often names are recomputed and changes pushed, in seconds. */
        public int refreshSeconds = 5;
        /**
         * Sends a remove-then-add pair when replacing an entry instead of a bare
         * add. Keep this on unless a client build is seen to flicker: it is the
         * only form that is correct whether the client upserts list entries by
         * UUID or appends them.
         */
        public boolean rebuildEntries = true;
    }
}
