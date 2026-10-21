package org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.hyzionstudios.mysticessentials.core.message.MysticText;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyDraft;

/**
 * What publishing a key draft would change, as lines of MysticText for the key
 * editor and the publish confirmation. Engine-free; {@code verifyLootBox} checks it.
 */
public final class KeyDiff {

    private KeyDiff() {
    }

    /**
     * @param working   the key draft
     * @param published the published version's spec, or {@code null} when the key was never published
     */
    public static List<String> lines(KeyDraft working, KeyDraft published) {
        List<String> lines = new ArrayList<>();
        if (published == null) {
            lines.add("&7New key &f" + MysticText.stripMarkup(blank(working.name)) + " &7on &f"
                    + blank(working.baseItemId) + "&7, " + uses(working.uses) + ", " + expiry(working.expiresAfterDays)
                    + (working.stackable ? ", stackable" : "") + ".");
            return lines;
        }
        field(lines, "Base item", published.baseItemId, working.baseItemId);
        field(lines, "Name", MysticText.stripMarkup(blank(published.name)), MysticText.stripMarkup(blank(working.name)));
        if (!Objects.equals(blank(published.name), blank(working.name))
                && MysticText.stripMarkup(blank(published.name)).equals(MysticText.stripMarkup(blank(working.name)))) {
            lines.add("&e~ Name colour or style");
        }
        List<String> before = published.lore == null ? List.of() : published.lore;
        List<String> after = working.lore == null ? List.of() : working.lore;
        if (!before.equals(after)) {
            lines.add("&e~ Lore &8· &7" + before.size() + " line(s) &8→ &f" + after.size() + " line(s)");
        }
        field(lines, "Uses", uses(published.uses), uses(working.uses));
        field(lines, "Expiry", expiry(published.expiresAfterDays), expiry(working.expiresAfterDays));
        field(lines, "Items", stacking(published.stackable), stacking(working.stackable));
        if (published.stackable != working.stackable) {
            lines.add("&7Keys already given out keep how they were issued; only new grants change.");
        }
        if (lines.isEmpty()) {
            lines.add("&7No changes from the published version.");
        }
        return lines;
    }

    /** {@code true} when {@link #lines} reports at least one change. */
    public static boolean changed(KeyDraft working, KeyDraft published) {
        return published == null || !lines(working, published).get(0).startsWith("&7No changes");
    }

    private static void field(List<String> lines, String label, String before, String after) {
        String a = blank(before);
        String b = blank(after);
        if (!a.equals(b)) {
            lines.add("&e~ " + label + " &8· &7" + (a.isEmpty() ? "none" : a) + " &8→ &f" + (b.isEmpty() ? "none" : b));
        }
    }

    static String uses(int uses) {
        return uses + (uses == 1 ? " use" : " uses");
    }

    static String stacking(boolean stackable) {
        return stackable ? "stack (one batch per grant)" : "one serial each";
    }

    static String expiry(long days) {
        return days <= 0 ? "never expires" : "expires after " + days + (days == 1 ? " day" : " days");
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }
}
