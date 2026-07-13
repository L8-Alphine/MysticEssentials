package org.hyzionstudios.mysticessentials.modules.playervaults.api.results;

import org.hyzionstudios.mysticessentials.modules.playervaults.model.VaultLock;

/**
 * Outcome of a lock-acquisition attempt. On {@link Status#ACQUIRED} the
 * {@link #token} is the caller's proof of ownership for renew/release; on
 * {@link Status#LOCKED_ELSEWHERE} the {@link #holder} describes who holds it (for
 * a clean player-facing message).
 */
public record VaultLockResult(Status status, String token, VaultLock holder) {

    public enum Status {
        /** Lock taken (or not required because cross-server locking is off). */
        ACQUIRED,
        /** Held by another server; a normal player may not open. */
        LOCKED_ELSEWHERE,
        /** Already open in a local session on this server. */
        LOCKED_LOCALLY,
        /** Cross-server mode requires Redis but it is unavailable. */
        REDIS_UNAVAILABLE
    }

    public static VaultLockResult acquired(String token) {
        return new VaultLockResult(Status.ACQUIRED, token, null);
    }

    public static VaultLockResult lockedElsewhere(VaultLock holder) {
        return new VaultLockResult(Status.LOCKED_ELSEWHERE, null, holder);
    }

    public static VaultLockResult lockedLocally() {
        return new VaultLockResult(Status.LOCKED_LOCALLY, null, null);
    }

    public static VaultLockResult redisUnavailable() {
        return new VaultLockResult(Status.REDIS_UNAVAILABLE, null, null);
    }

    public boolean acquired() {
        return status == Status.ACQUIRED;
    }
}
