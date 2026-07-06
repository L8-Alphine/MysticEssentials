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
}
