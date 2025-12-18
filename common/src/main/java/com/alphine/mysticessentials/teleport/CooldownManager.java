package com.alphine.mysticessentials.teleport;

import com.alphine.mysticessentials.config.MEConfig;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player cooldowns keyed by a logical action string (home/warp/tp/tpa/spawn...).
 * Cooldowns are stamped AFTER successful teleports by TeleportExecutor.
 */
public final class CooldownManager {

    // per-player -> (key -> untilEpochMs)
    private final Map<UUID, Map<String, Long>> untilByPlayer = new ConcurrentHashMap<>();

    // key -> defaultSeconds (from config)
    private final Map<String, Integer> defaults = new ConcurrentHashMap<>();

    public CooldownManager() {
        updateFromConfig();
    }

    /** True if this key has a non-zero default cooldown. */
    public boolean hasDefault(String key) {
        return getDefaultSeconds(key) > 0;
    }

    /** Remaining seconds (rounded up), 0 if none. */
    public long remainingSeconds(UUID id, String key, long nowMs) {
        if (id == null || key == null) return 0;
        Map<String, Long> m = untilByPlayer.get(id);
        if (m == null) return 0;

        long until = m.getOrDefault(norm(key), 0L);
        long remMs = until - nowMs;
        return remMs <= 0 ? 0 : (remMs + 999) / 1000;
    }

    /** Stamp a cooldown for the given key for secondsFromNow starting at nowMs. */
    public void set(UUID id, String key, long secondsFromNow, long nowMs) {
        if (id == null || key == null) return;
        untilByPlayer
                .computeIfAbsent(id, k -> new ConcurrentHashMap<>())
                .put(norm(key), nowMs + Math.max(0, secondsFromNow) * 1000L);
    }

    /** Convenience: stamp using the default seconds for the key. */
    public void stampDefault(UUID id, String key, long nowMs) {
        int secs = getDefaultSeconds(key);
        if (secs > 0) set(id, key, secs, nowMs);
    }

    /** Clears a specific cooldown key for a player. */
    public void clear(UUID id, String key) {
        if (id == null || key == null) return;
        Map<String, Long> m = untilByPlayer.get(id);
        if (m != null) m.remove(norm(key));
    }

    /** Optional: clear all cooldowns for a player (on logout, etc.) */
    public void clearAll(UUID id) {
        if (id == null) return;
        untilByPlayer.remove(id);
    }

    /** Reload default cooldowns from config. */
    public void updateFromConfig() {
        defaults.clear();

        MEConfig c = MEConfig.INSTANCE;
        if (c == null) return;

        putDefault("home", c.getCooldown("home"));
        putDefault("warp", c.getCooldown("warp"));
        putDefault("tp", c.getCooldown("tp"));
        putDefault("tpa", c.getCooldown("tpa"));
        putDefault("spawn", c.getCooldown("spawn"));
        putDefault("back", c.getCooldown("back"));
        putDefault("deathback", c.getCooldown("deathback"));
        putDefault("tphere", c.getCooldown("tphere"));
        putDefault("tpo", c.getCooldown("tpo"));
        putDefault("tpahere", c.getCooldown("tpahere"));
    }

    public int getDefaultSeconds(String key) {
        if (key == null) return 0;
        return Math.max(0, defaults.getOrDefault(norm(key), 0));
    }

    private void putDefault(String key, int seconds) {
        defaults.put(norm(key), Math.max(0, seconds));
    }

    private static String norm(String key) {
        return key.toLowerCase(Locale.ROOT);
    }
}
