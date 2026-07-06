package org.hyzionstudios.mysticessentials.api.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonObject;

/**
 * Persistent per-player record owned by the Player Profile Service.
 *
 * <p>Modules must not write unmanaged fields here. Instead they attach a private
 * blob under {@link #getModuleData()} keyed by their module id, keeping the core
 * schema stable. Mirrors the JSON layout in the design document.</p>
 */
public final class PlayerProfile {

    private String uuid;
    private String username;

    private String firstJoinDate;
    private String lastJoinDate;
    private String lastQuitDate;

    private long totalPlaytimeSeconds;
    private long activePlaytimeSeconds;
    private long idlePlaytimeSeconds;

    private MysticLocation lastKnownLocation;
    private MysticLocation lastTeleportedLocation;

    /** Per-module private storage; keyed by module id. */
    private Map<String, JsonObject> moduleData = new HashMap<>();

    /** Free-form metadata used by the Core (never gameplay data). */
    private Map<String, String> metadata = new HashMap<>();

    /** Transient (not persisted): true only for the session in which the profile was first created. */
    private transient boolean firstJoin;

    /** No-arg constructor for JSON deserialization. */
    public PlayerProfile() {
    }

    public static PlayerProfile create(UUID uuid, String username) {
        PlayerProfile profile = new PlayerProfile();
        profile.uuid = uuid.toString();
        profile.username = username;
        String now = Instant.now().toString();
        profile.firstJoinDate = now;
        profile.lastJoinDate = now;
        profile.firstJoin = true;
        return profile;
    }

    /** @return {@code true} if this is the player's first-ever join (this session only; not persisted). */
    public boolean isFirstJoin() {
        return firstJoin;
    }

    public UUID getUuid() {
        return UUID.fromString(uuid);
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getFirstJoinDate() {
        return firstJoinDate;
    }

    public String getLastJoinDate() {
        return lastJoinDate;
    }

    public void setLastJoinDate(String lastJoinDate) {
        this.lastJoinDate = lastJoinDate;
    }

    public String getLastQuitDate() {
        return lastQuitDate;
    }

    public void setLastQuitDate(String lastQuitDate) {
        this.lastQuitDate = lastQuitDate;
    }

    public long getTotalPlaytimeSeconds() {
        return totalPlaytimeSeconds;
    }

    public void setTotalPlaytimeSeconds(long value) {
        this.totalPlaytimeSeconds = value;
    }

    public long getActivePlaytimeSeconds() {
        return activePlaytimeSeconds;
    }

    public void setActivePlaytimeSeconds(long value) {
        this.activePlaytimeSeconds = value;
    }

    public long getIdlePlaytimeSeconds() {
        return idlePlaytimeSeconds;
    }

    public void setIdlePlaytimeSeconds(long value) {
        this.idlePlaytimeSeconds = value;
    }

    public MysticLocation getLastKnownLocation() {
        return lastKnownLocation;
    }

    public void setLastKnownLocation(MysticLocation location) {
        this.lastKnownLocation = location;
    }

    public MysticLocation getLastTeleportedLocation() {
        return lastTeleportedLocation;
    }

    public void setLastTeleportedLocation(MysticLocation location) {
        this.lastTeleportedLocation = location;
    }

    public Map<String, JsonObject> getModuleData() {
        if (moduleData == null) {
            moduleData = new HashMap<>();
        }
        return moduleData;
    }

    public Map<String, String> getMetadata() {
        if (metadata == null) {
            metadata = new HashMap<>();
        }
        return metadata;
    }
}
