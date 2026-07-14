package org.hyzionstudios.mysticessentials.api.rtp;

import java.util.Map;

import org.hyzionstudios.mysticessentials.api.model.MysticLocation;

/**
 * Result of a destination search.
 *
 * @param found          whether a safe location was found.
 * @param location       the validated destination (null when {@code found} is false).
 * @param attempts       how many candidate coordinates were evaluated.
 * @param failureReason  short reason when no destination was found (null on success).
 * @param rejectionTally per-reason rejection counts, for {@code /rtpadmin debug}.
 */
public record RtpDestinationResult(
        boolean found,
        MysticLocation location,
        int attempts,
        String failureReason,
        Map<String, Integer> rejectionTally) {

    public static RtpDestinationResult found(MysticLocation location, int attempts, Map<String, Integer> tally) {
        return new RtpDestinationResult(true, location, attempts, null, tally);
    }

    public static RtpDestinationResult notFound(int attempts, String reason, Map<String, Integer> tally) {
        return new RtpDestinationResult(false, null, attempts, reason, tally);
    }
}
