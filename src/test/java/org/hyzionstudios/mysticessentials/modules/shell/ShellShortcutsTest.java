package org.hyzionstudios.mysticessentials.modules.shell;

/**
 * Dependency-free checks of the shell's pure rules: which hotbar moves could be
 * the scroll wheel (those need a fresh Alt press before they count as
 * ALT+digit); that every default slot resolves to an icon while {@code "none"},
 * a picker name and a custom path behave as documented; the v1 to v2 rail
 * migration; the Server Browser's region filter; and the Rail Editor's input
 * limits.
 */
public final class ShellShortcutsTest {

    public static void main(String[] args) {
        wheelSizedMovesAreNeighbours();
        defaultSlotsAllHaveIcons();
        iconOverridesWork();
        iconNamesResolve();
        railMigrationMovesOnlyDefaults();
        regionTabsFilter();
        editorInputIsClipped();
        editorCopiesAreDetached();
        System.out.println("ShellShortcutsTest: all checks passed.");
    }

    /** One notch of the wheel moves one slot, wrapping 9 <-> 1; a digit can jump anywhere. */
    private static void wheelSizedMovesAreNeighbours() {
        require(AltShortcuts.adjacent(3, 4, 9), "3 -> 4 is one notch");
        require(AltShortcuts.adjacent(4, 3, 9), "4 -> 3 is one notch");
        require(AltShortcuts.adjacent(0, 8, 9), "1 -> 9 wraps");
        require(AltShortcuts.adjacent(8, 0, 9), "9 -> 1 wraps");
        require(!AltShortcuts.adjacent(0, 4, 9), "1 -> 5 is a jump only a digit makes");
        require(!AltShortcuts.adjacent(2, 6, 9), "3 -> 7 is a jump");
        require(AltShortcuts.adjacent(0, 1, 9), "1 -> 2 is one notch");
        require(AltShortcuts.adjacent(0, 1, 2), "a two-slot bar still has neighbours");
        require(!AltShortcuts.adjacent(0, 0, 1), "staying put on a one-slot bar is not a move");
    }

    private static void defaultSlotsAllHaveIcons() {
        ShellConfig config = new ShellConfig();
        require(config.slots.size() == 9, "nine default slots, found " + config.slots.size());
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ShellConfig.Slot slot : config.slots) {
            String icon = ShellConfig.iconFor(slot);
            require(!icon.isEmpty(), "default slot " + slot.index + " (" + slot.kind + ") has no icon");
            require(icon.endsWith(".png") && !icon.startsWith("/") && !icon.contains(" "),
                    "slot " + slot.index + " icon is not a clean Common-rooted path: " + icon);
            require(seen.add(icon), "slot " + slot.index + " repeats icon " + icon);
        }
    }

    private static void iconOverridesWork() {
        ShellConfig.Slot slot = new ShellConfig().slots.get(0);
        slot.icon = "none";
        require(ShellConfig.iconFor(slot).isEmpty(), "\"none\" must fall back to the letter mark");
        slot.icon = "  Icons/ItemCategories/Wood.png ";
        require(ShellConfig.iconFor(slot).equals("Icons/ItemCategories/Wood.png"), "a custom path is used, trimmed");
        require(ShellConfig.iconFor(null).isEmpty(), "no slot, no icon");
        slot.icon = "fist bump";
        require(ShellConfig.iconFor(slot).equals(
                org.hyzionstudios.mysticessentials.platform.ui.VanillaIcons.EMOTE_FIST_BUMP),
                "a picker name resolves, ignoring case and spaces");
        slot.icon = "NoSuchIcon";
        require(ShellConfig.iconFor(slot).equals(ShellConfig.iconFor(ShellConfig.defaultSlot(1))),
                "an unknown name falls back to the kind's default rather than drawing nothing");
    }

    private static void iconNamesResolve() {
        java.util.Set<String> names = new java.util.HashSet<>();
        java.util.Set<String> paths = new java.util.HashSet<>();
        // The whole picker: Hyzion's family and the game's must not shadow each other.
        for (org.hyzionstudios.mysticessentials.platform.ui.VanillaIcons.Entry entry
                : org.hyzionstudios.mysticessentials.platform.ui.VanillaIcons.pickerWithCustom()) {
            require(names.add(entry.name().replace(" ", "").toLowerCase(java.util.Locale.ROOT)),
                    "picker name repeats: " + entry.name());
            require(paths.add(entry.path()), "picker path repeats: " + entry.path());
            require(org.hyzionstudios.mysticessentials.platform.ui.VanillaIcons.resolve(entry.name())
                    .equals(entry.path()), entry.name() + " does not resolve to its own path");
            require(org.hyzionstudios.mysticessentials.platform.ui.VanillaIcons.nameOf(entry.path())
                    .equals(entry.name()), entry.path() + " does not name itself");
        }
        require(org.hyzionstudios.mysticessentials.platform.ui.VanillaIcons.resolve("").isEmpty(), "blank is empty");
        require(org.hyzionstudios.mysticessentials.platform.ui.VanillaIcons.resolve("My/Own.png")
                .equals("My/Own.png"), "a path passes through for custom asset packs");
    }

    private static void railMigrationMovesOnlyDefaults() {
        com.google.gson.JsonObject old = rail(196, "Chat Ch");
        old.getAsJsonObject("rail").addProperty("tileSize", 46);
        ShellModule.migrateRailV1(old);
        com.google.gson.JsonObject migrated = old.getAsJsonObject("rail");
        require(migrated.get("offsetY").getAsInt() == 316, "the old default position moves up");
        require("Chat".equals(migrated.get("title").getAsString()), "the old default title is shortened");
        require(!migrated.has("tileSize"), "the dead tileSize key is dropped");

        com.google.gson.JsonObject custom = rail(240, "Talk");
        ShellModule.migrateRailV1(custom);
        require(custom.getAsJsonObject("rail").get("offsetY").getAsInt() == 240, "a chosen position is kept");
        require("Talk".equals(custom.getAsJsonObject("rail").get("title").getAsString()), "a chosen title is kept");
        require(new ShellConfig().rail.offsetY == 316,
                "new configs start where the migration lands");

        com.google.gson.JsonObject empty = new com.google.gson.JsonObject();
        ShellModule.migrateRailV1(empty);
        require(empty.size() == 0, "a config without a rail section is left alone");
    }

    private static com.google.gson.JsonObject rail(int offsetY, String title) {
        com.google.gson.JsonObject rail = new com.google.gson.JsonObject();
        rail.addProperty("offsetY", offsetY);
        rail.addProperty("title", title);
        com.google.gson.JsonObject tree = new com.google.gson.JsonObject();
        tree.add("rail", rail);
        return tree;
    }

    private static void regionTabsFilter() {
        java.util.List<ServerDirectory.Entry> entries = java.util.List.of(
                entry("c0", "EU"), entry("c1", "NA"), entry("c2", ""), entry("c3", "EU"));
        require(ServerDirectory.regions(entries).equals(java.util.List.of("EU", "NA")),
                "regions in first-seen order, blanks skipped");
        require(ServerDirectory.inRegion(entries.get(0), "eu"), "region match ignores case");
        require(!ServerDirectory.inRegion(entries.get(1), "EU"), "NA is not under EU");
        require(ServerDirectory.inRegion(entries.get(2), "EU"), "a region-less server shows under every tab");
        require(ServerDirectory.inRegion(entries.get(1), ""), "All shows everything");
        require(!entries.get(0).connectable() && entry("x", "", ServerDirectory.Status.DIRECT).connectable(),
                "only ONLINE and DIRECT cards connect");
    }

    private static ServerDirectory.Entry entry(String key, String region) {
        return entry(key, region, ServerDirectory.Status.OFFLINE);
    }

    private static ServerDirectory.Entry entry(String key, String region, ServerDirectory.Status status) {
        return new ServerDirectory.Entry(key, key, "", "", region, "", status, 0, 0, key, "", 0);
    }

    private static void editorInputIsClipped() {
        require(RailEditorPage.clip("  Trade  ", 32).equals("Trade"), "labels are trimmed");
        require(RailEditorPage.clip("x".repeat(40), 32).length() == 32, "labels are capped");
        require(RailEditorPage.clipCodePoints("ABC", 2).equals("AB"), "glyphs are two characters");
        String emoji = new String(Character.toChars(0x1F5E1));
        require(RailEditorPage.clipCodePoints(emoji + emoji + "x", 2).equals(emoji + emoji),
                "glyph clipping never splits a surrogate pair");
        require(RailEditorPage.clampSlot(0) == 1 && RailEditorPage.clampSlot(12) == 9, "slots are 1 to 9");
    }

    private static void editorCopiesAreDetached() {
        ShellConfig.Slot live = ShellConfig.defaultSlot(5);
        ShellConfig.Slot draft = ShellConfig.copy(live, 5);
        draft.label = "Changed";
        draft.icon = "none";
        require(!"Changed".equals(live.label) && !"none".equals(live.icon), "editing a draft never touches the live slot");
        ShellConfig.Slot missing = ShellConfig.copy(null, 7);
        require(missing.index == 7 && ShortcutKind.parse(missing.kind) == ShortcutKind.NONE,
                "a missing slot copies as an empty one at its index");
        require(ShortcutKind.parse(ShellConfig.defaultSlot(8).kind) == ShortcutKind.SERVERS,
                "slot 8 ships as the server browser");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
