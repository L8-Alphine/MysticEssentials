package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeySerial;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.Opening;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PlayerLootState;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsTable;

/**
 * The document rules of the opening saga (§4.3): request deduplication, the
 * confirm and snapshot checks, the virtual-key debit and the physical serial's
 * reserve, redeem and release. They only read and change documents, so they run
 * without the engine; {@link OpeningService} applies each one inside the storage
 * update it belongs to, which is what makes it atomic.
 */
public final class OpeningRules {

    /** How long a preview's confirmation stays valid. */
    public static final long CONFIRM_TTL_MS = TimeUnit.MINUTES.toMillis(10);

    /** What a preview handed out; the confirmation has to come back with its {@code requestId}. */
    public record Confirm(String lootboxId, String hash, String requestId, String crateId, long issuedAt) {
    }

    /** Outcome of {@link #spendVirtual}. */
    public enum Debit {
        /** One key spent and the outcome recorded, in the same write. */
        SPENT,
        /** This opening was committed before; nothing is charged again. */
        ALREADY_COMMITTED,
        /** No balance left; nothing changed except clearing the active opening. */
        NO_KEY
    }

    private OpeningRules() {
    }

    // ----- Before the draw ----------------------------------------------------

    /** The opening an earlier confirmation with {@code requestId} produced, or {@code null}. */
    public static String earlierOpening(PlayerLootState state, String requestId) {
        return requestId == null ? null : state.openingForRequest(requestId);
    }

    /** Whether a confirmation of {@code lootboxId} with {@code requestId} still answers the player's last preview. */
    public static boolean confirmCurrent(Confirm confirm, String lootboxId, String requestId, long now) {
        return confirm != null && requestId != null && requestId.equals(confirm.requestId())
                && lootboxId.equals(confirm.lootboxId()) && now - confirm.issuedAt() <= CONFIRM_TTL_MS;
    }

    /** Whether the odds the player confirmed are still the pool's: a republish in between changes the hash. */
    public static boolean snapshotCurrent(String snapshotHash, OddsTable table) {
        return snapshotHash != null && snapshotHash.equals(table.hash());
    }

    /** The first of the pool's keys the player has a virtual balance of, or {@code null}. */
    public static String firstVirtualKey(List<String> keyIds, PlayerLootState state) {
        for (String keyId : keyIds) {
            if (state.virtualKeys.getOrDefault(keyId, 0) > 0) {
                return keyId;
            }
        }
        return null;
    }

    /**
     * Marks the freshly drawn {@code opening} active and remembers its request id
     * for deduplication, dropping records older than the dedupe window.
     */
    public static void recordPrepared(PlayerLootState state, Opening opening, long dedupeMinutes) {
        state.activeOpening = opening.openingId;
        PlayerLootState.RequestRecord request = new PlayerLootState.RequestRecord();
        request.requestId = opening.requestId;
        request.openingId = opening.openingId;
        request.at = opening.createdAt;
        state.requests.add(request);
        long horizon = opening.createdAt - TimeUnit.MINUTES.toMillis(dedupeMinutes);
        state.requests.removeIf(r -> r.at < horizon);
        while (state.requests.size() > PlayerLootState.MAX_REQUESTS) {
            state.requests.remove(0);
        }
    }

    // ----- Virtual keys ---------------------------------------------------------

    /**
     * The virtual-key commit point, applied inside one update of the player
     * document: spends one {@code keyId} and records the outcome, so the debit and
     * the outcome can never disagree. The balance never drops below zero, and an
     * opening that already committed is never charged again.
     */
    public static Debit spendVirtual(PlayerLootState state, String keyId, Opening opening, String poolName,
            int historySize, long now) {
        if (state.hasCommit(opening.openingId)) {
            return Debit.ALREADY_COMMITTED;
        }
        int balance = state.virtualKeys.getOrDefault(keyId, 0);
        if (balance < 1) {
            if (opening.openingId.equals(state.activeOpening)) {
                state.activeOpening = null;
            }
            return Debit.NO_KEY;
        }
        if (balance == 1) {
            state.virtualKeys.remove(keyId);
        } else {
            state.virtualKeys.put(keyId, balance - 1);
        }
        recordCommit(state, opening, poolName, historySize, now);
        return Debit.SPENT;
    }

    /** Appends the commit record, receipt and bookkeeping of {@code opening} (the caller holds the update). */
    public static void recordCommit(PlayerLootState state, Opening opening, String poolName, int historySize,
            long now) {
        PlayerLootState.CommitRecord commit = new PlayerLootState.CommitRecord();
        commit.openingId = opening.openingId;
        commit.lootboxId = opening.lootboxId;
        commit.revision = opening.revision;
        commit.outcomeId = opening.outcomeId;
        commit.snapshotHash = opening.snapshotHash;
        commit.keyId = opening.keyId;
        commit.keyKind = opening.keyKind;
        commit.at = now;
        state.commits.add(commit);
        while (state.commits.size() > PlayerLootState.MAX_COMMITS) {
            state.commits.remove(0);
        }
        PlayerLootState.PoolState pool = state.pools.computeIfAbsent(opening.lootboxId,
                id -> new PlayerLootState.PoolState());
        pool.lastOpenAt = now;
        pool.opens++;
        state.lastOpenAt = now;
        if (opening.openingId.equals(state.activeOpening)) {
            state.activeOpening = null;
        }
        if (!state.openDeliveries.contains(opening.openingId)) {
            state.openDeliveries.add(opening.openingId);
        }
        PlayerLootState.Receipt receipt = new PlayerLootState.Receipt();
        receipt.openingId = opening.openingId;
        receipt.at = now;
        receipt.lootboxId = opening.lootboxId;
        receipt.poolName = poolName;
        receipt.revision = opening.revision;
        receipt.rewardId = opening.outcomeId;
        receipt.rewardName = opening.outcomeName;
        receipt.keyLabel = opening.keyId + (Opening.KEY_PHYSICAL.equals(opening.keyKind) ? " (item)" : " (virtual)");
        receipt.probability = opening.probability;
        receipt.oddsId = opening.snapshotHash.substring(0, Math.min(8, opening.snapshotHash.length()));
        receipt.status = "Delivering";
        state.receipts.add(0, receipt);
        int keep = Math.max(1, historySize);
        while (state.receipts.size() > keep) {
            state.receipts.remove(state.receipts.size() - 1);
        }
    }

    // ----- Physical keys --------------------------------------------------------

    /** {@code null} when the serial may be spent now; otherwise why not. */
    public static String spendProblem(KeySerial serial, String keyId, long now) {
        if (serial == null) {
            return "unknown key";
        }
        if (!keyId.equals(serial.keyId)) {
            return "wrong key";
        }
        if (!KeySerial.ISSUED.equals(serial.state)) {
            return KeySerial.REVOKED.equals(serial.state) ? "revoked key" : "already used";
        }
        if (serial.usesLeft < 1) {
            return "no uses left";
        }
        if (serial.expiresAt > 0 && now >= serial.expiresAt) {
            return "expired";
        }
        return null;
    }

    /**
     * {@code ISSUED → RESERVED(openingId)}, applied inside one update of the serial.
     * Returns why the serial may not be spent (and leaves it untouched), or
     * {@code null} once it is reserved.
     */
    public static String reserve(KeySerial serial, String keyId, String openingId, long now) {
        String why = spendProblem(serial, keyId, now);
        if (why != null) {
            return why;
        }
        serial.state = KeySerial.RESERVED;
        serial.reservedBy = openingId;
        serial.reservedAt = now;
        return null;
    }

    /** Whether this use is the serial's last, so the item itself leaves the inventory. */
    public static boolean lastUse(KeySerial serial) {
        return serial.usesLeft <= 1;
    }

    /**
     * The physical-key commit point, applied inside one update of the serial:
     * {@code RESERVED(openingId)} → one use spent and the outcome recorded. The
     * serial goes back to {@code ISSUED} while uses remain and is {@code REDEEMED}
     * at 0. Refuses (and changes nothing) unless this opening holds the reservation,
     * so a repeated commit never spends a second use.
     */
    public static boolean redeem(KeySerial serial, String openingId, String outcomeId) {
        if (serial == null || !KeySerial.RESERVED.equals(serial.state) || !openingId.equals(serial.reservedBy)) {
            return false;
        }
        serial.usesLeft = Math.max(0, serial.usesLeft - 1);
        serial.state = serial.usesLeft == 0 ? KeySerial.REDEEMED : KeySerial.ISSUED;
        serial.reservedBy = null;
        serial.redeemedBy = openingId;
        serial.redeemedOutcome = outcomeId;
        return true;
    }

    /** Hands {@code openingId}'s reservation back without spending a use; {@code false} if it holds none. */
    public static boolean release(KeySerial serial, String openingId) {
        if (serial == null || !KeySerial.RESERVED.equals(serial.state) || !openingId.equals(serial.reservedBy)) {
            return false;
        }
        serial.state = KeySerial.ISSUED;
        serial.reservedBy = null;
        return true;
    }
}
