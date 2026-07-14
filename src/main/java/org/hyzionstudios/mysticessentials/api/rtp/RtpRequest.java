package org.hyzionstudios.mysticessentials.api.rtp;

import java.util.UUID;

/**
 * A request to randomly teleport a player. Built via {@link #builder()}.
 *
 * <p>{@link #profileId} and {@link #world} are optional selectors; when both are
 * absent the service resolves a destination through the configured selection
 * mode. {@link #actorId} identifies an administrator issuing the request on
 * another player's behalf (for {@code /rtp <player>} and {@code /rtpadmin}).</p>
 */
public final class RtpRequest {

    private final UUID playerId;
    private final String profileId;
    private final String world;
    private final String biome;
    private final boolean force;
    private final boolean silent;
    private final boolean bypassCost;
    private final UUID actorId;

    private RtpRequest(Builder b) {
        this.playerId = b.playerId;
        this.profileId = b.profileId;
        this.world = b.world;
        this.biome = b.biome;
        this.force = b.force;
        this.silent = b.silent;
        this.bypassCost = b.bypassCost;
        this.actorId = b.actorId;
    }

    public static Builder builder(UUID playerId) {
        return new Builder(playerId);
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getProfileId() {
        return profileId;
    }

    public String getWorld() {
        return world;
    }

    public String getBiome() {
        return biome;
    }

    public boolean isForce() {
        return force;
    }

    public boolean isSilent() {
        return silent;
    }

    public boolean isBypassCost() {
        return bypassCost;
    }

    public UUID getActorId() {
        return actorId;
    }

    /** @return {@code true} when an administrator issued this on another player's behalf. */
    public boolean isAdminInitiated() {
        return actorId != null && !actorId.equals(playerId);
    }

    public static final class Builder {
        private final UUID playerId;
        private String profileId;
        private String world;
        private String biome;
        private boolean force;
        private boolean silent;
        private boolean bypassCost;
        private UUID actorId;

        private Builder(UUID playerId) {
            this.playerId = playerId;
        }

        public Builder profileId(String profileId) {
            this.profileId = profileId;
            return this;
        }

        public Builder world(String world) {
            this.world = world;
            return this;
        }

        public Builder biome(String biome) {
            this.biome = biome;
            return this;
        }

        public Builder force(boolean force) {
            this.force = force;
            return this;
        }

        public Builder silent(boolean silent) {
            this.silent = silent;
            return this;
        }

        public Builder bypassCost(boolean bypassCost) {
            this.bypassCost = bypassCost;
            return this;
        }

        public Builder actor(UUID actorId) {
            this.actorId = actorId;
            return this;
        }

        public RtpRequest build() {
            return new RtpRequest(this);
        }
    }
}
