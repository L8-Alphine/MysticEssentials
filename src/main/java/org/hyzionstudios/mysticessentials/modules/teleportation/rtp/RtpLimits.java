package org.hyzionstudios.mysticessentials.modules.teleportation.rtp;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.api.rtp.RtpProfile;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Resolves a player's <em>effective</em> RTP cooldown, usage limits, and queue
 * priority, applying the dynamic numeric permission overrides from spec §8
 * ({@code ...rtp.cooldown.<n>}, {@code ...rtp.limit.daily.<n>}, etc.).
 *
 * <p>A player's granted numeric nodes cannot be enumerated through
 * {@code hasPermission}, so each value is resolved by probing a graduated tier
 * set — the same technique EssentialsX-style permission tiers rely on. Operators
 * grant a specific tier node (e.g. {@code ...cooldown.60}); the closest matching
 * tier wins ("lowest cooldown", "highest limit/priority").</p>
 */
final class RtpLimits {

    private static final int[] COOLDOWN_TIERS =
            {0, 1, 5, 10, 15, 30, 45, 60, 90, 120, 180, 300, 450, 600, 900, 1200, 1800, 2700, 3600};
    private static final int[] LIMIT_TIERS =
            {1, 2, 3, 5, 8, 10, 15, 20, 30, 50, 75, 100, 200};
    private static final int[] PRIORITY_TIERS =
            {1, 5, 10, 20, 25, 50, 75, 100, 200, 500, 1000};

    private RtpLimits() {
    }

    /** Effective cooldown in seconds; 0 when a bypass applies. Lowest granted tier wins. */
    static int cooldownSeconds(PlayerRef player, RtpProfile profile) {
        if (player.hasPermission(Permissions.RTP_BYPASS_COOLDOWN)) {
            return 0;
        }
        int effective = Math.max(0, profile.cooldownSeconds);
        for (int tier : COOLDOWN_TIERS) {
            if (tier < effective && player.hasPermission(Permissions.RTP_COOLDOWN_BASE + "." + tier)) {
                effective = tier;
            }
        }
        return effective;
    }

    /** Effective daily use limit; 0 = unlimited. Highest granted tier wins over the profile value. */
    static int dailyLimit(PlayerRef player, RtpProfile profile) {
        if (player.hasPermission(Permissions.RTP_BYPASS_LIMITS)) {
            return 0;
        }
        return highestTier(player, Permissions.RTP_LIMIT_DAILY_BASE, Math.max(0, profile.filters.maxUsesPerDay));
    }

    /** Effective hourly use limit; 0 = unlimited. Highest granted tier wins over the profile value. */
    static int hourlyLimit(PlayerRef player, RtpProfile profile) {
        if (player.hasPermission(Permissions.RTP_BYPASS_LIMITS)) {
            return 0;
        }
        return highestTier(player, Permissions.RTP_LIMIT_HOURLY_BASE, Math.max(0, profile.filters.maxUsesPerHour));
    }

    /** Queue priority; higher is served first. Highest granted tier wins. */
    static int priority(PlayerRef player) {
        int effective = 0;
        for (int tier : PRIORITY_TIERS) {
            if (tier > effective && player.hasPermission(Permissions.RTP_PRIORITY_BASE + "." + tier)) {
                effective = tier;
            }
        }
        return effective;
    }

    private static int highestTier(PlayerRef player, String base, int fallback) {
        int effective = fallback;
        for (int tier : LIMIT_TIERS) {
            if (tier > effective && player.hasPermission(base + "." + tier)) {
                effective = tier;
            }
        }
        return effective;
    }
}
