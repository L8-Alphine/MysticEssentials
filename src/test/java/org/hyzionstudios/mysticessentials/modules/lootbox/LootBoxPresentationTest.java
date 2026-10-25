package org.hyzionstudios.mysticessentials.modules.lootbox;

import java.util.List;

import org.hyzionstudios.mysticessentials.modules.lootbox.presentation.PresentationMode;
import org.hyzionstudios.mysticessentials.modules.lootbox.presentation.TimelinePlanner;
import org.hyzionstudios.mysticessentials.modules.lootbox.presentation.TimelinePlanner.Timeline;

/**
 * The presentation planner (Bible v2.0 §08/§09): the stopping slot always shows
 * the committed result, pushes never exceed the per-opening rate, and the plan
 * is a pure function of the opening. Run with {@code ./gradlew verifyLootBoxPresentation}.
 */
public final class LootBoxPresentationTest {

    public static void main(String[] args) {
        quickRevealHasNoSteps();
        everyPlanStopsOnTheCommittedResult();
        pushesStayWithinTheBudget();
        plansAreDeterministicPerOpening();
        gridShowsDistinctRewardsIncludingTheWinner();
        modesParse();
        System.out.println("LootBoxPresentationTest: all checks passed.");
    }

    private static void quickRevealHasNoSteps() {
        Timeline quick = TimelinePlanner.plan(PresentationMode.QUICK_REVEAL, 5, 3, 4000, 8, 1L);
        require(quick.steps() == 0 && quick.slots().get(quick.stop()) == 3, "Quick Reveal animated or lost the result");
        Timeline zero = TimelinePlanner.plan(PresentationMode.HORIZONTAL_REEL, 5, 3, 0, 8, 1L);
        require(zero.mode() == PresentationMode.QUICK_REVEAL, "a zero duration still planned a reel");
        try {
            TimelinePlanner.plan(PresentationMode.HORIZONTAL_REEL, 5, 5, 4000, 8, 1L);
            throw new AssertionError("a winner outside the table was accepted");
        } catch (IllegalArgumentException expected) {
            // The planner never invents a result.
        }
    }

    private static void everyPlanStopsOnTheCommittedResult() {
        for (PresentationMode mode : List.of(PresentationMode.HORIZONTAL_REEL, PresentationMode.GRID_ROULETTE)) {
            for (int entries = 1; entries <= 40; entries += 3) {
                for (int winner = 0; winner < entries; winner += Math.max(1, entries / 4)) {
                    for (long duration : new long[] {300, 1500, 4000, 9000}) {
                        Timeline plan = TimelinePlanner.plan(mode, entries, winner, duration, 8, winner * 31L + entries);
                        require(plan.slots().get(plan.stop()) == winner, mode + " stopped on the wrong slot");
                        for (int slot : plan.slots()) {
                            require(slot >= 0 && slot < entries, mode + " showed an entry that is not in the table");
                        }
                        if (mode == PresentationMode.HORIZONTAL_REEL) {
                            int middle = plan.reelOffset(plan.steps()) + TimelinePlanner.REEL_WINDOW / 2;
                            require(middle == plan.stop(), "the reel's last window is not centred on the result");
                            require(plan.reelOffset(plan.steps()) + TimelinePlanner.REEL_WINDOW
                                    <= plan.slots().size(), "the reel ran off the end of its strip");
                        } else {
                            require(plan.gridCell(plan.steps()) == plan.stop(), "the roulette's last cell isn't the result");
                        }
                    }
                }
            }
        }
    }

    private static void pushesStayWithinTheBudget() {
        for (int rate : new int[] {1, 4, 8, 30}) {
            for (long duration : new long[] {200, 1000, 4000, 12000}) {
                Timeline plan = TimelinePlanner.plan(PresentationMode.HORIZONTAL_REEL, 10, 4, duration, rate, 9L);
                double minGap = 1000.0 / Math.min(rate, TimelinePlanner.MAX_STEPS_PER_SECOND);
                long previous = 0;
                for (long at : plan.stepAtMs()) {
                    // Times are rounded to whole ms, so allow one ms of rounding.
                    require(at - previous >= minGap - 1, "steps came faster than " + rate + "/s: " + plan.stepAtMs());
                    previous = at;
                }
                require(plan.steps() >= 6, "a reel had too few steps to read as motion");
                long last = plan.stepAtMs().get(plan.steps() - 1);
                require(last >= duration - 2 || plan.steps() * minGap >= duration - 1,
                        "the reel ended early: " + last + " of " + duration);
            }
        }
    }

    private static void plansAreDeterministicPerOpening() {
        Timeline a = TimelinePlanner.plan(PresentationMode.HORIZONTAL_REEL, 12, 7, 4000, 8, 42L);
        Timeline b = TimelinePlanner.plan(PresentationMode.HORIZONTAL_REEL, 12, 7, 4000, 8, 42L);
        require(a.equals(b), "the same opening planned two different reels");
        Timeline c = TimelinePlanner.plan(PresentationMode.HORIZONTAL_REEL, 12, 7, 4000, 8, 43L);
        require(!a.slots().equals(c.slots()), "different openings planned the same filler");
    }

    private static void gridShowsDistinctRewardsIncludingTheWinner() {
        Timeline small = TimelinePlanner.plan(PresentationMode.GRID_ROULETTE, 3, 2, 4000, 8, 5L);
        require(small.slots().size() == 3 && small.slots().containsAll(List.of(0, 1, 2)),
                "a small grid didn't show every reward once: " + small.slots());
        Timeline big = TimelinePlanner.plan(PresentationMode.GRID_ROULETTE, 50, 49, 4000, 8, 5L);
        require(big.slots().size() == TimelinePlanner.GRID_CELLS && big.slots().contains(49)
                && big.slots().stream().distinct().count() == TimelinePlanner.GRID_CELLS,
                "a big grid lost the winner or repeated a reward: " + big.slots());
    }

    private static void modesParse() {
        require(PresentationMode.parse("horizontal-reel", null) == PresentationMode.HORIZONTAL_REEL
                && PresentationMode.parse(" grid_roulette ", null) == PresentationMode.GRID_ROULETTE
                && PresentationMode.parse("wheel", PresentationMode.QUICK_REVEAL) == PresentationMode.QUICK_REVEAL
                && PresentationMode.parse(null, PresentationMode.HORIZONTAL_REEL) == PresentationMode.HORIZONTAL_REEL,
                "presentation modes didn't parse");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
