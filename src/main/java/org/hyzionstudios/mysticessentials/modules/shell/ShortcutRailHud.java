package org.hyzionstudios.mysticessentials.modules.shell;

import static org.hyzionstudios.mysticessentials.platform.ui.MysticPage.uiText;

import java.util.List;

import org.hyzionstudios.mysticessentials.modules.customcontent.layout.LayoutRenderer;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * The shortcut rail: a vertical strip of numbered tiles drawn beside the chat
 * panel (Master UI/UX Design Bible v1.0 §8).
 *
 * <h2>Why this is a display, not a control</h2>
 *
 * <p>The rail renders as a {@code CustomUIHud}, and a HUD is read-only: the
 * {@code CustomHud} packet carries {@code commands} but no
 * {@code eventBindings}, so no press, hover or key on an overlay ever reaches
 * the server. Pressing a tile is therefore impossible by construction; the
 * pointer-driven twin is {@link ShellPages.ShortcutBarPage}, which is a real
 * page and does bind events.</p>
 *
 * <p><b>ALT+1–9 cannot be bound from a server-side mod on this engine.</b> The
 * server API exposes no keybind registry; the client's binding table is not
 * asset-driven, so a mod asset pack cannot add one; and the one key event the
 * custom UI protocol has — {@code CustomUIEventBindingType.KeyDown} — is a
 * page-level binding that only fires while a page is open and an element has
 * focus, which is exactly when the player is not in the world. The rail
 * therefore shows the number as a label and the module registers {@code /1}
 * … {@code /9} so the slots are reachable from the keyboard in one keystroke
 * plus Enter. If a future build ships client keybinds, only the input source
 * changes: {@link ShellModule#activateSlot} is already the single entry point.
 * The footer hint is written from {@link ShellModule#railHint()} rather than
 * hardcoded, so it never promises a shortcut that does not work.</p>
 */
final class ShortcutRailHud extends CustomUIHud {

    static final String KEY = "mysticessentials:shortcutrail";

    private static final String RAIL_UI = "Hud/MysticEssentialsShortcutRail.ui";
    private static final String SLOT_UI = "Hud/MysticEssentialsShortcutRailSlot.ui";
    private static final String SLOTS = "#MysticShortcutRailSlots";
    private static final int Z_ORDER = 0;

    /** Must match the tile heights in MysticEssentialsShortcutRailSlot.ui. */
    private static final int TILE_GAP = 5;
    private static final int TITLE_HEIGHT = 19;
    private static final int HINT_HEIGHT = 17;

    /**
     * Hyzion tokens as MysticText colour markup, mirrored from MysticTheme.ui
     * (see the rail's .ui header). {@code <#hex>} colours the rest of the
     * span, which is all a one-glyph label needs.
     */
    private static final String GOLD = "<#D9B572>";
    private static final String TEXT_SECONDARY = "<#AEC1D5>";
    private static final String TEXT_DIM = "<#93A8C0>";
    private static final String TEXT_FAINT = "<#6E829B>";

    private final ShellModule shell;
    private final List<ShortcutSlot> slots;

    ShortcutRailHud(PlayerRef player, ShellModule shell, List<ShortcutSlot> slots) {
        super(player, KEY, Z_ORDER);
        this.shell = shell;
        this.slots = slots;
    }

    @Override
    protected void build(UICommandBuilder cmd) {
        ShellConfig.Rail rail = shell.config().rail;
        cmd.append(RAIL_UI);

        // Position and size the root from config: the rail has to clear the
        // chat panel on whatever resolution and UI scale the player runs, and
        // only the server knows how many slots there are to fit.
        cmd.setObject("#MysticShortcutRailRoot.Anchor", LayoutRenderer.anchor(
                rail.offsetX, null, null, rail.offsetY, tileSize(rail), railHeight(rail)));

        cmd.set("#MysticShortcutRailTitle.TextSpans",
                uiText("#MysticShortcutRailTitle.TextSpans", rail.title));
        cmd.set("#MysticShortcutRailHint.TextSpans",
                uiText("#MysticShortcutRailHint.TextSpans", shell.railHint()));

        for (int i = 0; i < slots.size(); i++) {
            ShortcutSlot slot = slots.get(i);
            String tile = SLOTS + "[" + i + "]";
            cmd.append(SLOTS, SLOT_UI);

            // An unavailable slot dims but keeps its number, so the shortcut
            // stays discoverable while it is out of reach.
            String glyphColor = slot.active() ? GOLD : slot.available() ? TEXT_SECONDARY : TEXT_FAINT;
            cmd.set(tile + " #MysticSlotGlyph.TextSpans",
                    uiText(tile + " #MysticSlotGlyph.TextSpans", glyphColor + slot.glyph()));
            cmd.set(tile + " #MysticSlotNumber.TextSpans",
                    uiText(tile + " #MysticSlotNumber.TextSpans",
                            (slot.available() ? TEXT_DIM : TEXT_FAINT) + slot.index()));

            cmd.set(tile + " #MysticSlotActive.Visible", slot.active());
            cmd.set(tile + " #MysticSlotBar.Visible", slot.active());
            cmd.set(tile + " #MysticSlotSeparator.Visible", separatorAfter(rail, slot.index()));

            // A HUD routes no mouse events, so the tooltip is the only thing
            // that can explain an unavailable slot without opening a page.
            cmd.set(tile + " #MysticSlotHit.TooltipText", tooltip(slot));
        }
    }

    private String tooltip(ShortcutSlot slot) {
        return slot.index() + " — " + slot.label() + "\n" + slot.stateLine();
    }

    private static boolean separatorAfter(ShellConfig.Rail rail, int index) {
        return rail.separatorsAfter != null && rail.separatorsAfter.contains(index);
    }

    private static int tileSize(ShellConfig.Rail rail) {
        return rail.tileSize > 0 ? rail.tileSize : 46;
    }

    /**
     * Total rail height. The root has to be told its height explicitly: a
     * bottom-anchored {@code LayoutMode: Top} group lays its children out from
     * the top of the area it was given, so an area that does not match the
     * content would float the tiles away from the chat panel.
     */
    private int railHeight(ShellConfig.Rail rail) {
        int tiles = slots.size() * (tileSize(rail) + TILE_GAP);
        int separators = 0;
        for (ShortcutSlot slot : slots) {
            if (separatorAfter(rail, slot.index())) {
                separators += TILE_GAP;
            }
        }
        return TITLE_HEIGHT + tiles + separators + HINT_HEIGHT;
    }
}
