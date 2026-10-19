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
    }

    public static final class RequestRecord {
        public String requestId;
        public String openingId;
        public long at;
    }

    public static final class PoolState {
        public long lastOpenAt;
        public long opens;
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
