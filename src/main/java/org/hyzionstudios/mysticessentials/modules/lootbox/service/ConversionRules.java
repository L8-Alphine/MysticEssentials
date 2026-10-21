package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyBatch;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeySerial;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PlayerLootState;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PlayerLootState.Conversion;

/**
 * The document rules of the physical ↔ virtual key conversion (P2-F3), a small
 * saga shaped like the opening's (§4.3). They only read and change documents,
 * so they run without the engine; {@link ConversionService} applies each one
 * inside the storage update it belongs to, which is what makes it atomic.
 *
 * <p><b>Deposit</b> (a held key becomes virtual balance): the player document
 * records the conversion {@code PREPARED}; the serial goes
 * {@code ISSUED → RESERVED(conversionId)}; the item is removed; the serial goes
 * {@code RESERVED → CONVERTED}, which is the commit point (a revoke in between
 * wins); then ONE player-document update keyed by the conversion id credits the
 * serial's remaining uses and marks it {@code CREDITED}, so repeating it never
 * credits twice.</p>
 *
 * <p><b>Withdrawal</b> (virtual balance becomes key items): ONE player-document
 * update checks and debits the balance and records the conversion
 * {@code DEBITED} with the serials it will issue, which is the commit point.
 * Then the serials are written (only if absent), the items given, what did not
 * fit put in the pending inbox (keyed by serial, so exactly once), and the
 * conversion marked {@code DELIVERED}.</p>
 *
 * <p>Recovery ({@link #decideDeposit}, {@link #decideWithdrawn}) follows
 * {@link RecoveryPolicy}: a key still in the inventory is kept, a key that left
 * is credited, a converted serial is credited once, and a withdrawn key that is
 * neither in the inventory, nor in the inbox, nor spent goes to the inbox. When
 * the inventory cannot be read it waits rather than guess.</p>
 */
public final class ConversionRules {

    /**
     * How long a conversion waits for its world-thread hop before leaving the
     * rest to recovery. The platform holds a player's entity for up to 30 s, and
     * a dropped world task would otherwise hold the player's lane forever.
     */
    public static final long HOP_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(35);

    /** Source recorded on serials a withdrawal issues. */
    public static final String SOURCE = "CONVERSION";

    /** What recovery does with an unfinished deposit. */
    public enum DepositAction {
        /** The reservation was never written: nothing happened. */
        ABORT,
        /** Reserved and the key is still in the inventory: release it, the player keeps the key. */
        RELEASE_ABORT,
        /** Reserved and the key left the inventory: convert the serial and credit it. */
        CONVERT_CREDIT,
        /** Already converted: credit it (once). */
        CREDIT,
        /** Already converted and the dead key came back: remove it and credit (once). */
        CREDIT_CLEAN_UP,
        /** The serial belongs to something else now (spent, revoked, another reservation): abort without touching it. */
        ABORT_FOREIGN,
        /** The answer depends on the inventory, which could not be read: try again on the next pass. */
        WAIT
    }

    /** What recovery does with one serial of an unfinished withdrawal. */
    public enum Delivery {
        /** In the inventory, in the inbox, or already spent: it was delivered. */
        SKIP,
        /** Nowhere: put it in the pending inbox. */
        TO_INBOX,
        /** The inventory could not be read: try again on the next pass. */
        WAIT
    }

    /** Outcome of {@link #debit}. */
    public enum Debit {
        /** Balance debited and the conversion recorded, in the same write. */
        DEBITED,
        /** This conversion was recorded before; nothing is debited again. */
        ALREADY,
        /** Not enough balance; nothing changed. */
        NO_BALANCE
    }

    private ConversionRules() {
    }

    // ----- Requests -------------------------------------------------------------

    /** The conversion an earlier request with {@code requestId} started, or {@code null}. */
    public static Conversion earlier(PlayerLootState state, String requestId) {
        if (requestId == null) {
            return null;
        }
        for (Conversion conversion : state.conversions) {
            if (requestId.equals(conversion.requestId)) {
                return conversion;
            }
        }
        return null;
    }

    /** Whether an opening or a conversion is unfinished, so a new conversion must wait for recovery. */
    public static boolean busy(PlayerLootState state) {
        return state.activeOpening != null || !unfinished(state).isEmpty();
    }

    /** The conversions recovery still has to finish, oldest first. */
    public static List<Conversion> unfinished(PlayerLootState state) {
        List<Conversion> open = new ArrayList<>();
        for (Conversion conversion : state.conversions) {
            if (conversion.unfinished()) {
                open.add(conversion);
            }
        }
        return open;
    }

    // ----- Deposit ----------------------------------------------------------------

    /** Records a deposit of {@code serial} as {@code PREPARED}, before its serial is reserved. */
    public static Conversion prepareDeposit(PlayerLootState state, String conversionId, String requestId,
            String keyId, String serial, long now) {
        Conversion conversion = new Conversion();
        conversion.conversionId = conversionId;
        conversion.kind = Conversion.DEPOSIT;
        conversion.keyId = keyId;
        conversion.serial = serial;
        conversion.requestId = requestId;
        conversion.state = Conversion.PREPARED;
        conversion.at = now;
        state.conversions.add(conversion);
        trim(state);
        return conversion;
    }

    /**
     * {@code ISSUED → RESERVED(conversionId)}, applied inside one update of the
     * serial. Returns why the serial may not be deposited (and leaves it
     * untouched), or {@code null} once it is reserved. A key that expires is
     * refused, because virtual balance never expires.
     */
    public static String reserve(KeySerial serial, String keyId, String conversionId, long now) {
        String why = OpeningRules.spendProblem(serial, keyId, now);
        if (why != null) {
            return why;
        }
        if (serial.expiresAt > 0) {
            return "expiring";
        }
        serial.state = KeySerial.RESERVED;
        serial.reservedBy = conversionId;
        serial.reservedAt = now;
        return null;
    }

    /**
     * A held stack of batch items (P2-K) deposits as one hold of {@code count}
     * units, inside one update of the batch. A batch with fewer units left than
     * the stack holds is refused with {@code "only n left"} (its items are not
     * all dead, so they are kept); an expiring batch is refused as above.
     */
    public static String reserveBatch(KeyBatch batch, String keyId, String conversionId, int count, int heldBefore,
            long now) {
        String why = StackRules.spendProblem(batch, keyId, count, now);
        if ("already used".equals(why) && batch.remaining > 0) {
            return "only " + batch.remaining + " left";
        }
        if (why != null) {
            return why;
        }
        if (batch.expiresAt > 0) {
            return "expiring";
        }
        return StackRules.reserve(batch, keyId, conversionId, count, heldBefore, now);
    }

    /**
     * The deposit's commit point, applied inside one update of the serial:
     * {@code RESERVED(conversionId) → CONVERTED}, keeping {@code usesLeft} as the
     * amount to credit. Refuses (and changes nothing) unless this conversion holds
     * the reservation, so a revoke in between wins and a repeat never converts twice.
     */
    public static boolean convert(KeySerial serial, String conversionId) {
        if (serial == null || !KeySerial.RESERVED.equals(serial.state) || !conversionId.equals(serial.reservedBy)) {
            return false;
        }
        serial.state = KeySerial.CONVERTED;
        serial.reservedBy = null;
        serial.redeemedBy = conversionId;
        serial.redeemedOutcome = null;
        return true;
    }

    /** Whether {@code serial} was converted by {@code conversionId}. */
    public static boolean convertedBy(KeySerial serial, String conversionId) {
        return serial != null && KeySerial.CONVERTED.equals(serial.state) && conversionId.equals(serial.redeemedBy);
    }

    /** Hands the deposit's reservation back; {@code false} if it holds none. */
    public static boolean release(KeySerial serial, String conversionId) {
        return OpeningRules.release(serial, conversionId);
    }

    /**
     * Credits a converted serial, inside one update of the player document keyed
     * by {@code conversionId}: adds {@code uses} to the balance and marks the
     * conversion {@code CREDITED}. Returns {@code false} (and changes nothing)
     * when it was credited before, so recovery may repeat it.
     */
    public static boolean credit(PlayerLootState state, String conversionId, String keyId, int uses, long now) {
        Conversion conversion = state.conversion(conversionId);
        if (conversion == null) {
            // Only a finished record is ever dropped, and a credited one is never revisited;
            // a missing record still means "not credited yet".
            conversion = prepareDeposit(state, conversionId, null, keyId, null, now);
        }
        if (Conversion.CREDITED.equals(conversion.state)) {
            return false;
        }
        int amount = Math.max(0, uses);
        if (amount > 0) {
            state.virtualKeys.merge(keyId, amount, Integer::sum);
        }
        conversion.count = amount;
        conversion.state = Conversion.CREDITED;
        conversion.note = null;
        return true;
    }

    /** Marks an unfinished conversion aborted; a credited or delivered one is left alone. */
    public static boolean abort(PlayerLootState state, String conversionId, String note) {
        Conversion conversion = state.conversion(conversionId);
        if (conversion == null || !conversion.unfinished()) {
            return false;
        }
        conversion.state = Conversion.ABORTED;
        conversion.note = note;
        trim(state);
        return true;
    }

    /**
     * What to do with a deposit found {@code PREPARED} after a crash or a
     * disconnect, from its serial's state and whether a stack with that serial is
     * still in the inventory ({@code null} when it could not be read).
     */
    public static DepositAction decideDeposit(String conversionId, KeySerial serial, Boolean keyInInventory) {
        if (serial == null) {
            return DepositAction.ABORT;
        }
        if (convertedBy(serial, conversionId)) {
            if (keyInInventory == null) {
                return DepositAction.CREDIT;
            }
            return keyInInventory ? DepositAction.CREDIT_CLEAN_UP : DepositAction.CREDIT;
        }
        if (KeySerial.RESERVED.equals(serial.state) && conversionId.equals(serial.reservedBy)) {
            if (keyInInventory == null) {
                return DepositAction.WAIT;
            }
            return keyInInventory ? DepositAction.RELEASE_ABORT : DepositAction.CONVERT_CREDIT;
        }
        if (KeySerial.ISSUED.equals(serial.state)) {
            return DepositAction.ABORT;
        }
        return DepositAction.ABORT_FOREIGN;
    }

    // ----- Withdrawal ---------------------------------------------------------------

    /** {@code null} when {@code count} keys may be withdrawn from {@code balance}; otherwise why not. */
    public static String withdrawProblem(int balance, int count, int max) {
        if (count < 1 || count > Math.max(1, max)) {
            return "count must be from 1 to " + Math.max(1, max);
        }
        if (balance < count) {
            return balance == 0 ? "no virtual keys of that kind" : "only " + balance + " virtual key(s) of that kind";
        }
        return null;
    }

    /**
     * The withdrawal's commit point, applied inside one update of the player
     * document: debits {@code count} and records the conversion {@code DEBITED}
     * with the {@code serials} it will issue. The balance never drops below zero,
     * and a conversion recorded before is never debited again.
     */
    public static Debit debit(PlayerLootState state, String conversionId, String requestId, String keyId, int count,
            List<String> serials, long now) {
        if (state.conversion(conversionId) != null) {
            return Debit.ALREADY;
        }
        int balance = state.virtualKeys.getOrDefault(keyId, 0);
        if (count < 1 || balance < count) {
            return Debit.NO_BALANCE;
        }
        if (balance == count) {
            state.virtualKeys.remove(keyId);
        } else {
            state.virtualKeys.put(keyId, balance - count);
        }
        Conversion conversion = new Conversion();
        conversion.conversionId = conversionId;
        conversion.kind = Conversion.WITHDRAW;
        conversion.keyId = keyId;
        conversion.count = count;
        conversion.serials = new ArrayList<>(serials);
        conversion.requestId = requestId;
        conversion.state = Conversion.DEBITED;
        conversion.at = now;
        state.conversions.add(conversion);
        trim(state);
        return Debit.DEBITED;
    }

    /**
     * The ledger entry of one withdrawn key: one use (one virtual key is one
     * opening) and no expiry (virtual balance never expires), whatever the
     * definition says, so a withdrawal is worth exactly what it debited.
     */
    public static KeySerial withdrawnSerial(String serialId, String keyId, int defVersion, String owner, String actor,
            long now) {
        KeySerial serial = new KeySerial();
        serial.serial = serialId;
        serial.keyId = keyId;
        serial.defVersion = defVersion;
        serial.owner = owner;
        serial.usesLeft = 1;
        serial.source = SOURCE;
        serial.issuedBy = actor;
        serial.issuedAt = now;
        serial.expiresAt = 0;
        return serial;
    }

    /**
     * What recovery does with one serial of a {@code DEBITED} withdrawal: a key
     * that is in the inventory or the inbox, or no longer {@code ISSUED} (spent,
     * revoked, deposited again), was delivered; anything else goes to the inbox.
     * If the player moved a delivered key into a chest, the inbox copy carries the
     * same serial, so only one of the two can ever be spent.
     */
    public static Delivery decideWithdrawn(KeySerial serial, Boolean held, boolean inInbox) {
        if (serial != null && !KeySerial.ISSUED.equals(serial.state)) {
            return Delivery.SKIP;
        }
        if (inInbox) {
            return Delivery.SKIP;
        }
        if (held == null) {
            return Delivery.WAIT;
        }
        return held ? Delivery.SKIP : Delivery.TO_INBOX;
    }

    /** {@code DEBITED → DELIVERED}; {@code false} when it was not waiting for delivery. */
    public static boolean settle(PlayerLootState state, String conversionId) {
        Conversion conversion = state.conversion(conversionId);
        if (conversion == null || !Conversion.DEBITED.equals(conversion.state)) {
            return false;
        }
        conversion.state = Conversion.DELIVERED;
        trim(state);
        return true;
    }

    /** The pending-inbox id of a key item, as {@code KeyService} files it. */
    public static String inboxId(String serial) {
        return "key#" + serial;
    }

    /** Drops the oldest finished conversions beyond {@link PlayerLootState#MAX_CONVERSIONS}. */
    static void trim(PlayerLootState state) {
        int excess = state.conversions.size() - PlayerLootState.MAX_CONVERSIONS;
        for (int i = 0; i < state.conversions.size() && excess > 0; ) {
            if (state.conversions.get(i).unfinished()) {
                i++;
            } else {
                state.conversions.remove(i);
                excess--;
            }
        }
    }
}
