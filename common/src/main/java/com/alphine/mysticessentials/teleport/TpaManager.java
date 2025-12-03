package com.alphine.mysticessentials.teleport;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TpaManager {

    public enum Direction {
        TO_TARGET,  // /tpa  -> requester goes TO target
        HERE        // /tpahere -> target comes HERE to requester
    }

    public static final class Request {
        public final UUID from;       // requester
        public final UUID to;         // player who must /tpaccept or /tpdeny
        public final long expiresAt;  // epoch millis, or 0 = no expiry
        public final Direction direction;

        public Request(UUID from, UUID to, long expiresAt, Direction direction) {
            this.from = from;
            this.to = to;
            this.expiresAt = expiresAt;
            this.direction = direction;
        }
    }

    // Keyed by "to" (the player who must /tpaccept)
    private final Map<UUID, Request> byTarget = new ConcurrentHashMap<>();

    /**
     * Legacy /tpa request: requester wants to go TO the target.
     */
    public void request(UUID from, UUID to, int timeoutSeconds) {
        requestInternal(from, to, timeoutSeconds, Direction.TO_TARGET);
    }

    /**
     * /tpahere request: requester wants the target to come HERE.
     */
    public void requestHere(UUID from, UUID to, int timeoutSeconds) {
        requestInternal(from, to, timeoutSeconds, Direction.HERE);
    }

    private void requestInternal(UUID from, UUID to, int timeoutSeconds, Direction dir) {
        long exp = timeoutSeconds > 0
                ? System.currentTimeMillis() + timeoutSeconds * 1000L
                : 0L;
        byTarget.put(to, new Request(from, to, exp, dir));
    }

    /**
     * Consume (and remove) the pending request for the given target, if any and not expired.
     */
    public Optional<Request> consume(UUID target) {
        Request r = byTarget.remove(target);
        if (r == null) return Optional.empty();
        if (r.expiresAt > 0 && System.currentTimeMillis() > r.expiresAt) {
            return Optional.empty();
        }
        return Optional.of(r);
    }

    /**
     * Optional helper if you ever want to show "you have a pending request".
     */
    public boolean hasPending(UUID target) {
        Request r = byTarget.get(target);
        if (r == null) return false;
        if (r.expiresAt > 0 && System.currentTimeMillis() > r.expiresAt) {
            byTarget.remove(target);
            return false;
        }
        return true;
    }
}
