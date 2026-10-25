package org.hyzionstudios.mysticessentials.modules.lootbox.presentation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Builds the cosmetic timeline for an opening whose result is already
 * committed (Bible v2.0 §08 "Deterministic strip planning"). Engine-free, so
 * {@code verifyLootBoxPresentation} checks it directly.
 *
 * <p><b>The result is an input, never an output.</b> The planner receives the
 * winning entry's position and arranges the strip so the stopping slot shows
 * exactly that entry. Its randomness is a {@link SplittableRandom} seeded from
 * the opening id: it only decides which possible rewards fill the decorative
 * slots, so the same opening always replays the same way and nothing here can
 * influence what the player receives.</p>
 *
 * <p><b>No odds from spacing.</b> Filler slots pick uniformly from the possible
 * rewards, not by weight, so how often a reward passes the marker says nothing
 * about its chance. The odds catalog on the same screen is the only probability
 * display.</p>
 *
 * <p><b>Stepped updates.</b> Each step is one UI push. Gaps start at
 * {@code 1000 / maxStepsPerSecond} and only grow (an ease-out), so the push rate
 * never exceeds the Bible §09 baseline of 8 per second per opening.</p>
 */
public final class TimelinePlanner {

    /** Reward slots visible on the reel at once; the marker is on the middle one. */
    public static final int REEL_WINDOW = 7;
    /** Cells in the roulette grid (3 rows of 4). */
    public static final int GRID_CELLS = 12;
    /** Bible §09 baseline: at most this many UI pushes per second per opening. */
    public static final int MAX_STEPS_PER_SECOND = 8;
    /** Share of the duration spent at the fastest rate before slowing; the rest eases out. */
    private static final double FAST_SHARE = 0.6;
    private static final int MIN_STEPS = 6;

    private TimelinePlanner() {
    }

    /**
     * One opening's presentation plan.
     *
     * @param slots     table-entry positions shown in each slot: the reel's strip
     *                  left to right, or the grid's cells in reading order
     * @param stop      the slot that ends under the marker; {@code slots.get(stop)}
     *                  is always the winning entry
     * @param start     the reel's first window offset, or the grid's first lit cell
     * @param stepAtMs  when each step is pushed, in ms after the reveal opens;
     *                  empty for Quick Reveal
     */
    public record Timeline(PresentationMode mode, List<Integer> slots, int stop, int start, List<Long> stepAtMs) {

        /** The reel's left-most visible slot after {@code step} steps (0 = as built). */
        public int reelOffset(int step) {
            return start + step;
        }

        /** The grid cell lit after {@code step} steps (0 = as built). */
        public int gridCell(int step) {
            return Math.floorMod(start + step, slots.size());
        }

        public int steps() {
            return stepAtMs.size();
        }
    }

    /**
     * Plans {@code mode} for a table of {@code entryCount} possible rewards whose
     * committed result is entry {@code winner}.
     *
     * @param durationMs  how long the animation should run; {@code <= 0} plans a Quick Reveal
     * @param seed        cosmetic seed (the opening id's hash); never the draw's randomness
     */
    public static Timeline plan(PresentationMode mode, int entryCount, int winner, long durationMs,
            int maxStepsPerSecond, long seed) {
        if (entryCount <= 0 || winner < 0 || winner >= entryCount) {
            throw new IllegalArgumentException("winner " + winner + " is not one of " + entryCount + " entries");
        }
        if (mode == PresentationMode.QUICK_REVEAL || durationMs <= 0) {
            return new Timeline(PresentationMode.QUICK_REVEAL, List.of(winner), 0, 0, List.of());
        }
        int rate = Math.max(1, Math.min(MAX_STEPS_PER_SECOND, maxStepsPerSecond));
        List<Long> times = schedule(durationMs, rate);
        SplittableRandom random = new SplittableRandom(seed);
        return mode == PresentationMode.GRID_ROULETTE
                ? grid(entryCount, winner, times, random)
                : reel(entryCount, winner, times, random);
    }

    /**
     * The strip is {@code steps + REEL_WINDOW} long. The window starts at 0 and
     * moves one slot per step, so after the last step its middle slot is
     * {@code steps + REEL_WINDOW / 2}, which holds the winner.
     */
    private static Timeline reel(int entryCount, int winner, List<Long> times, SplittableRandom random) {
        int steps = times.size();
        int length = steps + REEL_WINDOW;
        int stop = steps + REEL_WINDOW / 2;
        List<Integer> strip = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            strip.add(i == stop ? winner : random.nextInt(entryCount));
        }
        return new Timeline(PresentationMode.HORIZONTAL_REEL, List.copyOf(strip), stop, 0, times);
    }

    /**
     * The grid shows up to {@link #GRID_CELLS} distinct rewards, always including
     * the winner, in a shuffled order. The light walks forward one cell per step
     * and its start is chosen so the last step lands on the winner's cell.
     */
    private static Timeline grid(int entryCount, int winner, List<Long> times, SplittableRandom random) {
        List<Integer> others = new ArrayList<>(entryCount - 1);
        for (int i = 0; i < entryCount; i++) {
            if (i != winner) {
                others.add(i);
            }
        }
        shuffle(others, random);
        List<Integer> cells = new ArrayList<>(Math.min(GRID_CELLS, entryCount));
        cells.add(winner);
        cells.addAll(others.subList(0, Math.min(others.size(), GRID_CELLS - 1)));
        shuffle(cells, random);
        int stop = cells.indexOf(winner);
        int start = Math.floorMod(stop - times.size(), cells.size());
        return new Timeline(PresentationMode.GRID_ROULETTE, List.copyOf(cells), stop, start, times);
    }

    /**
     * Step times: {@code steps} gaps that start at the minimum gap
     * ({@code 1000 / rate}) and grow linearly so they add up to the duration.
     * Every gap is at least the minimum, so the push rate never exceeds {@code rate}.
     */
    static List<Long> schedule(long durationMs, int rate) {
        double minGap = 1000.0 / rate;
        int steps = (int) Math.max(MIN_STEPS, Math.floor(FAST_SHARE * durationMs / minGap));
        if (steps * minGap > durationMs) {
            // Too short for MIN_STEPS at this rate: run at the rate and end late.
            durationMs = (long) Math.ceil(steps * minGap);
        }
        double grow = steps > 1 ? 2.0 * (durationMs - steps * minGap) / ((double) steps * (steps - 1)) : 0.0;
        List<Long> times = new ArrayList<>(steps);
        double at = 0;
        for (int k = 0; k < steps; k++) {
            at += minGap + grow * k;
            times.add(Math.round(at));
        }
        return Collections.unmodifiableList(times);
    }

    private static void shuffle(List<Integer> list, SplittableRandom random) {
        for (int i = list.size() - 1; i > 0; i--) {
            Collections.swap(list, i, random.nextInt(i + 1));
        }
    }
}
