package org.hyzionstudios.mysticessentials.modules.lootbox.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The ledger entry of one batch of stackable key items ({@code lootbox_batch/<batch>},
 * P2-K). A key definition marked {@code stackable} issues one batch per grant
 * (or withdrawal): every item of it carries the same signed batch marker, so the
 * items stack like any ordinary item.
 *
 * <p><b>The counter is the authority, not the item.</b> {@link #remaining} is how
 * many openings (or deposits) the batch still allows, and every spend is one
 * atomic update of this document. The items are interchangeable: splitting a
 * stack, merging stacks of the same batch, trading some away or a duplication
 * exploit all leave the counter alone, and once it reaches zero every item still
 * carrying the batch is dead. That is the weaker guarantee: a duplicated
 * serialized key is dead on sight, but a duplicated stack item spends the batch
 * (so whoever spends last finds nothing left), and staff cannot tell which item
 * of a batch is the copy.</p>
 *
 * <p>Items of different batches never stack, even for the same key: their
 * markers differ. Nor do items of one batch issued under different definition
 * versions, because their name, lore and version differ.</p>
 */
public final class KeyBatch {

    /** Batch ids start with this; serials never do ({@code SecureDraw} ids are base32). */
    public static final String PREFIX = "batch-";
    /** Openings and conversions remembered in {@link #spentBy}. */
    public static final int MAX_SPENT = 200;

    public int schema = 1;
    /** {@code batch-<id>}. */
    public String batch;
    public String keyId;
    public int defVersion;
    /** UUID of the player it was issued to. Items may change hands; the batch does not care. */
    public String owner;
    /** Items minted into this batch, ever. */
    public int issued;
    /** Openings or deposits this batch still allows. */
    public int remaining;
    public boolean revoked;
    /** Openings and deposits between their reservation and their commit. */
    public List<Reservation> reservations = new ArrayList<>();
    /** The most recent openings and deposits that spent from it, oldest first; for recovery. */
    public List<String> spentBy = new ArrayList<>();
    public String source;
    public String issuedBy;
    public long issuedAt;
    /** 0 = never expires. */
    public long expiresAt;

    /** One opening's or deposit's hold on {@link #count} units. */
    public static final class Reservation {
        /** The opening or conversion holding it. */
        public String holder;
        public int count = 1;
        /** Items of this batch the player held when it was reserved; recovery compares against it. */
        public int heldBefore;
        public long at;
    }

    public static boolean isBatch(String id) {
        return id != null && id.startsWith(PREFIX);
    }

    public Reservation reservation(String holder) {
        for (Reservation reservation : reservations) {
            if (reservation.holder.equals(holder)) {
                return reservation;
            }
        }
        return null;
    }

    /** Units neither spent nor reserved. */
    public int available() {
        int reserved = 0;
        for (Reservation reservation : reservations) {
            reserved += reservation.count;
        }
        return remaining - reserved;
    }
}
