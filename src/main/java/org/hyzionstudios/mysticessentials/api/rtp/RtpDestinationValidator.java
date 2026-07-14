package org.hyzionstudios.mysticessentials.api.rtp;

import java.util.Optional;

import org.hyzionstudios.mysticessentials.api.model.MysticLocation;

/**
 * Pluggable validator run against each candidate destination. Register via
 * {@link RandomTeleportService#registerValidator}. Other modules (region
 * protection, custom hazard rules) contribute here so destination search stays
 * centralized.
 */
@FunctionalInterface
public interface RtpDestinationValidator {

    /**
     * @return empty if the candidate is acceptable, or a short machine-friendly
     *         rejection reason (used in {@code RtpCandidateRejectedEvent} and the
     *         {@code /rtpadmin debug} tally).
     */
    Optional<String> reject(RtpProfile profile, MysticLocation candidate);

    /** Human-readable name for debug output. */
    default String name() {
        return getClass().getSimpleName();
    }
}
