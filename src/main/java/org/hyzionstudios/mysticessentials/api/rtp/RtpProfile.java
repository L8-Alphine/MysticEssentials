package org.hyzionstudios.mysticessentials.api.rtp;

import java.util.ArrayList;
import java.util.List;

/**
 * A named Random Teleport destination profile. Serialized from the
 * {@code profiles} block of the Teleportation module's {@code rtp.json}. Each
 * world may own several profiles (wilderness, resource, donor, event, ...).
 *
 * <p>Fields are public with sane defaults so Gson round-trips cleanly and
 * operators can hand-edit the file. The profile {@link #id} is transient and set
 * by the loader from the map key.</p>
 */
public final class RtpProfile {

    /** Map key this profile was loaded under; not serialized. */
    public transient String id;

    public boolean enabled = true;

    /** Display metadata for the selection UI. */
    public String displayName = "";
    public String icon = "";
    public String description = "";

    /** Destination world name. */
    public String world = "default";

    public RtpShape shape = RtpShape.CIRCLE;

    public Center center = new Center();

    /** Inner exclusion radius (blocks) — CIRCLE/SQUARE keep-out, RING inner edge. */
    public int minimumRadius = 1000;
    /** Outer radius/half-extent (blocks). */
    public int maximumRadius = 15000;
    /** Keep candidates at least this many blocks inside the outer edge. */
    public int borderPadding = 128;

    /** RECTANGLE half-extents; when 0 they fall back to {@link #maximumRadius}. */
    public int halfWidth = 0;
    public int halfDepth = 0;

    /** POLYGON vertices (world x/z). */
    public List<Vertex> polygon = new ArrayList<>();

    public int minimumY = 20;
    public int maximumY = 300;

    public int warmupSeconds = 5;
    public int cooldownSeconds = 900;
    public int searchTimeoutSeconds = 20;
    public int maximumSearchAttempts = 80;

    /** Search-priority weight; higher wins when {@link RtpSelectionMode#PERMISSION_PROFILE} resolves. */
    public int priority = 0;

    public Cost cost = new Cost();
    public Safety safety = new Safety();
    public ArrivalProtection arrivalProtection = new ArrivalProtection();
    public Filters filters = new Filters();
    public PlatformFallback platformFallback = new PlatformFallback();

    /** @return the effective display name (falls back to the id). */
    public String displayNameOrId() {
        return displayName == null || displayName.isBlank() ? id : displayName;
    }

    public static final class Center {
        public double x = 0;
        public double z = 0;
    }

    public static final class Vertex {
        public double x;
        public double z;
    }

    public static final class Cost {
        public boolean enabled = false;
        public double amount = 100.0;
        public String currency = "default";
    }

    public static final class Safety {
        public boolean requireSolidGround = true;
        public int requiredHeadroom = 2;
        public boolean allowLiquids = false;
        public boolean allowLeaves = false;
        public boolean avoidHazardousBlocks = true;
        public boolean avoidStructures = false;
        public boolean avoidClaims = true;
        public boolean avoidProtectedRegions = true;
    }

    public static final class ArrivalProtection {
        public int invulnerabilitySeconds = 5;
        public int preventFallDamageSeconds = 10;
        public boolean freezeUntilLoaded = true;
    }

    /**
     * Optional per-profile filters. Fields backed by not-yet-verified 0.5.6 APIs
     * (biomes, block tags, world types) or by other Mystic modules (claims via
     * MysticGuilds, level via MysticRPG) are honoured only when a corresponding
     * capability/exclusion provider is registered; otherwise they are ignored so
     * a search never silently fails.
     */
    public static final class Filters {
        public List<String> includedBiomes = new ArrayList<>();
        public List<String> excludedBiomes = new ArrayList<>();
        public List<String> includedRegions = new ArrayList<>();
        public List<String> excludedRegions = new ArrayList<>();
        public int minimumDistanceFromSpawn = 0;
        public int minimumDistanceFromPlayer = 0;
        public int minimumDistanceFromClaims = 0;
        public double maximumSlope = 0;
        public List<String> allowedDimensions = new ArrayList<>();
        public List<String> requiredBlockTags = new ArrayList<>();
        public List<String> excludedBlockTags = new ArrayList<>();
        public String permission = "";
        public int minimumLevel = 0;
        public List<String> allowedGuildRanks = new ArrayList<>();
        public List<String> allowedServerGroups = new ArrayList<>();
        public String timeOfDay = "";
        public int maxUsesPerHour = 0;
        public int maxUsesPerDay = 0;
    }

    public static final class PlatformFallback {
        public boolean enabled = false;
        public String material = "hytale:stone";
        public int width = 3;
        public int depth = 3;
        public int removeAfterSeconds = 0;
    }
}
