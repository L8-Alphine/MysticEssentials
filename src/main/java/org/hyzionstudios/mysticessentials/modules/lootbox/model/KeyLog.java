package org.hyzionstudios.mysticessentials.modules.lootbox.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The recent grants, refusals, revocations and conversions of one key
 * ({@code lootbox_keylog/<keyId>}), newest last and bounded by
 * {@code keys.grantLogSize}. It backs the Grants page's history. The audit
 * trail stays the record of authority; this is a per-key index into it, so
 * the page never scans every hourly audit bucket.
 */
public final class KeyLog {

    public static final String GRANT = "GRANT";
    /** A grant the policy profile refused (for example a PAID source). */
    public static final String REFUSED = "REFUSED";
    public static final String REVOKE = "REVOKE";
    /** A held physical key turned into virtual balance. */
    public static final String DEPOSIT = "DEPOSIT";
    /** Virtual balance turned into a physical key. */
    public static final String WITHDRAW = "WITHDRAW";

    public int schema = 1;
    public List<Entry> entries = new ArrayList<>();

    public static final class Entry {
        public long at;
        public String action;
        /** Staff name, {@code console}, {@code api}, or the player converting. */
        public String actor;
        /** UUID of the player the keys went to (or the serial belonged to). */
        public String owner;
        /** Their name when it was known at the time; may be {@code null}. */
        public String ownerName;
        public int count;
        public boolean virtual;
        public String source;
        /** Physical keys that went to the pending inbox instead of the inventory. */
        public int pending;
        /** The serial revoked or converted, when there is one. */
        public String serial;
        /** Why a grant was refused, or other free text. */
        public String detail;
    }

    /** Appends {@code entry}, dropping the oldest entries beyond {@code max}. */
    public void add(Entry entry, int max) {
        entries.add(entry);
        int keep = Math.max(1, max);
        while (entries.size() > keep) {
            entries.remove(0);
        }
    }

    /** At most {@code limit} entries, newest first. */
    public List<Entry> newest(int limit) {
        List<Entry> newest = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0 && newest.size() < limit; i--) {
            newest.add(entries.get(i));
        }
        return newest;
    }
}
