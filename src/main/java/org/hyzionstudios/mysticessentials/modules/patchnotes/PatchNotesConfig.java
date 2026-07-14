package org.hyzionstudios.mysticessentials.modules.patchnotes;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed model of {@code modules/patchnotes/config.json}. Controls the open
 * command + aliases, join behaviour, default filter/sort, and the category set
 * the viewer's filter buttons are built from. Field names map directly to JSON
 * keys via Gson; defaults here define the file written on first run.
 *
 * <p>The visual theme (dark navy list + content panes, colour-coded lines) is
 * baked into the {@code .ui} files rather than exposed here: Hytale 0.5.6 Custom
 * UI Labels take a static style only, so colours cannot be re-themed at runtime.</p>
 */
public final class PatchNotesConfig {

    public int configVersion = 1;

    /** Primary command label; {@code /patchnotes} by default. */
    public String openCommand = "patchnotes";
    public List<String> aliases = new ArrayList<>(List.of("patches", "updates", "changelog"));

    /** Notify players in chat about unread login patches when they join. */
    public boolean showOnJoin = true;
    /** When notifying on join, only count patches the player has not read yet. */
    public boolean showOnlyUnreadOnJoin = true;

    /**
     * Automatically open the Patch Notes UI on join when there are qualifying
     * login patches (see {@link #showOnlyUnreadOnJoin}). When this opens the UI,
     * the chat notification is suppressed for that join so players are not told
     * about notes the viewer is already showing them.
     */
    public boolean openOnJoin = false;
    /**
     * Ticks to wait after a player connects before auto-opening the UI. The
     * player entity is not ready to receive a Custom UI page the instant
     * {@code PlayerConnectEvent} fires, so a short delay is required (1 tick =
     * 50&nbsp;ms). Values &le; 0 are treated as "next tick".
     */
    public int openOnJoinDelayTicks = 40;

    /** Mark a patch read as soon as the player opens it in the viewer. */
    public boolean markReadOnView = true;

    /** Default category filter id, or {@code "all"}. */
    public String defaultFilter = "all";
    /** {@code newest} or {@code oldest} (pinned notes always sort first regardless). */
    public String defaultSort = "newest";
    /** Hard cap on how many notes appear in the list (0 = unlimited). */
    public int maxPatchNotesShown = 50;

    /** The filterable categories, in display order. */
    public List<Category> categories = defaultCategories();

    /** Generate the bundled example patches on first startup. */
    public boolean generateExamples = true;

    private static List<Category> defaultCategories() {
        List<Category> list = new ArrayList<>();
        list.add(new Category("additions", "Additions"));
        list.add(new Category("fixes", "Fixes"));
        list.add(new Category("changes", "Changes"));
        list.add(new Category("removals", "Removals"));
        return list;
    }

    /** A filter category: a stable {@code id} matched against {@link PatchNote.Section#type} and a display label. */
    public static final class Category {
        public String id;
        public String displayName;

        public Category() {
        }

        public Category(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }
    }

    public boolean sortNewestFirst() {
        return defaultSort == null || !defaultSort.equalsIgnoreCase("oldest");
    }
}
