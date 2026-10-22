package org.hyzionstudios.mysticessentials.modules.lootbox.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything LootBox keeps per player ({@code lootbox_player/<uuid>}). Spending a
 * virtual key is the single write that lowers the balance and appends the
 * {@link CommitRecord}, so the debit and the outcome can never disagree.
 */
public final class PlayerLootState {

    /** Bounds on the lists kept in one document. */
    public static final int MAX_COMMITS = 200;
    public static final int MAX_REQUESTS = 200;
    public static final int MAX_CONVERSIONS = 100;

    public int schema = 1;
    /** keyId → balance. */
    public Map<String, Integer> virtualKeys = new LinkedHashMap<>();
    /** Newest last. Holds virtual-key commits and the follow-up record of physical-key commits. */
    public List<CommitRecord> commits = new ArrayList<>();
    /** Confirm request ids already handled, for duplicate suppression. */
    public List<RequestRecord> requests = new ArrayList<>();
    /** lootboxId → per-pool bookkeeping. */
    public Map<String, PoolState> pools = new LinkedHashMap<>();
    /** Newest first; capped by {@code opening.historySize}. */
    public List<Receipt> receipts = new ArrayList<>();
    /** The opening between draw and commit, if any. Cleared at commit or abort. */
    public String activeOpening;
    /** Committed openings whose delivery has not settled; recovery resumes them on join. */
    public List<String> openDeliveries = new ArrayList<>();
    public long lastOpenAt;
    /**
     * The highest network fencing token (P4-WP2) that wrote this document. A
     * server whose lease is older is refused, so a lock that went stale can't
     * spend twice. 0 when no network opening ever touched it.
     */
    public long fence;
    /**
     * Physical ↔ virtual key conversions (P2-F3), newest last. A finished one is
     * dropped beyond {@link #MAX_CONVERSIONS}; an unfinished one never is, because
     * recovery finishes it from this record.
     */
    public List<Conversion> conversions = new ArrayList<>();
    /**
     * The player's reduced-motion choice (P2-L): {@code true} reveals results
     * without animation, {@code false} always animates, {@code null} follows
     * {@code opening.reducedMotionDefault}. See {@code MotionRules}.
     */
    public Boolean reducedMotion;

    /** The conversion with {@code conversionId}, or {@code null}. */
    public Conversion conversion(String conversionId) {
        for (Conversion conversion : conversions) {
            if (conversion.conversionId.equals(conversionId)) {
                return conversion;
            }
        }
        return null;
    }

    public boolean hasCommit(String openingId) {
        for (CommitRecord commit : commits) {
            if (openingId.equals(commit.openingId)) {
                return true;
            }
        }
        return false;
    }

    public String openingForRequest(String requestId) {
        for (RequestRecord request : requests) {
            if (requestId.equals(request.requestId)) {
                return request.openingId;
            }
        }
        return null;
    }

    public static final class CommitRecord {
        public String openingId;
        public String lootboxId;
        public int revision;
        public String outcomeId;
        public String snapshotHash;
        public String keyId;
        /** {@code VIRTUAL} or {@code PHYSICAL}. */
        public String keyKind;
        public long at;
        /** What the bonus rolls awarded (P3-WP2), as {@code <rollId>/<rewardId>}. */
        public List<String> bonusOutcomes = new ArrayList<>();
    }

    /**
     * One conversion. A deposit is recorded {@code PREPARED} before its serial is
     * reserved and becomes {@code CREDITED} in the update that credits the
     * balance. A withdrawal is recorded {@code DEBITED} in the update that debits
     * the balance (with the serials it will issue) and becomes {@code DELIVERED}
     * once every key is in the inventory or the pending inbox.
     */
    public static final class Conversion {
        public static final String DEPOSIT = "DEPOSIT";
        public static final String WITHDRAW = "WITHDRAW";

        public static final String PREPARED = "PREPARED";
        public static final String CREDITED = "CREDITED";
        public static final String DEBITED = "DEBITED";
        public static final String DELIVERED = "DELIVERED";
        public static final String ABORTED = "ABORTED";

        public String conversionId;
        /** {@link #DEPOSIT} or {@link #WITHDRAW}. */
        public String kind;
        public String keyId;
        /** Deposit: uses credited (set when credited). Withdrawal: keys debited. */
        public int count;
        /** Deposit: the serial being converted. */
        public String serial;
        /** Withdrawal: the serials it issues, chosen before the debit so a retry issues the same ones. */
        public List<String> serials = new ArrayList<>();
        /** The request that started it, for duplicate suppression; may be {@code null}. */
        public String requestId;
        public String state;
        public String note;
        public long at;

        /** Whether recovery still has work to do on it. */
        public boolean unfinished() {
            return PREPARED.equals(state) || DEBITED.equals(state);
        }
    }

    public static final class RequestRecord {
        public String requestId;
        public String openingId;
        public long at;
    }

    public static final class PoolState {
        public long lastOpenAt;
        public long opens;
        /**
         * Openings since the player last won one of the pool's pity targets
         * (P3-WP4). Counted only while the pool has pity; changed in the same write
         * as the commit, so the counter and the outcome never disagree.
         */
        public int sincePity;
        /**
         * Unique rewards the player won from this pool (P3-WP4, duplicate
         * protection): reward ids, and {@code <bonusRollId>/<rewardId>} for a bonus
         * roll's. Never trimmed: dropping one would let a duplicate through.
         */
        public List<String> owned = new ArrayList<>();
    }

    /** What /lootbox history shows; readable even after the pool changes. */
    public static final class Receipt {
        public String openingId;
        public long at;
        public String lootboxId;
        public String poolName;
        public int revision;
        public String rewardId;
        public String rewardName;
        public String keyLabel;
        /** The exact chance shown at commit, already formatted. */
        public String probability;
        /** First 8 hex digits of the odds snapshot hash. */
        public String oddsId;
        /** Delivery status at the time the receipt was last updated. */
        public String status;
    }
}
