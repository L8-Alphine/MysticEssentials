package org.hyzionstudios.mysticessentials.modules.lootbox.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A reward type delivered by another plugin (P3-WP1): cosmetics, RPG experience,
 * a typed currency deposit, anything LootBox does not deliver itself. A reward
 * names its provider by {@link #id()} ({@code "provider": "mysticeconomy"}), and
 * its {@code params} reach the provider unchanged.
 *
 * <p>Register it with {@link LootBoxApi#registerRewardProvider}. A pool whose
 * provider is missing is invalid: new openings are refused, receipts and
 * pending deliveries keep working, and nothing is ever substituted silently
 * (Design Bible §15, LB-013). A delivery that finds its provider gone goes to
 * staff review.</p>
 *
 * <p>Calls come from storage and lane threads, never the world thread; a
 * provider that needs one hops there itself.</p>
 */
public interface RewardProvider {

    /** What a provider promises when a delivery is repeated after a crash. */
    enum Guarantee {
        /** Granting the same {@code fulfillmentId} twice has the effect of granting it once. */
        IDEMPOTENT,
        /** The provider can say whether a {@code fulfillmentId} was applied ({@link #reconcile}). */
        RECONCILABLE,
        /** Neither: an interrupted grant goes to staff review and is never repeated automatically. */
        AT_MOST_ONCE
    }

    /** How one grant (or reconcile) ended. */
    enum Outcome {
        /** Applied now. */
        DELIVERED,
        /** Applied earlier under the same fulfillment id; nothing was done again. */
        ALREADY_DELIVERED,
        /** Certainly not applied (refused, account missing, …); safe to look at again. */
        FAILED,
        /** May or may not have been applied; staff must decide. */
        UNKNOWN
    }

    record Result(Outcome outcome, String detail) {

        public static Result delivered(String detail) {
            return new Result(Outcome.DELIVERED, detail);
        }

        public static Result failed(String detail) {
            return new Result(Outcome.FAILED, detail);
        }

        public static Result unknown(String detail) {
            return new Result(Outcome.UNKNOWN, detail);
        }
    }

    /** Lower-case id rewards name in {@code provider}, e.g. {@code mysticeconomy}; {@code [a-z0-9_.:-]+}. */
    String id();

    /**
     * The reward type it serves: {@code EXTENSION}, {@code COSMETIC}, {@code RPG}
     * or {@code ECONOMY}. A reward's type must match its provider's.
     */
    String kind();

    Guarantee guarantee();

    /** Publish-time check of a reward's parameters; empty means valid. */
    List<String> validate(Map<String, String> params);

    /** One line for previews and receipts, e.g. {@code Cosmetic: Royal Cape}. Plain text. */
    String describe(Map<String, String> params);

    /**
     * Delivers one action. {@code fulfillmentId} ({@code openingId#index}) is
     * stable across retries: an {@link Guarantee#IDEMPOTENT} provider keys its
     * effect on it.
     */
    CompletableFuture<Result> grant(String fulfillmentId, UUID player, Map<String, String> params);

    /**
     * Whether an interrupted {@code fulfillmentId} was applied. Only asked of
     * {@link Guarantee#RECONCILABLE} providers: {@code ALREADY_DELIVERED} settles
     * it, {@code FAILED} runs the grant again, {@code UNKNOWN} sends it to review.
     */
    default CompletableFuture<Result> reconcile(String fulfillmentId, UUID player, Map<String, String> params) {
        return CompletableFuture.completedFuture(Result.unknown("this provider cannot reconcile"));
    }

    /**
     * Whether the provider can tell that a player already owns a reward, for
     * duplicate protection ({@code "duplicates": "EXCLUDE_OWNED"}).
     */
    default boolean supportsOwnership() {
        return false;
    }

    /** Whether {@code player} already owns this reward. Must answer quickly from a cache. */
    default boolean owns(UUID player, Map<String, String> params) {
        return false;
    }
}
