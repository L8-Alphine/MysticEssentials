package org.hyzionstudios.mysticessentials.modules.shell;

import java.util.ArrayList;
import java.util.List;

import org.hyzionstudios.mysticessentials.platform.ui.HyzionIcons;
import org.hyzionstudios.mysticessentials.platform.ui.VanillaIcons;

/**
 * Typed model of {@code mods/MysticEssentials/modules/shell/config.json}.
 *
 * <p>The nine slots ship with the assignment the design bible fixes (§8):
 * 1–4 chat channels, 5 store, 6 wiki, 7 warps, 8 server browser, 9 menu. A
 * server may repoint any of them — the rail renders whatever is here — but the
 * defaults are the contract other Mystic modules are written against, so
 * changing them is a deliberate act.</p>
 */
public final class ShellConfig {

    public int configVersion = 2;

    public Rail rail = new Rail();
    public Keys keys = new Keys();
    public Menu menu = new Menu();
    public Browser browser = new Browser();
    public List<Slot> slots = defaultSlots();

    /** The always-on overlay left of chat. */
    public static final class Rail {
        /** Whether the rail is drawn at all. */
        public boolean enabled = true;
        /**
         * Whether a player who has never touched {@code /shortcuts rail} sees
         * it. Each player's own choice is remembered and wins over this.
         */
        public boolean visibleByDefault = true;
        /**
         * Pixels from the left edge of the screen. The rail sits beside the
         * chat panel rather than over it; raise this on a server that moves
         * chat inward.
         */
        public int offsetX = 12;
        /**
         * Pixels from the bottom edge to the foot of the rail. The default
         * centres the rail on the default chat panel; raise or lower it if
         * your players resize chat.
         */
        public int offsetY = 316;
        /**
         * Caption above the first tile. Keep it short: the rail is 34 px wide,
         * and anything longer than about four letters is cut off.
         */
        public String title = "Chat";
        /**
         * Footer hint. Left empty, the module writes what actually activates a
         * slot: {@code Alt+#} while ALT shortcuts are on, {@code /1 – /9} when
         * they are off.
         */
        public String hint = "";
        /** Draw a divider under these slot numbers. The bible suggests 4 and 6. */
        public List<Integer> separatorsAfter = new ArrayList<>(List.of(4, 6));
    }

    /** Keyboard shortcuts. */
    public static final class Keys {
        /**
         * ALT+1 … ALT+9 run the matching rail slot. Read from the walk key
         * (Alt, by default) plus the hotbar slot a digit selects; the slot is
         * put back afterwards so the player keeps their held item. A player
         * who rebound walk away from Alt uses that key instead. Turn this off
         * if your players walk with Alt held and switch items while doing it.
         */
        public boolean altShortcuts = true;
    }

    /** The {@code /menu} shell. */
    public static final class Menu {
        public boolean enabled = true;
        /** Shown in the window's context strip; falls back to the server name. */
        public String serverTitle = "";
        /**
         * Ecosystem modules the shell will not offer even when they are
         * installed, by module id ({@code mysticrpg}, {@code mysticguilds}, …).
         */
        public List<String> hiddenModules = new ArrayList<>();
    }

    /**
     * The Server Browser (rail slot 8, the menu card, {@code /servers}). Lists
     * {@link #servers} grouped and filterable by region, with live player
     * counts for any server on the Redis network roster.
     */
    public static final class Browser {
        public boolean enabled = true;
        /**
         * Also list servers on the network roster that {@link #servers} does
         * not name, under "Network", plus this server when it is not listed.
         * Turn off for a hand-curated list.
         */
        public boolean showNetwork = true;
        /** The servers to offer, in the order they are shown within a group. */
        public List<Server> servers = new ArrayList<>();
    }

    /** One server card in the browser. */
    public static final class Server {
        /**
         * The server's network id ({@code storage.redis.serverId} on that
         * server). With Redis on, it gives the card a live player count and
         * Connect goes through the roster (or the proxy, when one is set).
         */
        public String id = "";
        public String name = "";
        public String description = "";
        /** Cards with the same group sit under one heading ("Survival", "Minigames"). */
        public String group = "";
        /** A short region code for the filter tabs ("NA", "EU"). Empty: shown under every tab. */
        public String region = "";
        /** A {@link VanillaIcons} name ("Portal") or a Common-rooted path; empty for the default. */
        public String icon = "";
        /**
         * Direct address, for a server that is not on the network roster (a
         * partner server, or a network without Redis). Players are referred
         * straight to it; the count is not known.
         */
        public String host = "";
        public int port = 0;
        /** Shown after the player count when set ("12 / 100 online"). */
        public int maxPlayers = 0;
        /** Permission needed to connect; others see the card, locked, with the reason. */
        public String permission = "";
        /** Show the card with "Coming soon" instead of a Connect button. */
        public boolean comingSoon = false;
    }

    /** One rail position. */
    public static final class Slot {
        /** 1–9, matching the number drawn on the tile. */
        public int index;
        /** A {@link ShortcutKind} name. */
        public String kind = "NONE";
        /** Tile caption in the Shortcuts page and the tooltip. */
        public String label = "";
        /**
         * One or two characters drawn on the tile when it has no icon
         * ({@code icon: "none"}, or a path the client cannot find).
         */
        public String glyph = "";
        /**
         * What the slot points at: a channel id for {@code CHANNEL}, a command
         * line (without the leading slash) for {@code COMMAND}, a URL or
         * command for {@code STORE}. Ignored by the kinds that open a built-in
         * page.
         */
        public String target = "";
        /**
         * A vanilla icon: a {@link VanillaIcons} picker name ({@code Portal},
         * {@code Fist Bump}) or an asset path under {@code Common/} (for
         * example {@code Icons/ItemCategories/Portal.png}). Left empty, the
         * slot gets the icon that fits what it does; {@code none} shows the
         * letter mark from {@link #glyph} instead. A server with its own asset
         * pack can point this at its own art by path.
         */
        public String icon = "";
        /** Permission required to use the slot; empty means everyone. */
        public String permission = "";
        /** Set false to leave the position empty. */
        public boolean enabled = true;

        public Slot() {
        }

        Slot(int index, ShortcutKind kind, String label, String glyph, String target) {
            this.index = index;
            this.kind = kind.name();
            this.label = label;
            this.glyph = glyph;
            this.target = target;
        }
    }

    /**
     * The icon a slot draws: its configured one, else the default for what it
     * does, else none. Defaults come from the shipped Hyzion family so the rail
     * reads as one visual system. A configured vanilla, custom or raw asset
     * path still wins over the default.
     */
    static String iconFor(Slot slot) {
        if (slot == null) {
            return "";
        }
        String configured = slot.icon == null ? "" : slot.icon.trim();
        if ("none".equalsIgnoreCase(configured)) {
            return "";
        }
        String resolved = VanillaIcons.resolve(configured);
        if (!resolved.isEmpty()) {
            return resolved;
        }
        String target = slot.target == null ? "" : slot.target.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (ShortcutKind.parse(slot.kind)) {
            case CHANNEL -> switch (target) {
                case "trade", "trading", "market" -> HyzionIcons.ECONOMY;
                case "guild", "clan" -> HyzionIcons.GUILD;
                case "party", "group" -> HyzionIcons.COMMUNITY;
                default -> HyzionIcons.CHANNELS;
            };
            case STORE -> HyzionIcons.SERVICES;
            case WIKI -> HyzionIcons.WIKI;
            case WARPS -> HyzionIcons.PORTAL;
            case SERVERS -> HyzionIcons.SERVERS;
            case MENU -> HyzionIcons.MENU;
            case COMMAND -> HyzionIcons.MENU;
            case NONE -> "";
        };
    }

    /** @return the shipped slot for {@code index}, or an empty one */
    static Slot defaultSlot(int index) {
        for (Slot slot : defaultSlots()) {
            if (slot.index == index) {
                return slot;
            }
        }
        Slot empty = new Slot();
        empty.index = index;
        return empty;
    }

    /**
     * A detached copy, so an editor's draft never aliases the live config that
     * world threads are reading. A missing slot copies as an empty one.
     */
    static Slot copy(Slot source, int index) {
        Slot copy = new Slot();
        copy.index = index;
        if (source != null) {
            copy.kind = source.kind;
            copy.label = source.label;
            copy.glyph = source.glyph;
            copy.target = source.target;
            copy.icon = source.icon;
            copy.permission = source.permission;
            copy.enabled = source.enabled;
        }
        return copy;
    }

    private static List<Slot> defaultSlots() {
        List<Slot> defaults = new ArrayList<>(9);
        defaults.add(new Slot(1, ShortcutKind.CHANNEL, "Server Channel", "S", "global"));
        defaults.add(new Slot(2, ShortcutKind.CHANNEL, "Trading Channel", "T", "trade"));
        defaults.add(new Slot(3, ShortcutKind.CHANNEL, "Guild Channel", "G", "guild"));
        defaults.add(new Slot(4, ShortcutKind.CHANNEL, "Party Channel", "P", "party"));
        defaults.add(new Slot(5, ShortcutKind.STORE, "Server Store", "$", ""));
        defaults.add(new Slot(6, ShortcutKind.WIKI, "Server Wiki", "W", ""));
        defaults.add(new Slot(7, ShortcutKind.WARPS, "Warps", ">", ""));
        defaults.add(new Slot(8, ShortcutKind.SERVERS, "Server Browser", "B", ""));
        defaults.add(new Slot(9, ShortcutKind.MENU, "Menu", "=", ""));
        return defaults;
    }
}
