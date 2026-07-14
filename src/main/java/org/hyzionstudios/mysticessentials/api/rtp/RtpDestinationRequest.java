package org.hyzionstudios.mysticessentials.api.rtp;

import java.util.UUID;

/**
 * A search-only request: find a safe destination for a profile without moving
 * anyone. Used by {@code /rtpadmin test} and internally by
 * {@link RandomTeleportService#teleport}.
 *
 * @param profileId        the profile to search.
 * @param requestingPlayer the player the search is for (for player-relative
 *                         filters such as minimum distance), or {@code null} for
 *                         an anonymous admin test.
 */
public record RtpDestinationRequest(String profileId, UUID requestingPlayer) {

    public static RtpDestinationRequest of(String profileId) {
        return new RtpDestinationRequest(profileId, null);
    }
}
