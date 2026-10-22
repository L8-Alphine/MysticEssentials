package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.util.Locale;
import java.util.UUID;

import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;

/**
 * The rules of network openings (P4-WP2), engine-free so the chaos checks
 * ({@code verifyLootBoxNetwork}) run them over in-memory fakes.
 *
 * <ul>
 *   <li><b>Scope.</b> A pool is {@code LOCAL} (default) or {@code NETWORK}.</li>
 *   <li><b>Fail closed.</b> A NETWORK opening needs the network switch, Redis
 *       for the lock, a store shared by every server (SQL) for atomic
 *       cross-server updates, and the same key-signing secret everywhere.
 *       Missing any of them refuses the opening before anything is spent.</li>
 *   <li><b>Fencing.</b> The lock is a Redis key with a TTL. Each acquisition
 *       takes the next value of one shared counter, the fencing token. Every
 *       write a lease holder makes to the player's documents carries it, and a
 *       document that already saw a higher token refuses the write
 *       ({@link FencedOut}). So a server whose lock expired mid-opening (a long
 *       pause, a partition) can't commit after another server took over.</li>
 * </ul>
 */
public final class NetworkRules {

    public static final String LOCAL = "LOCAL";
    public static final String NETWORK = "NETWORK";

    /** A write by a lease older than the document's newest one. Nothing was written. */
    public static final class FencedOut extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public FencedOut(long stored, long lease) {
            super("fenced out: the document saw token " + stored + ", this lease holds " + lease);
        }
    }

    private NetworkRules() {
    }

    /** {@code LOCAL} unless the pool says {@code NETWORK}. */
    public static String scope(LootBoxDraft spec) {
        return spec != null && spec.scope != null && NETWORK.equalsIgnoreCase(spec.scope.trim()) ? NETWORK : LOCAL;
    }

    public static boolean isNetwork(LootBoxDraft spec) {
        return NETWORK.equals(scope(spec));
    }

    /** Publish check of {@code scope}; {@code null} when fine. */
    public static String validateScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return null;
        }
        String value = scope.trim().toUpperCase(Locale.ROOT);
        return value.equals(LOCAL) || value.equals(NETWORK) ? null : "scope must be LOCAL or NETWORK";
    }

    /**
     * Why a NETWORK opening can't run now, or {@code null} when it can. Checked
     * before the lock and before anything is read for spending.
     */
    public static String unavailable(boolean networkEnabled, boolean redisUp, boolean sharedStore,
            boolean secretAgreed) {
        if (!networkEnabled) {
            return "network loot boxes are off on this server (lootbox network.enabled)";
        }
        if (!sharedStore) {
            return "network loot boxes need the shared SQL storage provider";
        }
        if (!redisUp) {
            return "the network lock (Redis) is unreachable";
        }
        if (!secretAgreed) {
            return "this server's key-signing secret differs from the network's";
        }
        return null;
    }

    /** Refuses a write by lease {@code lease} to a document that saw {@code stored}. 0 is "no lease". */
    public static void admit(long stored, long lease) {
        if (lease > 0 && lease < stored) {
            throw new FencedOut(stored, lease);
        }
    }

    /** The token a document records after a write by {@code lease}. */
    public static long after(long stored, long lease) {
        return Math.max(stored, lease);
    }

    /** The Redis lock key of one player's openings, namespaced by the network id. */
    public static String lockKey(String networkId, UUID player) {
        return (networkId == null || networkId.isBlank() ? "mystic" : networkId) + ":lootbox:lock:" + player;
    }

    /** Whether {@code error} (or what caused it) is a fenced-out write. */
    public static boolean fencedOut(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof FencedOut) {
                return true;
            }
        }
        return false;
    }
}
