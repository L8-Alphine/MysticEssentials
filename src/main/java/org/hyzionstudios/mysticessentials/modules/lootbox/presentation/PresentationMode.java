package org.hyzionstudios.mysticessentials.modules.lootbox.presentation;

import java.util.Locale;

/**
 * How an opening is shown (Bible v2.0 §05). Every mode only presents an outcome
 * that {@code OpeningService} already drew and recorded; none of them draws.
 *
 * <p>Bible §14 orders the rollout: Quick Reveal and the Horizontal Reel first,
 * then the Grid Roulette, and only after those the premium cinematics (Wheel,
 * Cards, Showcase, World, Mythic). Those are deliberately not listed here: a new
 * mode is a new constant plus a {@code RevealStage} registered for it, never a
 * placeholder that half-works.</p>
 */
public enum PresentationMode {
    /** No animation: the result and the odds at once. Also the reduced-motion path. */
    QUICK_REVEAL,
    /** A horizontal strip of rewards slides under a centre marker and stops on the result. */
    HORIZONTAL_REEL,
    /** A highlight runs around a grid of rewards and settles on the result. */
    GRID_ROULETTE;

    /** The mode {@code name} names (any case, {@code -} or {@code _}), else {@code fallback}. */
    public static PresentationMode parse(String name, PresentationMode fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(name.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
