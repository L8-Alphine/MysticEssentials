package com.alphine.mysticessentials.teleport;

import com.alphine.mysticessentials.config.MEConfig;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player cooldowns keyed by a logical action string (home/warp/tp/tpa/spawn...).
 * Supports config-driven defaults and reload via updateFromConfig().
 */
public class CooldownManager {
    private final Map<UUID, Map<String, Long>> map = new HashMap<>();
    private int defHome = 0, defWarp = 0, defTp = 0, defTpa = 0, defSpawn = 0;

    public CooldownManager() { updateFromConfig(); } // ensure defaults once

    public boolean checkAndStamp(UUID id, String key, long nowMs, long seconds) {
        var m = map.computeIfAbsent(id, k -> new HashMap<>());
        long until = m.getOrDefault(key, 0L);
        if (nowMs < until) return false;
        m.put(key, nowMs + Math.max(0, seconds) * 1000L);
        return true;
    }

    public boolean checkAndStampDefault(UUID id, String key, long nowMs) {
        return checkAndStamp(id, key, nowMs, getDefaultSeconds(key));
    }

    public long remaining(UUID id, String key, long nowMs) {
        var m = map.get(id);
        if (m == null) return 0;
        long remMs = m.getOrDefault(key, 0L) - nowMs;
        return remMs <= 0 ? 0 : (remMs + 999) / 1000;
    }

    public void set(UUID id, String key, long secondsFromNow, long nowMs) {
        map.computeIfAbsent(id, k -> new HashMap<>())
                .put(key, nowMs + Math.max(0, secondsFromNow) * 1000L);
    }
    public void clear(UUID id, String key) {
        var m = map.get(id);
        if (m != null) m.remove(key);
    }

    public void updateFromConfig() {
        var c = MEConfig.INSTANCE;
        if (c == null) { defHome=defWarp=defTp=defTpa=defSpawn=0; return; }
        this.defHome  = Math.max(0, c.getCooldown("home"));
        this.defWarp  = Math.max(0, c.getCooldown("warp"));
        this.defTp    = Math.max(0, c.getCooldown("tp"));
        this.defTpa   = Math.max(0, c.getCooldown("tpa"));
        this.defSpawn = Math.max(0, c.getCooldown("spawn"));
    }

    public int getDefaultSeconds(String key) {
        if (key == null) return 0;
        switch (key.toLowerCase(Locale.ROOT)) {
            case "home":  return defHome;
            case "warp":  return defWarp;
            case "tp":    return defTp;
            case "tpa":   return defTpa;
            case "spawn": return defSpawn;
            default:      return 0;
        }
    }
}
