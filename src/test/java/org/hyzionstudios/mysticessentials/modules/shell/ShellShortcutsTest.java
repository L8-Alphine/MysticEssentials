package org.hyzionstudios.mysticessentials.modules.shell;

/**
 * Dependency-free checks of the shortcut rail's pure rules: which hotbar moves
 * could be the scroll wheel (those need a fresh Alt press before they count as
 * ALT+digit), and that every default slot resolves to an icon while
 * {@code "none"} and a custom path behave as documented.
 */
public final class ShellShortcutsTest {

    public static void main(String[] args) {
        wheelSizedMovesAreNeighbours();
        defaultSlotsAllHaveIcons();
        iconOverridesWork();
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
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
