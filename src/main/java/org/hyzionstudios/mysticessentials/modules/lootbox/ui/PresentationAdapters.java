package org.hyzionstudios.mysticessentials.modules.lootbox.ui;

import java.util.EnumMap;
import java.util.Map;

import org.hyzionstudios.mysticessentials.core.message.MysticText;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.presentation.PresentationMode;
import org.hyzionstudios.mysticessentials.modules.lootbox.presentation.TimelinePlanner;
import org.hyzionstudios.mysticessentials.platform.ui.ItemCatalog;
import org.hyzionstudios.mysticessentials.platform.ui.MysticPage;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/**
 * The registered {@link PresentationAdapter}s, one per {@link PresentationMode}.
 * Bible v2.0 §14: Quick Reveal and the Horizontal Reel ship first, then the Grid
 * Roulette. A later mode (Wheel, Cards, Showcase, World, Mythic) is added by
 * registering its adapter here alongside a new mode constant and its stage in
 * {@code LootBoxReveal.ui}.
 */
public final class PresentationAdapters {

    private static final Map<PresentationMode, PresentationAdapter> ADAPTERS = new EnumMap<>(PresentationMode.class);

    static {
        register(new QuickReveal());
        register(new HorizontalReel());
        register(new GridRoulette());
    }

    private PresentationAdapters() {
    }

    private static void register(PresentationAdapter adapter) {
        ADAPTERS.put(adapter.mode(), adapter);
    }

    /** The adapter for {@code mode}; Quick Reveal when none is registered. */
    public static PresentationAdapter of(PresentationMode mode) {
        return ADAPTERS.getOrDefault(mode, ADAPTERS.get(PresentationMode.QUICK_REVEAL));
    }

    // ----- Shared drawing -----------------------------------------------------

    /** Writes {@code reward}'s icon and name into the cell at {@code cell}. */
    static void drawCell(UICommandBuilder cmd, String cell, LootBoxDraft.RewardSpec reward) {
        String icon = icon(reward);
        cmd.set(cell + " #Icon.Visible", icon != null);
        if (icon != null) {
            cmd.set(cell + " #Icon.ItemId", icon);
        }
        String name = reward.name == null || reward.name.isBlank() ? reward.id : MysticText.stripMarkup(reward.name);
        cmd.set(cell + " #Name.TextSpans", MysticPage.uiText(cell + " #Name.TextSpans", name));
    }

    private static String icon(LootBoxDraft.RewardSpec reward) {
        return reward.item != null && reward.item.itemId != null && !reward.item.itemId.isBlank()
                && ItemCatalog.exists(reward.item.itemId) ? reward.item.itemId : null;
    }

    private static void show(UICommandBuilder cmd, String visibleStage) {
        for (String stage : new String[] {"#QuickStage", "#ReelStage", "#GridStage"}) {
            cmd.set(stage + ".Visible", stage.equals(visibleStage));
        }
    }

    // ----- Quick Reveal -------------------------------------------------------

    /** No motion: the stage names the result at once. The reduced-motion path. */
    static final class QuickReveal implements PresentationAdapter {

        @Override
        public PresentationMode mode() {
            return PresentationMode.QUICK_REVEAL;
        }

        @Override
        public void build(UICommandBuilder cmd, Stage stage) {
            show(cmd, "#QuickStage");
            cmd.set("#QuickLine.TextSpans", MysticPage.uiText("#QuickLine.TextSpans", "&7Revealing..."));
        }

        @Override
        public void step(UICommandBuilder cmd, Stage stage, int step) {
            // Quick Reveal has no steps.
        }

        @Override
        public void land(UICommandBuilder cmd, Stage stage) {
            LootBoxDraft.RewardSpec reward = stage.rewards().get(stage.plan().slots().get(stage.plan().stop()));
            String name = reward.name == null || reward.name.isBlank() ? reward.id : reward.name;
            cmd.set("#QuickLine.TextSpans", MysticPage.uiText("#QuickLine.TextSpans", name));
        }
    }

    // ----- Horizontal Reel ----------------------------------------------------

    /**
     * Seven cells over the planned strip; each step slides the window one slot
     * left. The middle cell ({@code #Reel3}) carries the marker, and after the
     * last step it holds the committed result.
     */
    static final class HorizontalReel implements PresentationAdapter {

        @Override
        public PresentationMode mode() {
            return PresentationMode.HORIZONTAL_REEL;
        }

        @Override
        public void build(UICommandBuilder cmd, Stage stage) {
            show(cmd, "#ReelStage");
            draw(cmd, stage, 0);
        }

        @Override
        public void step(UICommandBuilder cmd, Stage stage, int step) {
            draw(cmd, stage, step);
        }

        @Override
        public void land(UICommandBuilder cmd, Stage stage) {
            draw(cmd, stage, stage.plan().steps());
        }

        private static void draw(UICommandBuilder cmd, Stage stage, int step) {
            int offset = stage.plan().reelOffset(step);
            for (int cell = 0; cell < TimelinePlanner.REEL_WINDOW; cell++) {
                int entry = stage.plan().slots().get(offset + cell);
                drawCell(cmd, "#Reel" + cell, stage.rewards().get(entry));
            }
        }
    }

    // ----- Grid Roulette ------------------------------------------------------

    /** Up to twelve rewards in a grid; a light walks the cells and rests on the result. */
    static final class GridRoulette implements PresentationAdapter {

        @Override
        public PresentationMode mode() {
            return PresentationMode.GRID_ROULETTE;
        }

        @Override
        public void build(UICommandBuilder cmd, Stage stage) {
            show(cmd, "#GridStage");
            int cells = stage.plan().slots().size();
            for (int cell = 0; cell < TimelinePlanner.GRID_CELLS; cell++) {
                String id = "#Cell" + cell;
                cmd.set(id + ".Visible", cell < cells);
                if (cell < cells) {
                    drawCell(cmd, id, stage.rewards().get(stage.plan().slots().get(cell)));
                }
                cmd.set(id + " #Hl.Visible", cell < cells && cell == stage.plan().gridCell(0));
            }
        }

        @Override
        public void step(UICommandBuilder cmd, Stage stage, int step) {
            cmd.set("#Cell" + stage.plan().gridCell(step - 1) + " #Hl.Visible", false);
            cmd.set("#Cell" + stage.plan().gridCell(step) + " #Hl.Visible", true);
        }

        @Override
        public void land(UICommandBuilder cmd, Stage stage) {
            for (int cell = 0; cell < stage.plan().slots().size(); cell++) {
                cmd.set("#Cell" + cell + " #Hl.Visible", cell == stage.plan().stop());
            }
        }
    }
}
