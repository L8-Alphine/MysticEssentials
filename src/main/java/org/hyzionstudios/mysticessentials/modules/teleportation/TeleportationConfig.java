package org.hyzionstudios.mysticessentials.modules.teleportation;

import java.util.ArrayList;
import java.util.List;

/** Persisted teleportation settings for {@code modules/teleportation/config.json}. */
public final class TeleportationConfig {

    /** Seconds before a pending /tpa request expires. */
    public int requestExpirySeconds = 60;

    /** Warmup applied to accepted /tpa and /tpahere moves. */
    public int tpaWarmupSeconds = 3;

    /** Cooldown between /tpa uses. */
    public int tpaCooldownSeconds = 5;

    /** Warmup applied to /back (0 = instant). */
    public int backWarmupSeconds = 0;

    /** Cooldown between /back uses. */
    public int backCooldownSeconds = 5;

    /**
     * Destination-world whitelist. Empty means every world is allowed unless it
     * appears in {@link #worldBlacklist}.
     */
    public List<String> worldWhitelist = new ArrayList<>();

    /** Destination worlds blocked for every teleport routed through the module. */
    public List<String> worldBlacklist = new ArrayList<>();
}
