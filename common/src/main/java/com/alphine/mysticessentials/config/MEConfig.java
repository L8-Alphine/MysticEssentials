package com.alphine.mysticessentials.config;

import com.google.gson.*;
import com.google.gson.annotations.SerializedName;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class MEConfig {

    public static MEConfig INSTANCE;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    @SerializedName("locale")
    public String locale = "en_us";

    @SerializedName("cooldowns") public Cooldowns cooldowns = new Cooldowns();
    @SerializedName("warmups")   public Warmups   warmups   = new Warmups();
    @SerializedName("limits")    public Limits    limits    = new Limits();

    // multi-home groups section
    @SerializedName("homes")
    public Homes homes = new Homes();

    @SerializedName("features")  public Features  features  = new Features();
    @SerializedName("permissions") public Permissions permissions = new Permissions();
    @SerializedName("afk") public Afk afk = new Afk();

    private transient Path filePath;

    public static class Cooldowns { public int home=3, warp=3, tp=3, tpa=3, spawn=3; }
    public static class Warmups   { public int home=2, warp=2, tp=2, tpa=2, spawn=2; }
    public static class Limits    { public int defaultHomes = 1; public int maxJumpDistance=64; public int nearRadius=64; }

    // mirrors Essentials-style groups -> count
    public static class Homes {
        /** Used when no permission/group mapping matched */
        public int defaultHomes = 1;

        /** Map of "group name" -> max homes. Example: {"group1":3, "vip":5, "mvp":10} */
        public Map<String, Integer> groups = new LinkedHashMap<>();

        /** Highest numeric node allowed, for convenience (cap). Set 0 for unlimited check range = 64. */
        public int numericCap = 64;
    }

    public static class Features {
        public boolean cancelWarmupOnMove = true;
        public boolean cancelWarmupOnDamage = true;
        public boolean useLuckPermsHomeLimits = true;
        public boolean enableModerationSystem = true;
        public boolean enableMiscCommands = true;
        public boolean enableKits            = true;
        public boolean enableHomesWarpsTP    = true;
        public boolean enableAfkSystem       = true;
    }

    public static class Permissions {
        public String nearExempt = "messentials.near.exempt";
        public String godBypassDamage = "messentials.god.bypass.damage";
        public String cooldownBypass = "messentials.bypass.cooldown";
        public String warmupBypass = "messentials.bypass.warmup";
        public String modBypass = "messentials.mod.bypass";
        // Base for multi-homes: messentials.homes.multiple.<number|group>
        public String homesMultipleBase = "messentials.homes.multiple";
    }

    public static class Afk {
        public boolean enabled = true;

        /** Seconds of inactivity before auto-AFK */
        public int autoAfkSeconds = 240;

        /** If true, only players with messentials.afk.autoenable are auto-AFKed */
        public boolean respectAutoEnablePermission = true;

        /** If true, players with messentials.afk.exempt never get auto-AFK */
        public boolean respectExemptPermission = true;

        /** Idle kick (0 = disabled) */
        public int idleKickSeconds = 0;
        public String idleKickReason = "<red>Kicked for being AFK too long.";

        /** Default AFK message when none is set */
        public String defaultMessage = "<gray>I'm currently AFK.";

        /** Private notify format sent to the pinger when they mention an AFK player */
        public String notifyFormat = "<gray><sender> → <target>: <msg>";

        /** Fallback teleport if return point missing */
        public TeleportPoint fallback = new TeleportPoint("world", 0.5, 64.0, 0.5, 0f, 0f);

        /** How often per-player reward timers are evaluated (seconds) */
        public int rewardTickSeconds = 1;

        /** AFK pools keyed by name */
        public Map<String, AfkPool> pools = new LinkedHashMap<>();
    }

    public static class TeleportPoint {
        public String world;
        public double x,y,z;
        public float yaw,pitch;
        public TeleportPoint() {}
        public TeleportPoint(String world, double x, double y, double z, float yaw, float pitch) {
            this.world = world; this.x=x; this.y=y; this.z=z; this.yaw=yaw; this.pitch=pitch;
        }
    }

    public static class PoolBox {
        public String world;
        public Vec3i min = new Vec3i(0,0,0);
        public Vec3i max = new Vec3i(0,0,0);
    }

    public static class Vec3i { public int x,y,z; public Vec3i(){} public Vec3i(int x,int y,int z){this.x=x;this.y=y;this.z=z;} }

    public static class AfkPool {
        public boolean enabled = true;
        public PoolBox region = new PoolBox();
        public TeleportPoint teleport = new TeleportPoint("world", 0.5, 64.0, 0.5, 0f, 0f);

        /** If true, requires messentials.afk.pool.<name> */
        public boolean requirePermission = true;

        /** Allow chat while AFK in this region (won't auto-unAFK on chat) */
        public boolean allowChatInside = true;

        /** Allow movement while AFK in this region */
        public boolean allowMoveInside = true;

        /** Allow interaction (blocks, entities) while AFK in this region */
        public boolean allowInteractInside = true;

        /** Allow walking out to unAFK */
        public boolean allowEnterExitFreely = true;

        /** Reward tracks */
        public List<AfkReward> rewards = new ArrayList<>();
    }

    public static class AfkReward {
        public String id = "default";
        public int everySeconds = 60;
        public List<String> commands = new ArrayList<>(); // console commands, %player%
        public List<ItemSpec> items = new ArrayList<>();
    }

    public static class ItemSpec {
        /** namespaced material id, e.g. minecraft:emerald or nexo:my_item */
        public String type = "minecraft:stone";
        public int amount = 1;
        /** optional NBT json string */
        public String nbt = "{}";
    }

    public static MEConfig load(Path configDir) {
        try {
            Files.createDirectories(configDir);
            Path file = configDir.resolve("config.json");
            MEConfig cfg;
            if (Files.exists(file)) try (Reader r = Files.newBufferedReader(file)) { cfg = GSON.fromJson(r, MEConfig.class); }
            else {
                cfg = new MEConfig();
                // sensible defaults for groups (example)
                cfg.homes.groups.put("group1", 3);
                cfg.homes.groups.put("vip", 5);
                cfg.homes.groups.put("mvp", 10);
                cfg.save(file);
            }
            cfg.filePath = file;
            INSTANCE = cfg;
            return cfg;
        } catch (IOException e) {
            throw new RuntimeException("[MysticEssentials] Failed to load config!", e);
        }
    }

    public void save() { if (filePath != null) save(filePath); }
    private void save(Path file) {
        try (Writer w = Files.newBufferedWriter(file)) { GSON.toJson(this, w); }
        catch (IOException e) { System.err.println("[MysticEssentials] Failed to save config: " + e.getMessage()); }
    }
    public void reload() {
        if (filePath == null) return;
        try (Reader r = Files.newBufferedReader(filePath)) {
            MEConfig x = GSON.fromJson(r, MEConfig.class);
            if (x != null) {
                this.cooldowns=x.cooldowns; this.warmups=x.warmups; this.limits=x.limits;
                this.homes=x.homes; this.features=x.features; this.permissions=x.permissions;
                this.afk=x.afk;
            }
        } catch (IOException e) { System.err.println("[MysticEssentials] Failed to reload config: " + e.getMessage()); }
    }

    /**
     * Utility: Get cooldown seconds by key.
     */
    public int getCooldown(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "home" -> cooldowns.home;
            case "warp" -> cooldowns.warp;
            case "tp" -> cooldowns.tp;
            case "tpa" -> cooldowns.tpa;
            case "spawn" -> cooldowns.spawn;
            default -> 0;
        };
    }

    /**
     * Utility: Get warmup seconds by key.
     */
    public int getWarmup(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "home" -> warmups.home;
            case "warp" -> warmups.warp;
            case "tp" -> warmups.tp;
            case "tpa" -> warmups.tpa;
            case "spawn" -> warmups.spawn;
            default -> 0;
        };
    }
}
