package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.util.Locale;

import org.hyzionstudios.mysticessentials.modules.lootbox.model.PlayerLootState;

/**
 * Reduced motion (P2-L, bible §10): each player can turn the reveal animation
 * off, and a player who never chose follows {@code opening.reducedMotionDefault}.
 * With reduced motion the reveal lands on the committed result at once. It never
 * changes what was drawn, only how it is shown. Engine-free, so
 * {@code verifyLootBoxAccessibility} checks it directly.
 */
public final class MotionRules {

    /** The reveal's frame rate is kept within what spike S7 measured as smooth. */
    public static final int MAX_FRAMES_PER_SECOND = 30;

    /** What {@code /lootbox motion <choice>} asked for. */
    public enum Choice {
        REDUCED(Boolean.TRUE),
        FULL(Boolean.FALSE),
        /** Forget the player's choice and follow the server default again. */
        DEFAULT(null);

        private final Boolean stored;

        Choice(Boolean stored) {
            this.stored = stored;
        }

        /** The value kept in {@link PlayerLootState#reducedMotion}. */
        public Boolean stored() {
            return stored;
        }
    }

    private MotionRules() {
    }

    /** Whether {@code state}'s player sees the reveal without animation. */
    public static boolean reduced(PlayerLootState state, boolean serverDefault) {
        return state == null || state.reducedMotion == null ? serverDefault : state.reducedMotion;
    }

    /** {@code framesPerSecond} kept within 1 and {@link #MAX_FRAMES_PER_SECOND}. */
    public static int framesPerSecond(int configured) {
        return Math.max(1, Math.min(MAX_FRAMES_PER_SECOND, configured));
    }

    /** How many frames the reveal animates before it lands; 0 lands at once. */
    public static int revealFrames(long durationMs, int framesPerSecond, boolean reduced) {
        if (reduced || durationMs <= 0) {
            return 0;
        }
        long frames = durationMs * framesPerSecond(framesPerSecond) / 1000L;
        return (int) Math.min(Integer.MAX_VALUE, frames);
    }

    /** The choice {@code argument} names, or {@code null} when it names none. */
    public static Choice parse(String argument) {
        if (argument == null) {
            return null;
        }
        // No on/off: "motion off" reads as "reduced" to one player and "full" to another.
        return switch (argument.trim().toLowerCase(Locale.ROOT)) {
            case "reduced" -> Choice.REDUCED;
            case "full" -> Choice.FULL;
            case "default" -> Choice.DEFAULT;
            default -> null;
        };
    }

    /** How a player's setting reads in chat and on the Catalog. */
    public static String describe(PlayerLootState state, boolean serverDefault) {
        boolean reduced = reduced(state, serverDefault);
        String mode = reduced ? "reduced" : "full";
        return state == null || state.reducedMotion == null ? mode + " (server default)" : mode;
    }
}
