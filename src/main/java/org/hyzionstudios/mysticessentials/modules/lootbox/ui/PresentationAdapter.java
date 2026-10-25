package org.hyzionstudios.mysticessentials.modules.lootbox.ui;

import java.util.List;

import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.presentation.PresentationMode;
import org.hyzionstudios.mysticessentials.modules.lootbox.presentation.TimelinePlanner.Timeline;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/**
 * Draws one {@link PresentationMode} on the reveal page's stage (Bible v2.0
 * §03 PresentationAdapter). An adapter only renders the {@link Timeline} it is
 * given: it never draws, never picks the result, and never touches the odds
 * catalog, which the reveal page keeps on screen itself.
 *
 * <p>Each call writes into one {@link UICommandBuilder} that the page sends as
 * a single update, so one step is one push; the planner keeps those within the
 * per-opening budget.</p>
 */
public interface PresentationAdapter {

    PresentationMode mode();

    /** Shows this adapter's stage and draws it as it looks before the first step. */
    void build(UICommandBuilder cmd, Stage stage);

    /** Draws step {@code step} (1-based; the last is {@code stage.plan().steps()}). */
    void step(UICommandBuilder cmd, Stage stage, int step);

    /** Draws the resting state on the committed result: after the last step, or at once on Skip. */
    void land(UICommandBuilder cmd, Stage stage);

    /**
     * What an adapter draws from: the plan, and the reward behind each odds-table
     * entry ({@code rewards.get(i)} is entry {@code i}'s reward).
     */
    record Stage(Timeline plan, List<LootBoxDraft.RewardSpec> rewards) {
    }
}
