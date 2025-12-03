package com.alphine.mysticessentials.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ChatConfigManager {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    public static HistoryConfig HISTORY;
    public static ChannelsConfig CHANNELS;
    public static TriggersConfig TRIGGERS;
    public static TagsConfig TAGS;
    public static MentionConfig MENTION;
    public static ReplacementsConfig REPLACEMENTS;
    public static MotdConfig MOTD;
    public static PrivateMessagesConfig PRIVATE;
    public static BroadcastConfig BROADCAST;
    public static ShoutConfig SHOUT;
    public static AnnouncementsConfig ANNOUNCEMENTS;

    private ChatConfigManager() {
    }

    // ------------------------------------------------------------------------
    // Generic loader: "like we do for the main default config"
    // ------------------------------------------------------------------------

    public static void loadAll(Path configDir, MEConfig.ChatFiles files) {
        HISTORY = loadOrCreate(configDir, files.history, HistoryConfig.class, HistoryConfig::new);
        CHANNELS = loadOrCreate(configDir, files.channels, ChannelsConfig.class, ChannelsConfig::new);
        TRIGGERS = loadOrCreate(configDir, files.triggers, TriggersConfig.class, TriggersConfig::new);
        TAGS = loadOrCreate(configDir, files.tags, TagsConfig.class, TagsConfig::new);
        MENTION = loadOrCreate(configDir, files.mention, MentionConfig.class, MentionConfig::new);
        REPLACEMENTS = loadOrCreate(configDir, files.replacements, ReplacementsConfig.class, ReplacementsConfig::new);
        PRIVATE = loadOrCreate(configDir, files.privatemessages, PrivateMessagesConfig.class, PrivateMessagesConfig::new);
        MOTD = loadOrCreate(configDir, files.motd, MotdConfig.class, MotdConfig::new);
        BROADCAST = loadOrCreate(configDir, files.broadcast, BroadcastConfig.class, BroadcastConfig::new);
        SHOUT = loadOrCreate(configDir, files.shout, ShoutConfig.class, ShoutConfig::new);
        ANNOUNCEMENTS = loadOrCreate(configDir, files.announcements, AnnouncementsConfig.class, AnnouncementsConfig::new);
    }

    private static <T> T loadOrCreate(Path configDir,
                                      String relativePath,
                                      Class<T> type,
                                      SupplierWithException<T> defaultSupplier) {
        Path file = configDir.resolve(relativePath);
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file)) {
                    T loaded = GSON.fromJson(r, type);
                    if (loaded != null) return loaded;
                }
            }
            // No file or failed to load -> create defaults and write them
            T def = defaultSupplier.get();
            try (Writer w = Files.newBufferedWriter(file)) {
                GSON.toJson(def, w);
            }
            return def;
        } catch (IOException e) {
            throw new RuntimeException("[MysticEssentials] Failed to load chat config " + file, e);
        }
    }

    // ------------------------------------------------------------------------
    // HISTORY CONFIG
    // ------------------------------------------------------------------------

    private interface SupplierWithException<T> {
        T get();
    }

    // ------------------------------------------------------------------------
    // CHANNELS CONFIG
    // ------------------------------------------------------------------------

    public static class HistoryConfig {
        public boolean enabled = true;
        public int maxMessagesPerPlayer = 100;
        public List<String> trackedMessageTypes = new ArrayList<>();

        public HistoryConfig() {
            trackedMessageTypes.add("minecraft:chat");
            trackedMessageTypes.add("minecraft:say");
            trackedMessageTypes.add("styled_chat:generic_hack");
        }
    }

    // ------------------------------------------------------------------------
    // TRIGGERS CONFIG
    // ------------------------------------------------------------------------

    public static class ChannelsConfig {

        public int version = 2;
        public Settings settings = new Settings();

        /**
         * Dynamic channels: key = channel id ("global", "local", "staff", "admin", or anything else).
         */
        public java.util.Map<String, Channel> channels = new java.util.LinkedHashMap<>();

        public Console console = new Console();
        public SystemMessages systemMessages = new SystemMessages();

        public ChannelsConfig() {
            // Initialize defaults only if nothing is defined (first run)
            if (channels.isEmpty()) {
                initDefaults();
            }
        }

        private void initDefaults() {
            channels.put("global", Channel.createGlobalDefaults("global"));
            channels.put("local", Channel.createLocalDefaults("local"));
            channels.put("staff", Channel.createStaffDefaults("staff"));
            channels.put("admin", Channel.createAdminDefaults("admin"));
            settings.defaultChannel = "global";
        }

        public static class Settings {
            public String timeFormat = "HH:mm";
            public boolean use24hClock = true;
            /**
             * Default channel id, e.g. "global"
             */
            public String defaultChannel = "global";
        }

        public static class Channel {
            /**
             * Id/key of this channel ("global", "staff", etc.).
             */
            public String id;

            public String displayName;
            public String description;

            /**
             * How this channel should behave once Redis is wired in.
             */
            public Scope scope = Scope.LOCAL;

            /**
             * Optional explicit permission base. If null/blank, we fall back to
             * PermNodes.chatChannelNode(id), i.e. "messentials.chat.channel.<id>".
             * <p>
             * We'll then use:
             * <base>.send
             * <base>.read
             * <base>.*
             */
            public String permissionBase;

            /**
             * Optional command aliases, e.g. ["g","global"] or ["staff","sc"].
             */
            public java.util.List<String> aliases = new java.util.ArrayList<>();

            /**
             * Chat range in blocks (0 or <0 = unlimited).
             */
            public int range;

            /**
             * MiniMessage player format.
             */
            public String format;

            public java.util.List<String> hoverNameLines = new java.util.ArrayList<>();

            /**
             * Console log format.
             */
            public String consoleFormat;

            /**
             * Optional explicit Redis topic name override for this channel.
             */
            public String redisTopic = null;

            // ---- Default builders ----

            public static Channel createGlobalDefaults(String id) {
                Channel c = new Channel();
                c.id = id;
                c.displayName = "Global";
                c.description = "Default global chat channel.";
                c.permissionBase = null; // will fall back to "messentials.chat.channel.global"
                c.aliases = java.util.Arrays.asList("g", "global");
                c.range = 0;
                c.format =
                        "<gray>[</gray><gradient:#00d4ff:#7c3aed>G</gradient><gray>]</gray> " +
                                "<prefix><display-name><suffix> <gray>»</gray> <message>";
                c.hoverNameLines = java.util.Arrays.asList(
                        "<yellow>Name:</yellow> <white><display-name></white>",
                        "<gray>World:</gray> <world>",
                        "<gray>Server:</gray> <server>"
                );
                c.consoleFormat = "[G] <name> » <message>";
                return c;
            }

            static Channel createLocalDefaults(String id) {
                Channel c = new Channel();
                c.id = id;
                c.displayName = "Local";
                c.description = "Local chat with a radius around the sender.";
                c.permissionBase = null; // "messentials.chat.channel.local"
                c.aliases = java.util.Arrays.asList("l", "local");
                c.range = 100;
                c.format =
                        "<gray>[</gray><gradient:#22c55e:#16a34a>L</gradient><gray>]</gray> " +
                                "<prefix><display-name><suffix> <gray>»</gray> <message>";
                c.hoverNameLines = java.util.Arrays.asList(
                        "<yellow>Local Chat</yellow>",
                        "<yellow>Name:</yellow> <white><display-name></white>",
                        "<gray>World:</gray> <world>"
                );
                c.consoleFormat = "[L] <name>@<world> » <message>";
                return c;
            }

            static Channel createStaffDefaults(String id) {
                Channel c = new Channel();
                c.id = id;
                c.displayName = "Staff";
                c.description = "Staff-only communication channel.";
                c.permissionBase = "messentials.chat.channel.staff"; // explicit if you want
                c.aliases = java.util.Arrays.asList("staff", "sc");
                c.range = 0;
                c.format =
                        "<dark_gray>[</dark_gray><gradient:#facc15:#f97316>Staff</gradient><dark_gray>]</dark_gray> " +
                                "<gold><display-name></gold> <gray>»</gray> <yellow><message></yellow>";
                c.hoverNameLines = java.util.Arrays.asList(
                        "<gold>Staff Channel</gold>",
                        "<yellow>Name:</yellow> <white><display-name></white>",
                        "<yellow>Rank:</yellow> <prefix>",
                        "<gray>World:</gray> <world>"
                );
                c.consoleFormat = "[Staff] <name> » <message>";
                return c;
            }

            static Channel createAdminDefaults(String id) {
                Channel c = new Channel();
                c.id = id;
                c.displayName = "Admin";
                c.description = "Admin-only communication channel.";
                c.permissionBase = "messentials.chat.channel.admin";
                c.aliases = java.util.Arrays.asList("admin", "ac");
                c.range = 0;
                c.format =
                        "<dark_red>[</dark_red><gradient:#f97316:#ef4444>Admin</gradient><dark_red>]</dark_red> " +
                                "<red><display-name></red> <gray>»</gray> <red><message></red>";
                c.hoverNameLines = java.util.Arrays.asList(
                        "<red>Admin Channel</red>",
                        "<yellow>Name:</yellow> <white><display-name></white>",
                        "<yellow>Rank:</yellow> <prefix>",
                        "<gray>World:</gray> <world>"
                );
                c.consoleFormat = "[Admin] <name> » <message>";
                return c;
            }

            public enum Scope {
                /**
                 * Messages stay on this JVM only (no Redis).
                 */
                LOCAL,

                /**
                 * Messages fan out only to servers with the same MEConfig.chat.redis.serverId.
                 * (e.g. "avalon-1" -> all nodes in that logical server group)
                 */
                SERVER,

                /**
                 * Messages fan out to all servers in the Redis cluster.
                 */
                GLOBAL
            }
        }

        // Console + SystemMessages
        public static class Console {
            public boolean usePrefix = true;
            public String prefix = "[Console]";
            public String name = "Server";
            public String format =
                    "<gray>[</gray><gradient:#22c1c3:#fdbb2d>G</gradient><gray>]</gray> " +
                            "<dark_gray>[CONSOLE]</dark_gray> <gray>»</gray> <message>";
        }

        public static class SystemMessages {
            public String join =
                    "<gray>[</gray><green>+</green><gray>]</gray> <green><display-name> joined the game.</green>";
            public String quit =
                    "<gray>[</gray><red>-</red><gray>]</gray> <red><display-name> left the game.</red>";
            public String death =
                    "<gray>[☠]</gray> <red><message></red>";
        }
    }

    // ------------------------------------------------------------------------
    // TAGS CONFIG (item / inventory / ender chest)
    // ------------------------------------------------------------------------

    public static class TriggersConfig {
        public boolean enabled = true;
        public List<TriggerRule> rules = new ArrayList<>();

        public TriggersConfig() {
            TriggerRule discord = new TriggerRule();
            discord.id = "discord";
            discord.pattern = "(?i)^!discord$";
            discord.stopOnMatch = true;
            discord.runAsConsole = true;
            discord.commands.add("tellraw {player} {\"text\":\"Join our Discord: discord.gg/example\",\"color\":\"aqua\"}");
            rules.add(discord);

            TriggerRule help = new TriggerRule();
            help.id = "help-alias";
            help.pattern = "(?i)^!help$";
            help.stopOnMatch = true;
            help.runAsConsole = false;
            help.commands.add("help");
            rules.add(help);
        }

        public static class TriggerRule {
            public String id = "example";
            public String pattern = "(?i)^!example$";
            public boolean stopOnMatch = true;
            public boolean runAsConsole = true;
            public List<String> commands = new ArrayList<>();
        }
    }

    // ------------------------------------------------------------------------
    // MENTION CONFIG
    // ------------------------------------------------------------------------

    public static class TagsConfig {
        public boolean enabled = true;
        public Tag item = Tag.createDefaultItem();
        public Tag inventory = Tag.createDefaultInventory();
        public Tag enderchest = Tag.createDefaultEnderChest();

        public static class Tag {
            public boolean enabled = true;
            public List<String> aliases = new ArrayList<>();
            public String displayText;
            public String hoverHeader;
            public String hoverEmpty;
            public String hoverNote; // optional

            /**
             * Optional template for a click command, e.g. "meview item {player}".
             * "{player}" will be replaced with the sender's *name* when building the tag.
             * If null/blank => no click action is added.
             */
            public String clickCommandTemplate;

            static Tag createDefaultItem() {
                Tag t = new Tag();
                t.aliases = Arrays.asList("<item>", "[item]", "{item}");
                t.displayText = "<aqua>[ITEM]</aqua>";
                t.hoverHeader = "<yellow>Held Item:</yellow>";
                t.hoverEmpty = "<gray>(no item in hand)</gray>";
                t.hoverNote = "<gray>Click to preview the sender's currently held item.</gray>";

                // Default: hook into /meview item <player>
                t.clickCommandTemplate = "meview item {player}";
                return t;
            }

            static Tag createDefaultInventory() {
                Tag t = new Tag();
                t.aliases = Arrays.asList("<inv>", "[inv]", "{inv}");
                t.displayText = "<light_purple>[INVENTORY]</light_purple>";
                t.hoverHeader = "<yellow>Inventory Preview</yellow>";
                t.hoverEmpty = null;
                t.hoverNote = "<gray>Click to view the sender's current inventory.</gray>";

                // Default: /meview inv <player>
                t.clickCommandTemplate = "meview inv {player}";
                return t;
            }

            static Tag createDefaultEnderChest() {
                Tag t = new Tag();
                t.aliases = Arrays.asList("<ec>", "[ec]", "{ec}");
                t.displayText = "<dark_purple>[ENDER CHEST]</dark_purple>";
                t.hoverHeader = "<yellow>Ender Chest Preview</yellow>";
                t.hoverEmpty = null;
                t.hoverNote = "<gray>Click to view the sender's ender chest.</gray>";

                // Default: /meview ec <player>
                t.clickCommandTemplate = "meview ec {player}";
                return t;
            }
        }
    }

    // ------------------------------------------------------------------------
    // REPLACEMENTS CONFIG (regex replacements)
    // ------------------------------------------------------------------------

    public static class MentionConfig {
        public boolean enabled = true;
        public Trigger trigger = new Trigger();
        public Formatting formatting = new Formatting();
        public Sound sound = new Sound();

        public static class Trigger {
            public String mode = "AT_PREFIX"; // AT_PREFIX / NAME_ONLY / REGEX
            public String pattern = "@{name}";
            public boolean caseInsensitive = true;
        }

        public static class Formatting {
            public String highlightFormat = "<gradient:#f97316:#facc15>@<target></gradient>";
            public String selfHighlightFormat = "<gradient:#22c55e:#a3e635>@<target></gradient>";
        }

        public static class Sound {
            public boolean enabled = true;
            public String soundId = "minecraft:entity.experience_orb.pickup";
            public float volume = 1.0f;
            public float pitch = 1.0f;
            public Repeat repeat = new Repeat();
        }

        public static class Repeat {
            public boolean enabled = false;
            public int times = 3;
            public int intervalTicks = 10;
        }
    }

    public static class ReplacementsConfig {
        public boolean enabled = true;
        public List<Rule> rules = new ArrayList<>();

        public ReplacementsConfig() {
            Rule censor = new Rule();
            censor.id = "censor-badword";
            censor.pattern = "(?i)badword";
            censor.replacement = "***";
            censor.literal = false;
            censor.ignoreCase = true;
            censor.replaceAll = true;
            rules.add(censor);

            Rule shrug = new Rule();
            shrug.id = "shrug-emoji";
            shrug.pattern = ":shrug:";
            shrug.replacement = "¯\\\\_(ツ)_/¯";
            shrug.literal = true;
            shrug.ignoreCase = false;
            shrug.replaceAll = true;
            rules.add(shrug);

            Rule heart = new Rule();
            heart.id = "heart";
            heart.pattern = "<3";
            heart.replacement = "<red>❤</red>";
            heart.literal = true;
            heart.ignoreCase = false;
            heart.replaceAll = true;
            rules.add(heart);
        }

        public static class Rule {
            public String id = "example";
            public String pattern = "(?i)example";
            public String replacement = "EXAMPLE";
            public boolean literal = false;
            public boolean ignoreCase = true;
            public boolean replaceAll = true;
        }
    }

    public static class PrivateMessagesConfig {
        public boolean enabled = true;
        public boolean logToConsole = true;

        public Format format = new Format();
        public Sound sound = new Sound();

        public static class Format {
            public String toSender =
                    "<gray>[<gold>To <yellow><target></yellow></gold>] <white><message></white>";
            public String toTarget =
                    "<gray>[<gold>From <yellow><sender></yellow></gold>] <white><message></white>";
            public String spy =
                    "<gray>[<red>Spy</red>] <yellow><sender></yellow> <gray>→</gray> <yellow><target></yellow>: <white><message></white>";
        }

        public static class Sound {
            public boolean enabled = true;
            public String soundId = "minecraft:block.note_block.pling";
            public float volume = 1.0f;
            public float pitch = 1.0f;
        }
    }

    // ------------------------------------------------------------------------
    // MOTD Config
    // ------------------------------------------------------------------------

    public static class MotdConfig {
        /**
         * Master toggle for join MOTD.
         */
        public boolean enabled = true;

        /**
         * Lines to send to the player on join.
         * Parsed as MiniMessage, with placeholders:
         * <player>, <name>         -> raw username
         * <display-name>           -> display name (with nickname)
         * <online>                 -> online player count
         * <max>                    -> max player count
         * <world>                  -> dimension id (e.g. minecraft:overworld)
         * <server>                 -> server name/mod name
         */
        public java.util.List<String> lines = new java.util.ArrayList<>();

        public MotdConfig() {
            lines.add("<gray>Welcome, <green><display-name></green>!</gray>");
            lines.add("<gray>There are <aqua><online></aqua>/<aqua><max></aqua> players online.</gray>");
            lines.add("<gray>You are in <yellow><world></yellow> on <gold><server></gold>.</gray>");
        }
    }

    // ------------------------------------------------------------------------
    // BROADCAST CONFIG
    // ------------------------------------------------------------------------

    public static class BroadcastConfig {
        public boolean enabled = true;
        public boolean logToConsole = true;

        /**
         * MiniMessage format.
         * Placeholders:
         *  - <message> : the raw message text
         */
        public String format =
                "<gray>[<gold>Broadcast</gold>]</gray> <yellow><message></yellow>";

        /**
         * Console log format (plain text).
         * Placeholders:
         *  - <message> : the raw message text
         */
        public String consoleFormat = "[Broadcast] <message>";
    }

    // ------------------------------------------------------------------------
    // SHOUT CONFIG
    // ------------------------------------------------------------------------

    public static class ShoutConfig {
        public boolean enabled = true;

        /**
         * Radius in blocks to hear shouts.
         * If <= 0, defaults to 100.
         */
        public int radius = 100;

        public boolean logToConsole = true;

        /**
         * MiniMessage format.
         * Placeholders:
         *  - <sender>  : the sender's name
         *  - <message> : the raw message text
         *  - <radius>  : the effective radius
         */
        public String format =
                "<gray>[<yellow>Shout</yellow>]</gray> <white><sender></white> <gray>»</gray> <yellow><message></yellow>";

        /**
         * Console log format (plain text).
         * Placeholders:
         *  - <sender>
         *  - <message>
         *  - <radius>
         */
        public String consoleFormat =
                "[Shout r=<radius>] <sender>: <message>";

        /**
         * Message shown to the shouter if nobody is in range.
         * MiniMessage.
         * Placeholders:
         *  - <radius> : effective radius
         */
        public String nobodyHeard =
                "<gray>No one is close enough to hear your shout (radius <radius> blocks).</gray>";
    }

    // ------------------------------------------------------------------------
    // AUTO ANNOUNCEMENTS CONFIG
    // ------------------------------------------------------------------------

    /**
     * Periodic auto-broadcast announcements.
     *
     * Designed to be used by a scheduler in your plugin:
     *  - Check ANNOUNCEMENTS.enabled
     *  - Every X seconds, pick the next message based on randomOrder / priority.
     *
     * All formatting is MiniMessage, so full color/gradient support works.
     */
    public static class AnnouncementsConfig {

        /**
         * Master toggle for auto announcements.
         */
        public boolean enabled = true;

        /**
         * If true, each broadcast chooses a random group.
         * If false, groups are walked in the given "priority" order.
         */
        public boolean randomOrder = false;

        /**
         * Default interval between announcements in seconds.
         * Individual groups can override this with their own intervalSeconds.
         */
        public int intervalSeconds = 300;

        /**
         * Priority order for groups when randomOrder == false.
         * Each entry must be a group id present in "groups".
         */
        public java.util.List<String> priority = new java.util.ArrayList<>();

        /**
         * Groups of announcements, keyed by id.
         */
        public java.util.Map<String, Group> groups = new java.util.LinkedHashMap<>();

        public AnnouncementsConfig() {
            // Provide a simple default group so the file isn't empty on first run.
            Group g = new Group();
            g.id = "global";
            g.displayName = "Global Announcements";
            g.randomOrder = true;
            g.center = false; // default: no centering
            g.intervalSeconds = 0; // use global interval
            g.format =
                    "<gray>[<gold>Announcement</gold>]</gray> <yellow><message></yellow>";

            g.messages.add("<gray>Welcome to <gold>MysticHorizonsMC</gold>!</gray>");
            g.messages.add("<gray>Join our Discord: <aqua>discord.gg/yourcode</aqua></gray>");
            g.messages.add("<gray>Use <green>/sethome</green> and <green>/home</green> to save your favorite spots.</gray>");

            groups.put(g.id, g);
            priority.add(g.id);
        }

        public static class Group {

            /**
             * Unique id for this group ("global", "survival-tips", etc.)
             */
            public String id = "default";

            /**
             * Friendly display name (optional, mostly for future GUIs or debug).
             */
            public String displayName = "Default Group";

            /**
             * Toggle this group on/off.
             */
            public boolean enabled = true;

            /**
             * If true, messages from this group are picked randomly.
             * If false, messages are cycled in their list order.
             */
            public boolean randomOrder = true;

            /**
             * Per-group interval override (seconds).
             * If <= 0, the global intervalSeconds is used.
             */
            public int intervalSeconds = 0;

            /**
             * If true, each logical line of the message will be auto-centered
             * (using a simple padding-based centering in chat).
             */
            public boolean center = false;

            /**
             * MiniMessage format for this group's announcements.
             * <message> is replaced with the actual message text.
             */
            public String format =
                    "<gray>[<gold>Announcement</gold>]</gray> <yellow><message></yellow>";

            /**
             * MiniMessage messages themselves.
             *
             * Line breaks:
             *  - You can use actual newlines inside the string (e.g. "Line1\nLine2"),
             *    or the token <br> which will be converted to a newline.
             *
             * Centering:
             *  - If center == true, each line is centered separately.
             */
            public java.util.List<String> messages = new java.util.ArrayList<>();
        }
    }

}
