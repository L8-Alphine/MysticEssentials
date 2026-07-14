package org.hyzionstudios.mysticessentials.modules.teleportation.rtp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hyzionstudios.mysticessentials.api.rtp.RtpProfile;
import org.hyzionstudios.mysticessentials.api.rtp.RtpSelectionMode;
import org.hyzionstudios.mysticessentials.api.rtp.RtpShape;

/**
 * Persisted Random Teleport settings for {@code modules/teleportation/rtp.json}.
 * Holds the global toggle, default-selection behaviour, the search-engine
 * budgets, warmup-cancellation rules, tag-driven safety lists, and the named
 * {@link RtpProfile} map.
 */
public final class RandomTeleportConfig {

    public int configVersion = 1;

    /** Master switch for the whole subsystem. */
    public boolean enabled = true;

    public Defaults randomTeleport = new Defaults();
    public SearchEngine searchEngine = new SearchEngine();
    public Warmup warmup = new Warmup();

    /**
     * Block-type tags whose blocks make a candidate's ground unsafe. Honoured
     * only where a block-tag lookup is available on the running server; the
     * built-in probe already rejects non-solid ground, so these are an optional
     * extension point.
     */
    public List<String> unsafeGroundTags = new ArrayList<>(List.of(
            "mysticessentials:rtp_unsafe_ground", "hytale:damaging_blocks"));
    public List<String> unsafeBodyTags = new ArrayList<>(List.of(
            "mysticessentials:rtp_unsafe_body"));

    /** Named profiles keyed by id. */
    public Map<String, RtpProfile> profiles = new LinkedHashMap<>();

    public static final class Defaults {
        public RtpSelectionMode defaultSelectionMode = RtpSelectionMode.DEFAULT_WORLD;
        public String defaultWorld = "default";
        public String defaultProfile = "default-wilderness";
        public boolean allowCurrentWorldFallback = true;
        /** When true, a bare {@code /rtp} opens the selection UI instead of teleporting immediately. */
        public boolean openUiOnRtp = false;
        /** world name &rarr; profile id (for {@code CURRENT_WORLD}/{@code PER_WORLD_PROFILE}). */
        public Map<String, String> worldProfiles = new LinkedHashMap<>();
        /** Worlds with RTP enabled. Empty means "any world that owns a profile". */
        public List<String> enabledWorlds = new ArrayList<>();
        /** Worlds explicitly disabled for RTP (wins over {@link #enabledWorlds}). */
        public List<String> disabledWorlds = new ArrayList<>();
    }

    public static final class SearchEngine {
        public int maximumConcurrentSearches = 4;
        public int maximumConcurrentSearchesPerWorld = 2;
        public int maximumQueueSize = 200;
        public int candidateChecksPerTick = 8;
        /** Milliseconds between search-engine ticks. */
        public long tickIntervalMillis = 100;
        public Cache cache = new Cache();

        public static final class Cache {
            public boolean enabled = true;
            public int targetLocationsPerProfile = 20;
            public int expirationMinutes = 30;
            public boolean revalidateBeforeTeleport = true;
        }
    }

    public static final class Warmup {
        public boolean cancelOnMovement = true;
        public double movementTolerance = 0.25;
        public boolean cancelOnDamage = true;
        public boolean cancelOnCombat = true;
        public boolean cancelOnWorldChange = true;
        public boolean cancelOnLogout = true;
    }

    /** Builds the first-run config with one worked-example wilderness profile. */
    public static RandomTeleportConfig withDefaults() {
        RandomTeleportConfig config = new RandomTeleportConfig();
        RtpProfile wilderness = new RtpProfile();
        wilderness.enabled = true;
        wilderness.displayName = "Wilderness";
        wilderness.description = "A random spot out in the wild.";
        wilderness.world = "default";
        wilderness.shape = RtpShape.CIRCLE;
        wilderness.minimumRadius = 1000;
        wilderness.maximumRadius = 15000;
        wilderness.warmupSeconds = 5;
        wilderness.cooldownSeconds = 900;
        config.profiles.put("default-wilderness", wilderness);
        return config;
    }
}
