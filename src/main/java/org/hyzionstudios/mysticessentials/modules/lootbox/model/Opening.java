package org.hyzionstudios.mysticessentials.modules.lootbox.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One opening ({@code lootbox_open/<openingId>}): the saga's projection, used for
 * recovery, receipts and review. The authoritative commit lives with the key
 * debit (the serial or the player document); this document follows it.
 */
public final class Opening {

    public static final String PREPARED = "PREPARED";
    public static final String RESERVED = "RESERVED";
    public static final String COMMITTED = "COMMITTED";
    public static final String DELIVERING = "DELIVERING";
    public static final String DELIVERED = "DELIVERED";
    public static final String ABORTED = "ABORTED";
    public static final String REVIEW = "REVIEW";

    public static final String KEY_VIRTUAL = "VIRTUAL";
    public static final String KEY_PHYSICAL = "PHYSICAL";

    public int schema = 1;
    public String openingId;
    public String player;
    public String playerName;
    public String requestId;
    public String lootboxId;
    public String poolName;
    public int revision;
    public String snapshotHash;
    /** The draw: {@code roll} uniform in {@code [0, totalWeight)}. */
    public long roll;
    public long totalWeight;
    public String outcomeId;
    public String outcomeName;
    /** The exact chance of the outcome, formatted when it was drawn. */
    public String probability;
    public String keyKind;
    public String keyId;
    public String keySerial;
    public String crateId;
    public String state = PREPARED;
    public long createdAt;
    public long committedAt;
    public String note;
    /** The highest network fencing token that wrote this opening (P4-WP2); 0 for local openings. */
    public long fence;
    public List<Fulfillment> fulfillments = new ArrayList<>();
    /** The bonus draws (P3-WP2), one per bonus roll of the revision; empty when it has none. */
    public List<BonusDraw> bonus = new ArrayList<>();

    public boolean isTerminal() {
        return DELIVERED.equals(state) || ABORTED.equals(state);
    }

    /**
     * One bonus roll's draw: {@code roll} uniform in {@code [0, total)} over its
     * rewards and its "no bonus" weight. {@code outcomeId} is {@code null} when the
     * roll landed on "no bonus".
     */
    public static final class BonusDraw {
        public String rollId;
        public String label;
        public long roll;
        public long total;
        public String outcomeId;
        public String outcomeName;
        /** The exact chance of what it landed on, formatted when it was drawn. */
        public String probability;
    }

    /** One delivery action, idempotency id {@code openingId#index}. */
    public static final class Fulfillment {

        public static final String PLANNED = "PLANNED";
        public static final String ATTEMPTED = "ATTEMPTED";
        public static final String DELIVERED = "DELIVERED";
        public static final String PENDING_INBOX = "PENDING_INBOX";
        public static final String REVIEW = "REVIEW";
        public static final String FAILED = "FAILED";
        public static final String DISMISSED = "DISMISSED";

        public String id;
        public String type;
        /**
         * The reward this action belongs to (P3-WP2) and its index among that
         * reward's actions (a bundle has several). {@code null} on openings recorded
         * before Phase 3: the main outcome's only action.
         */
        public String rewardId;
        public int action;
        /** The bonus roll that drew {@link #rewardId}; {@code null} for the main outcome. */
        public String bonusRoll;
        public String state = PLANNED;
        public String detail;
        public int attempts;
        public long updatedAt;

        public boolean isSettled() {
            return DELIVERED.equals(state) || PENDING_INBOX.equals(state) || DISMISSED.equals(state);
        }
    }
}
