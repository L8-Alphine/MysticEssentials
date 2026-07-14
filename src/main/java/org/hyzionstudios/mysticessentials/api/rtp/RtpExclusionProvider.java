package org.hyzionstudios.mysticessentials.api.rtp;

import java.util.UUID;

/**
 * Registered by claim/region systems (MysticGuilds, other protection mods) to
 * veto Random Teleport destinations that fall inside protected land. Register via
 * {@link RandomTeleportService#registerExclusionProvider}.
 */
public interface RtpExclusionProvider {

    /** Human-readable name for debug output. */
    String name();

    /**
     * @param requestingPlayer the player being teleported (a provider may allow a
     *                         player's own claim), or {@code null} for an
     *                         anonymous search.
     * @return {@code true} if the coordinate is excluded and must not be used.
     */
    boolean isExcluded(String world, double x, double z, RtpProfile profile, UUID requestingPlayer);
}
