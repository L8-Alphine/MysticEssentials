package org.hyzionstudios.mysticessentials.api.rtp;

/**
 * Boundary shape a Random Teleport profile samples candidate coordinates from.
 *
 * <p>{@code WORLD_BORDER} is honoured on a best-effort basis: the verified 0.5.6
 * server API does not expose a world-border query, so a profile using it samples
 * a large square around its configured centre bounded by
 * {@link RtpProfile#maximumRadius}.</p>
 */
public enum RtpShape {
    /** A filled disc between {@code minimumRadius} and {@code maximumRadius} of the centre. */
    CIRCLE,
    /** A filled square of half-extent {@code maximumRadius}, excluding an inner {@code minimumRadius} square. */
    SQUARE,
    /** An axis-aligned rectangle of {@code halfWidth} x {@code halfDepth} (falls back to {@code maximumRadius}). */
    RECTANGLE,
    /** An annulus: only the band between {@code minimumRadius} (inner) and {@code maximumRadius} (outer). */
    RING,
    /** An arbitrary polygon defined by {@link RtpProfile#polygon} vertices. */
    POLYGON,
    /** The world border (degrades to a {@code maximumRadius} square where no border API is available). */
    WORLD_BORDER
}
