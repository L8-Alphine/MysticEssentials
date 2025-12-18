package com.alphine.mysticessentials.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class MEConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
    public static MEConfig INSTANCE;
    @SerializedName("locale")
    public String locale = "en_us";

    @SerializedName("cooldowns")
    public Cooldowns cooldowns = new Cooldowns();
    @SerializedName("warmups")
    public Warmups warmups = new Warmups();
    @SerializedName("limits")
    public Limits limits = new Limits();
    @SerializedName("homes")
    public Homes homes = new Homes();
    @SerializedName("features")
    public Features features = new Features();
    @SerializedName("permissions")
    public Permissions permissions = new Permissions();
    @SerializedName("afk")
    public Afk afk = new Afk();
    @SerializedName("chat")
    public Chat chat = new Chat();
    @SerializedName("vaults")
    public Vaults vaults = new Vaults();

    private transient Path filePath;

    // ------------------------------------------------------------
    // BASIC STRUCTS
    // ------------------------------------------------------------

    public static MEConfig load(Path configDir) {
        try {
            Files.createDirectories(configDir);
            Path file = configDir.resolve("config.json");
            MEConfig cfg;
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file)) {
                    cfg = GSON.fromJson(r, MEConfig.class);
                }
            } else {
                cfg = new MEConfig();
                // sensible defaults for groups (example)
                cfg.homes.groups.put("group1", 3);
                cfg.homes.groups.put("vip", 5);
                cfg.homes.groups.put("mvp", 10);
                cfg.save(file);
            }
            cfg.filePath = file;
            INSTANCE = cfg;

            // Load chat module files
            ChatConfigManager.loadAll(configDir, cfg.chat.files);

            return cfg;
        } catch (IOException e) {
            throw new RuntimeException("[MysticEssentials] Failed to load config!", e);
        }
    }

    public void save() {
        if (filePath != null) save(filePath);
    }

    private void save(Path file) {
        try (Writer w = Files.newBufferedWriter(file)) {
            GSON.toJson(this, w);
        } catch (IOException e) {
            System.err.println("[MysticEssentials] Failed to save config: " + e.getMessage());
        }
    }

    public void reload() {
        if (filePath == null) return;
        try (Reader r = Files.newBufferedReader(filePath)) {
            MEConfig x = GSON.fromJson(r, MEConfig.class);
            if (x != null) {
                this.cooldowns = x.cooldowns;
                this.warmups = x.warmups;
                this.limits = x.limits;
                this.homes = x.homes;
                this.features = x.features;
                this.permissions = x.permissions;
                this.afk = x.afk;
                this.chat = x.chat;
            }
        } catch (IOException e) {
            System.err.println("[MysticEssentials] Failed to reload config: " + e.getMessage());
        }
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
            case "back" -> cooldowns.back;
            case "deathback" -> cooldowns.deathback;
            case "tpo" -> cooldowns.tpo;
            case "tphere" -> cooldowns.tphere;
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
            case "back" -> warmups.back;
            case "deathback" -> warmups.deathback;
            case "tpo" -> warmups.tpo;
            case "tphere" -> warmups.tphere;
            default -> 0;
        };
    }

    // ------------------------------------------------------------
    // AFK CONFIG
    // ------------------------------------------------------------

    public static class Cooldowns {
        public int home = 3, warp = 3, tp = 3, tpa = 3, spawn = 3, back = 300, deathback = 300, tpo = 3, tphere = 3;
    }

    public static class Warmups {
        public int home = 2, warp = 2, tp = 2, tpa = 2, spawn = 2, back = 5, deathback = 5, tpo = 2, tphere = 2;
    }

    public static class Limits {
        public int defaultHomes = 1;
        public int maxJumpDistance = 64;
        public int nearRadius = 64;
    }

    // mirrors Essentials-style groups -> count
    public static class Homes {
        /**
         * Used when no permission/group mapping matched
         */
        public int defaultHomes = 1;

        /**
         * Map of "group name" -> max homes. Example: {"group1":3, "vip":5, "mvp":10}
         */
        public Map<String, Integer> groups = new LinkedHashMap<>();

        /**
         * Highest numeric node allowed, for convenience (cap). Set 0 for unlimited check range = 64.
         */
        public int numericCap = 64;
    }

    public static class Features {
        public boolean cancelWarmupOnMove = true;
        public boolean cancelWarmupOnDamage = true;
        public boolean useLuckPermsHomeLimits = true;
        public boolean enableModerationSystem = true;
        public boolean enableMiscCommands = true;
        public boolean enableKits = true;
        public boolean enableHomesWarpsTP = true;
        public boolean enableAfkSystem = true;
        public boolean enableChatSystem = true;
        public boolean enableVaultSystem = true;
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

        /**
         * Seconds of inactivity before auto-AFK
         */
        public int autoAfkSeconds = 240;

        /**
         * If true, only players with messentials.afk.autoenable are auto-AFKed
         */
        public boolean respectAutoEnablePermission = true;

        /**
         * If true, players with messentials.afk.exempt never get auto-AFK
         */
        public boolean respectExemptPermission = true;

        /**
         * Idle kick (0 = disabled)
         */
        public int idleKickSeconds = 0;
        public String idleKickReason = "<red>Kicked for being AFK too long.";

        /**
         * Default AFK message when none is set
         */
        public String defaultMessage = "<gray>I'm currently AFK.";

        /**
         * Private notify format sent to the pinger when they mention an AFK player
         */
        public String notifyFormat = "<gray><sender> → <target>: <msg>";

        /**
         * Fallback teleport if return point missing
         */
        public TeleportPoint fallback = new TeleportPoint("world", 0.5, 64.0, 0.5, 0f, 0f);

        /**
         * How often per-player reward timers are evaluated (seconds)
         */
        public int rewardTickSeconds = 1;

        /**
         * AFK pools keyed by name
         */
        public Map<String, AfkPool> pools = new LinkedHashMap<>();
    }

    // ============================================================
    // CHAT MODULE CONFIG (with Redis)
    // ============================================================

    public static class TeleportPoint {
        public String world;
        public double x, y, z;
        public float yaw, pitch;

        public TeleportPoint() {
        }

        public TeleportPoint(String world, double x, double y, double z, float yaw, float pitch) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    public static class PoolBox {
        public String world;
        public Vec3i min = new Vec3i(0, 0, 0);
        public Vec3i max = new Vec3i(0, 0, 0);
    }

    public static class Vec3i {
        public int x, y, z;

        public Vec3i() {
        }

        public Vec3i(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    // ============================================================
    // LOAD / SAVE
    // ============================================================

    public static class AfkPool {
        public boolean enabled = true;
        public PoolBox region = new PoolBox();
        public TeleportPoint teleport = new TeleportPoint("world", 0.5, 64.0, 0.5, 0f, 0f);

        /**
         * If true, requires messentials.afk.pool.<name>
         */
        public boolean requirePermission = true;

        /**
         * Allow chat while AFK in this region (won't auto-unAFK on chat)
         */
        public boolean allowChatInside = true;

        /**
         * Allow movement while AFK in this region
         */
        public boolean allowMoveInside = true;

        /**
         * Allow interaction (blocks, entities) while AFK in this region
         */
        public boolean allowInteractInside = true;

        /**
         * Allow walking out to unAFK
         */
        public boolean allowEnterExitFreely = true;

        /**
         * Reward tracks
         */
        public List<AfkReward> rewards = new ArrayList<>();
    }

    public static class AfkReward {
        public String id = "default";
        public int everySeconds = 60;
        public List<String> commands = new ArrayList<>(); // console commands, %player%
        public List<ItemSpec> items = new ArrayList<>();
    }

    public static class ItemSpec {
        /**
         * namespaced material id, e.g. minecraft:emerald or nexo:my_item
         */
        public String type = "minecraft:stone";
        public int amount = 1;
        /**
         * optional NBT json string
         */
        public String nbt = "{}";
    }

    public static class Chat {

        /**
         * Master toggle for the chat system (in addition to Features.enableChatSystem).
         */
        public boolean enabled = true;

        /** Integrations */
//        public boolean useTextPlaceholderApi = true; // Text Placeholder API
//        public boolean useLuckPermsMeta     = true;  // prefixes/suffixes/groups from LP

        /**
         * Color support flags
         */
        public boolean enableHexColors = true;  // &#RRGGBB / <#RRGGBB>
        public boolean enableLegacyColors = true;  // &a codes

        /**
         * Cross-server / Redis-backed chat settings.
         */
        public Redis redis = new Redis();

        /**
         * File locations (relative to the MysticEssentials config directory).
         * <p>
         * Example resolved path:
         * config/mysticessentials/ + files.channels  -> config/mysticessentials/chat/channels.json
         */
        public ChatFiles files = new ChatFiles();
    }

    // ============================================================
    // UTILS
    // ============================================================

    public static class ChatFiles {
        public String history = "chat/history.json";
        public String channels = "chat/channels.json";
        public String triggers = "chat/triggers.json";
        public String tags = "chat/tags.json";
        public String mention = "chat/mention.json";
        public String replacements = "chat/replacements.json";
        public String privatemessages = "chat/privatemessages.json";
        public String motd = "chat/motd.json";
        public String broadcast = "chat/broadcast.json";
        public String shout = "chat/shout.json";
        public String announcements = "chat/announcements.json";
    }

    /**
     * Redis configuration for cross-server chat.
     * This does NOT open any connections by itself; it's just config.
     */
    public static class Redis {
        public boolean enabled = false;

        public String host = "127.0.0.1";
        public int port = 6379;
        public String username = "";
        public String password = "";
        public boolean useSsl = false;

        /**
         * Logical cluster and server identifiers.
         */
        public String clusterId = "mystichorizons";
        public String serverId = "server-1";

        /**
         * Chat pub/sub bases (you already use these for channels).
         */
        public String globalChannelBase = "chat:global";
        public String serverChannelBase = "chat:server";

        /**
         * Private message pub/sub channel.
         */
        public String pmChannel = "chat:pm";

        /**
         * Ignore toggle pub/sub channel.
         */
        public String ignoreChannel = "chat:ignore";
    }

    public static class Vaults {
        public boolean enabled = true;

        // Selector GUI is always 6 rows (54)
        public int selectorRows = 6;

        // Default display item for accessible vaults
        public String defaultDisplayItem = "minecraft:barrel";

        // Default vault name (without suffix)
        public String defaultVaultName = "&bVault";

        // Locked vault appearance
        public String lockedItem = "minecraft:barrier";
        public String lockedName = "&cLocked Vault";

        // If true, selector shows up to 5 vault slots (row 1-5) and row 6 is nav/settings
        public boolean selectorUsesLastRowForNav = true;

        // Permissions behavior
        public boolean allowHexInRenameIfPermitted = true;
        public boolean allowLegacyInRenameIfPermitted = true;

        // Hard cap convenience (if you want it): max vault count to scan perms for
        public int numericCap = 64;
    }
}
