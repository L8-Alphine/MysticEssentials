package org.hyzionstudios.mysticessentials.modules.teleportation.rtp;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.hyzionstudios.mysticessentials.api.model.PlayerProfile;

import com.google.gson.JsonObject;

/**
 * Reads and writes a player's persistent Random Teleport state — per-profile
 * cooldown expiries and hourly/daily usage counters — inside their profile's
 * {@code moduleData["teleportation"]["rtp"]} blob (spec §15). Cooldowns are
 * persisted (not just held in the in-memory {@code CooldownService}) so they
 * survive restarts.
 *
 * <p>All methods expect the caller to hold the profile's monitor, matching the
 * {@code synchronized (profile)} pattern used elsewhere in the Teleportation
 * module.</p>
 */
final class RtpPlayerData {

    private static final String MODULE_KEY = "teleportation";
    private static final String RTP_KEY = "rtp";
    private static final DateTimeFormatter HOUR_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH").withZone(ZoneOffset.UTC);

    private RtpPlayerData() {
    }

    private static JsonObject rtp(PlayerProfile profile, boolean create) {
        JsonObject module = create
                ? profile.getModuleData().computeIfAbsent(MODULE_KEY, k -> new JsonObject())
                : profile.getModuleData().get(MODULE_KEY);
        if (module == null) {
            return null;
        }
        if (!module.has(RTP_KEY)) {
            if (!create) {
                return null;
            }
            module.add(RTP_KEY, new JsonObject());
        }
        return module.getAsJsonObject(RTP_KEY);
    }

    private static JsonObject child(JsonObject parent, String key) {
        if (!parent.has(key) || !parent.get(key).isJsonObject()) {
            parent.add(key, new JsonObject());
        }
        return parent.getAsJsonObject(key);
    }

    // ----- Cooldowns ---------------------------------------------------------

    /** @return remaining whole seconds of cooldown for {@code profileId}, or 0 if ready. */
    static long remainingCooldown(PlayerProfile profile, String profileId) {
        JsonObject rtp = rtp(profile, false);
        if (rtp == null || !rtp.has("cooldowns")) {
            return 0;
        }
        JsonObject cooldowns = rtp.getAsJsonObject("cooldowns");
        if (!cooldowns.has(profileId)) {
            return 0;
        }
        try {
            Instant expiry = Instant.parse(cooldowns.get(profileId).getAsString());
            long remaining = expiry.getEpochSecond() - Instant.now().getEpochSecond();
            return Math.max(0, remaining);
        } catch (RuntimeException malformed) {
            return 0;
        }
    }

    static void setCooldown(PlayerProfile profile, String profileId, long seconds) {
        if (seconds <= 0) {
            return;
        }
        JsonObject cooldowns = child(rtp(profile, true), "cooldowns");
        cooldowns.addProperty(profileId, Instant.now().plusSeconds(seconds).toString());
    }

    static void clearCooldown(PlayerProfile profile, String profileId) {
        JsonObject rtp = rtp(profile, false);
        if (rtp != null && rtp.has("cooldowns")) {
            rtp.getAsJsonObject("cooldowns").remove(profileId);
        }
    }

    // ----- Usage limits ------------------------------------------------------

    static int usageToday(PlayerProfile profile, String profileId) {
        return usage(profile, profileId, "dailyUsage", "date", today());
    }

    static int usageThisHour(PlayerProfile profile, String profileId) {
        return usage(profile, profileId, "hourlyUsage", "hour", thisHour());
    }

    /** Records one successful use against both the daily and hourly counters. */
    static void recordUse(PlayerProfile profile, String profileId) {
        increment(profile, profileId, "dailyUsage", "date", today());
        increment(profile, profileId, "hourlyUsage", "hour", thisHour());
    }

    static void recordSuccess(PlayerProfile profile, String profileId) {
        JsonObject rtp = rtp(profile, true);
        rtp.addProperty("lastSuccessfulTeleport", Instant.now().toString());
        rtp.addProperty("lastProfile", profileId);
    }

    private static int usage(PlayerProfile profile, String profileId, String bucketKey, String periodKey,
            String period) {
        JsonObject rtp = rtp(profile, false);
        if (rtp == null || !rtp.has(bucketKey)) {
            return 0;
        }
        JsonObject bucket = rtp.getAsJsonObject(bucketKey);
        if (!period.equals(optString(bucket, periodKey))) {
            return 0;
        }
        JsonObject profiles = bucket.has("profiles") ? bucket.getAsJsonObject("profiles") : null;
        if (profiles == null || !profiles.has(profileId)) {
            return 0;
        }
        try {
            return profiles.get(profileId).getAsInt();
        } catch (RuntimeException malformed) {
            return 0;
        }
    }

    private static void increment(PlayerProfile profile, String profileId, String bucketKey, String periodKey,
            String period) {
        JsonObject bucket = child(rtp(profile, true), bucketKey);
        if (!period.equals(optString(bucket, periodKey))) {
            bucket.addProperty(periodKey, period);
            bucket.add("profiles", new JsonObject());
        }
        JsonObject profiles = child(bucket, "profiles");
        int current = profiles.has(profileId) ? profiles.get(profileId).getAsInt() : 0;
        profiles.addProperty(profileId, current + 1);
    }

    private static String optString(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : null;
    }

    private static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    private static String thisHour() {
        return HOUR_FMT.format(Instant.now());
    }
}
