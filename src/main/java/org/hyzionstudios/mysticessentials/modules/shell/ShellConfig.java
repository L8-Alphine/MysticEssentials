package org.hyzionstudios.mysticessentials.modules.shell;

import java.util.ArrayList;
import java.util.List;

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

    public int configVersion = 1;

    public Rail rail = new Rail();
    public Menu menu = new Menu();
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
        /** Pixels from the bottom edge to the foot of the rail. */
        public int offsetY = 196;
        /** Tile edge in pixels. Must match MysticEssentialsShortcutRailSlot.ui. */
        public int tileSize = 46;
        /** Caption above the first tile, as in the reference's "CHAT CH". */
        public String title = "Chat Ch";
        /**
         * Footer hint. Left empty, the module writes what actually activates a
         * slot on this build, which is the honest thing to show: a server-side
         * mod cannot bind ALT+1–9 on the client (see ShortcutRailHud).
         */
        public String hint = "";
        /** Draw a divider under these slot numbers. The bible suggests 4 and 6. */
        public List<Integer> separatorsAfter = new ArrayList<>(List.of(4, 6));
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

    /** One rail position. */
    public static final class Slot {
        /** 1–9, matching the number drawn on the tile. */
        public int index;
        /** A {@link ShortcutKind} name. */
        public String kind = "NONE";
        /** Tile caption in the Shortcuts page and the tooltip. */
        public String label = "";
        /**
         * One or two characters drawn on the tile. Letter marks rather than
         * icons: the mod ships no textures, and a TexturePath that does not
         * resolve in an always-on overlay breaks every custom UI document on
         * that client. Any text works, so a server with its own asset pack can
         * put a glyph from its own font here.
         */
        public String glyph = "";
        /**
         * What the slot points at: a channel id for {@code CHANNEL}, a command
         * line (without the leading slash) for {@code COMMAND}, a URL or
         * command for {@code STORE}. Ignored by the kinds that open a built-in
         * page.
         */
        public String target = "";
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
