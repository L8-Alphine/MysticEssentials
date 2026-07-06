package org.hyzionstudios.mysticessentials.core.scheduler;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-player, per-key cooldown expiry timestamps. Shared by the
 * Teleport service, Mail, and any module needing rate limiting. Purely
 * in-memory; transient cooldowns intentionally reset on restart.
 */
public final class CooldownService {

    private final Map<String, Long> expiries = new ConcurrentHashMap<>();

    private static String key(UUID player, String cooldownKey) {
        return player + ":" + cooldownKey;
    }

    /** Starts a cooldown of {@code seconds} for the key, overwriting any existing one. */
    public void set(UUID player, String cooldownKey, long seconds) {
        expiries.put(key(player, cooldownKey), System.currentTimeMillis() + seconds * 1000L);
    }

    /** @return {@code true} if the cooldown is currently active. */
    public boolean isActive(UUID player, String cooldownKey) {
        return remaining(player, cooldownKey) > 0;
    }

    /** @return remaining whole seconds, or {@code 0} if ready. */
    public long remaining(UUID player, String cooldownKey) {
        Long expiry = expiries.get(key(player, cooldownKey));
        if (expiry == null) {
            return 0;
        }
        long remainingMillis = expiry - System.currentTimeMillis();
        if (remainingMillis <= 0) {
            expiries.remove(key(player, cooldownKey));
            return 0;
        }
        return (remainingMillis + 999) / 1000;
    }

    public void clear(UUID player, String cooldownKey) {
        expiries.remove(key(player, cooldownKey));
    }

    public void clearAll(UUID player) {
        expiries.keySet().removeIf(k -> k.startsWith(player + ":"));
    }
}
