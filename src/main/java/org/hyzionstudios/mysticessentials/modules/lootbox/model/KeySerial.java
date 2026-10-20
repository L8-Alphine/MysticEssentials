package org.hyzionstudios.mysticessentials.modules.lootbox.model;

/**
 * The durable ledger entry of one physical key ({@code lootbox_serial/<serial>}).
 * Spending a physical key is the single write that moves it to {@code REDEEMED}
 * and records the outcome, so a copied item carrying the same serial is dead.
 */
public final class KeySerial {

    public static final String ISSUED = "ISSUED";
    public static final String RESERVED = "RESERVED";
    public static final String REDEEMED = "REDEEMED";
    public static final String REVOKED = "REVOKED";
    /**
     * Deposited into the owner's virtual balance (P2-F3): {@link #redeemedBy}
     * holds the conversion and {@link #usesLeft} the uses it credited. Any item
     * still carrying this serial is dead.
     */
    public static final String CONVERTED = "CONVERTED";

    public int schema = 1;
    public String serial;
    public String keyId;
    public int defVersion;
    /** UUID of the player it was issued to. */
    public String owner;
    public String state = ISSUED;
    public int usesLeft = 1;
    /** Opening (or deposit conversion) holding the reservation while the item is removed from the inventory. */
    public String reservedBy;
    public long reservedAt;
    /** Last opening that spent a use, and what it drew; for {@link #CONVERTED}, the conversion. */
    public String redeemedBy;
    public String redeemedOutcome;
    public String source;
    public String issuedBy;
    public long issuedAt;
    /** 0 = never expires. */
    public long expiresAt;
}
