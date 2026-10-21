package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyBatch;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.Opening;

/**
 * The document rules of stackable keys (P2-K): reserving, spending and
 * releasing units of a {@link KeyBatch}, and what recovery does with a batch
 * opening or deposit left unfinished. Engine-free, like {@link OpeningRules};
 * {@link OpeningService} and {@link ConversionService} apply each rule inside
 * the storage update of the batch it belongs to, which is what makes it atomic.
 *
 * <p><b>Spending one from a stack</b>: {@code reserve} holds one unit for the
 * opening and remembers how many items of the batch the player held; one item
 * is taken from the stack; {@code redeem} turns the hold into a spend
 * ({@code remaining - 1}), which is the commit point.</p>
 *
 * <p><b>Recovery prefers abort</b> (implementation plan §4.5). A serialized key
 * can be found in the inventory or not, so recovery commits when it left. Items
 * of a batch are interchangeable, so a smaller count may also mean the player
 * moved one away; recovery therefore always releases the hold and, when the
 * count dropped, gives one item back. A batch is a counter, so an extra item
 * never adds an opening: it only means that the last item of the batch to be
 * used finds nothing left.</p>
 */
public final class StackRules {

    /** What recovery does with a batch opening or deposit found unfinished. */
    public enum Recovery {
        /** Nothing was held: mark it aborted. */
        ABORT,
        /** Release the hold and abort; the items were not touched. */
        RELEASE,
        /** Release the hold, give back the items that may have been taken ({@link #regive}), abort. */
        RELEASE_REGIVE,
        /** It was spent: make sure the opening (or credit) says so. */
        COMMITTED,
        /** The inventory could not be read: try again on the next pass (deposits only). */
        WAIT,
        /** Nothing to do. */
        NONE
    }

    private StackRules() {
    }

    /** A fresh batch of {@code count} items. */
    public static KeyBatch newBatch(String batchId, String keyId, int defVersion, String owner, int count,
            String source, String actor, long now, long expiresAt) {
        KeyBatch batch = new KeyBatch();
        batch.batch = batchId;
        batch.keyId = keyId;
        batch.defVersion = defVersion;
        batch.owner = owner;
        batch.issued = count;
        batch.remaining = count;
        batch.source = source;
        batch.issuedBy = actor;
        batch.issuedAt = now;
        batch.expiresAt = expiresAt;
        return batch;
    }

    /**
     * {@code null} when {@code count} units may be spent now; otherwise why not,
     * in {@link OpeningRules#spendProblem}'s words. {@code "reserved"} means
     * units are left but another opening holds them, so the items are not dead.
     */
    public static String spendProblem(KeyBatch batch, String keyId, int count, long now) {
        if (batch == null) {
            return "unknown key";
        }
        if (!keyId.equals(batch.keyId)) {
            return "wrong key";
        }
        if (batch.revoked) {
            return "revoked key";
        }
        if (batch.expiresAt > 0 && now >= batch.expiresAt) {
            return "expired";
        }
        if (batch.remaining < count) {
            return "already used";
        }
        if (batch.available() < count) {
            return "reserved";
        }
        return null;
    }

    /**
     * Holds {@code count} units for {@code holder}, inside one update of the
     * batch, remembering {@code heldBefore} (the player's items of this batch).
     * Returns why it may not (and leaves the batch untouched), or {@code null}
     * once held. A second reserve by the same holder changes nothing.
     */
    public static String reserve(KeyBatch batch, String keyId, String holder, int count, int heldBefore, long now) {
        if (batch != null && batch.reservation(holder) != null) {
            return null;
        }
        String why = spendProblem(batch, keyId, count, now);
        if (why != null) {
            return why;
        }
        KeyBatch.Reservation reservation = new KeyBatch.Reservation();
        reservation.holder = holder;
        reservation.count = count;
        reservation.heldBefore = heldBefore;
        reservation.at = now;
        batch.reservations.add(reservation);
        return null;
    }

    /**
     * The commit point of a batch spend, inside one update of the batch: the
     * hold becomes a spend. Refuses (and changes nothing) unless {@code holder}
     * holds units, so a repeat never spends twice.
     *
     * @return the units spent, or 0 when refused
     */
    public static int redeem(KeyBatch batch, String holder) {
        KeyBatch.Reservation reservation = batch == null ? null : batch.reservation(holder);
        if (reservation == null || batch.revoked) {
            return 0;
        }
        batch.reservations.remove(reservation);
        batch.remaining = Math.max(0, batch.remaining - reservation.count);
        batch.spentBy.add(holder);
        while (batch.spentBy.size() > KeyBatch.MAX_SPENT) {
            batch.spentBy.remove(0);
        }
        return reservation.count;
    }

    /** Whether {@code holder} spent from this batch. */
    public static boolean spentBy(KeyBatch batch, String holder) {
        return batch != null && batch.spentBy.contains(holder);
    }

    /** Hands {@code holder}'s units back without spending them; {@code false} if it holds none. */
    public static boolean release(KeyBatch batch, String holder) {
        KeyBatch.Reservation reservation = batch == null ? null : batch.reservation(holder);
        return reservation != null && batch.reservations.remove(reservation);
    }

    /** Items to give back after releasing {@code reservation}: what the drop in the player's count may have taken. */
    public static int regive(KeyBatch.Reservation reservation, int heldNow) {
        return reservation == null ? 0 : Math.max(0, Math.min(reservation.count, reservation.heldBefore - heldNow));
    }

    /**
     * What to do with a batch opening found unfinished. {@code heldNow} is the
     * player's items of the batch, or {@code null} when the inventory could not
     * be read, which is treated as nothing taken (it only ever aborts).
     */
    public static Recovery decideOpening(String openingState, KeyBatch batch, String openingId, Integer heldNow) {
        if (Opening.ABORTED.equals(openingState) || Opening.DELIVERED.equals(openingState)) {
            return Recovery.NONE;
        }
        boolean beforeCommit = Opening.PREPARED.equals(openingState) || Opening.RESERVED.equals(openingState);
        if (batch == null) {
            return beforeCommit ? Recovery.ABORT : Recovery.COMMITTED;
        }
        if (spentBy(batch, openingId) || !beforeCommit) {
            return Recovery.COMMITTED;
        }
        KeyBatch.Reservation reservation = batch.reservation(openingId);
        if (reservation == null) {
            return Recovery.ABORT;
        }
        if (batch.revoked || heldNow == null || regive(reservation, heldNow) == 0) {
            return Recovery.RELEASE;
        }
        return Recovery.RELEASE_REGIVE;
    }

    /**
     * What to do with a deposit of batch items found {@code PREPARED}. Unlike an
     * opening it waits when the inventory cannot be read, because nothing is
     * owed to the player in the meantime.
     */
    public static Recovery decideDeposit(String conversionId, KeyBatch batch, Integer heldNow) {
        if (batch == null) {
            return Recovery.ABORT;
        }
        if (spentBy(batch, conversionId)) {
            return Recovery.COMMITTED;
        }
        KeyBatch.Reservation reservation = batch.reservation(conversionId);
        if (reservation == null) {
            return Recovery.ABORT;
        }
        if (batch.revoked) {
            return Recovery.RELEASE;
        }
        if (heldNow == null) {
            return Recovery.WAIT;
        }
        return regive(reservation, heldNow) == 0 ? Recovery.RELEASE : Recovery.RELEASE_REGIVE;
    }

    /**
     * Items of a withdrawn batch recovery still has to file: the batch's
     * remaining units that are neither in the inventory nor in the inbox. Any it
     * files beyond what really went missing only top the batch up to its counter,
     * which never adds an opening.
     *
     * @return the count to file, or {@code -1} when the inventory could not be read
     */
    public static int missingWithdrawn(KeyBatch batch, Integer held, int inInbox) {
        if (batch == null || batch.revoked) {
            return 0;
        }
        if (held == null) {
            return -1;
        }
        return Math.max(0, batch.remaining - held - Math.max(0, inInbox));
    }
}
