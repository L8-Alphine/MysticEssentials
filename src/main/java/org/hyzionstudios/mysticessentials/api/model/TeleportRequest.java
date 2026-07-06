package org.hyzionstudios.mysticessentials.api.model;

import java.util.UUID;

/**
 * Describes a single teleport the {@code TeleportService} should perform,
 * including warmup, cooldown, cost, and cancellation rules. Built via
 * {@link #builder()}.
 *
 * <pre>{@code
 * TeleportRequest.builder()
 *     .type("custom_dungeon")
 *     .target(location)
 *     .warmupSeconds(5)
 *     .cooldownKey("dungeon_teleport")
 *     .cancelOnMove(true)
 *     .build();
 * }</pre>
 */
public final class TeleportRequest {

    private final String type;
    private final MysticLocation target;
    private final UUID targetPlayer;
    private final int warmupSeconds;
    private final int cooldownSeconds;
    private final String cooldownKey;
    private final double cost;
    private final boolean cancelOnMove;
    private final boolean cancelOnDamage;
    private final boolean recordBackLocation;

    private TeleportRequest(Builder b) {
        this.type = b.type;
        this.target = b.target;
        this.targetPlayer = b.targetPlayer;
        this.warmupSeconds = b.warmupSeconds;
        this.cooldownSeconds = b.cooldownSeconds;
        this.cooldownKey = b.cooldownKey;
        this.cost = b.cost;
        this.cancelOnMove = b.cancelOnMove;
        this.cancelOnDamage = b.cancelOnDamage;
        this.recordBackLocation = b.recordBackLocation;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getType() {
        return type;
    }

    public MysticLocation getTarget() {
        return target;
    }

    public UUID getTargetPlayer() {
        return targetPlayer;
    }

    public int getWarmupSeconds() {
        return warmupSeconds;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public String getCooldownKey() {
        return cooldownKey;
    }

    public double getCost() {
        return cost;
    }

    public boolean isCancelOnMove() {
        return cancelOnMove;
    }

    public boolean isCancelOnDamage() {
        return cancelOnDamage;
    }

    public boolean isRecordBackLocation() {
        return recordBackLocation;
    }

    /** Fluent builder for {@link TeleportRequest}. */
    public static final class Builder {
        private String type = "generic";
        private MysticLocation target;
        private UUID targetPlayer;
        private int warmupSeconds;
        private int cooldownSeconds;
        private String cooldownKey;
        private double cost;
        private boolean cancelOnMove = true;
        private boolean cancelOnDamage = true;
        private boolean recordBackLocation = true;

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        /** Fixed-coordinate destination. Mutually exclusive with {@link #targetPlayer(UUID)}. */
        public Builder target(MysticLocation target) {
            this.target = target;
            return this;
        }

        /** Destination resolved from another player's live position at execution time. */
        public Builder targetPlayer(UUID targetPlayer) {
            this.targetPlayer = targetPlayer;
            return this;
        }

        public Builder warmupSeconds(int warmupSeconds) {
            this.warmupSeconds = warmupSeconds;
            return this;
        }

        public Builder cooldownSeconds(int cooldownSeconds) {
            this.cooldownSeconds = cooldownSeconds;
            return this;
        }

        public Builder cooldownKey(String cooldownKey) {
            this.cooldownKey = cooldownKey;
            return this;
        }

        public Builder cost(double cost) {
            this.cost = cost;
            return this;
        }

        public Builder cancelOnMove(boolean cancelOnMove) {
            this.cancelOnMove = cancelOnMove;
            return this;
        }

        public Builder cancelOnDamage(boolean cancelOnDamage) {
            this.cancelOnDamage = cancelOnDamage;
            return this;
        }

        public Builder recordBackLocation(boolean recordBackLocation) {
            this.recordBackLocation = recordBackLocation;
            return this;
        }

        public TeleportRequest build() {
            if (target == null && targetPlayer == null) {
                throw new IllegalStateException("TeleportRequest requires either a target location or a target player");
            }
            return new TeleportRequest(this);
        }
    }
}
