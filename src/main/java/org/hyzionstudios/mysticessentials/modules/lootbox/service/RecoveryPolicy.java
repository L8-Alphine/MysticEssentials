package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeySerial;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.Opening;

/**
 * What to do with a physical-key opening found unfinished after a crash or a
 * disconnect (implementation plan §4.3). The decision only needs the opening's
 * state, its serial's state and whether the key item is still in the inventory,
 * and it holds even when the engine lost recent inventory writes: a key that came
 * back aborts with nothing lost, and a key that was already redeemed is dead.
 */
public final class RecoveryPolicy {

    public enum Action {
        /** Nothing was spent: mark the opening aborted. */
        ABORT,
        /** Release the reservation, keep the key, abort the opening. */
        ABORT_RELEASE,
        /** The key left the inventory: commit the recorded outcome. */
        COMMIT,
        /** Already committed: make sure the opening says so, and remove the dead key stack. */
        ENSURE_COMMITTED_CLEAN_UP,
        /** Already committed: make sure the opening says so and resume delivery. */
        ENSURE_COMMITTED,
        /** The serial belongs to another opening or was revoked: abort without touching it. */
        ABORT_FOREIGN,
        /** Nothing to do. */
        NONE
    }

    private RecoveryPolicy() {
    }

    /**
     * @param openingState  the opening's recorded state
     * @param serial        its key's ledger entry (may be {@code null} if it vanished)
     * @param openingId     the opening being recovered
     * @param keyInInventory whether a stack with that serial is still in the player's inventory
     */
    public static Action decide(String openingState, KeySerial serial, String openingId, boolean keyInInventory) {
        if (Opening.ABORTED.equals(openingState) || Opening.DELIVERED.equals(openingState)) {
            return Action.NONE;
        }
        if (serial == null) {
            return Opening.PREPARED.equals(openingState) || Opening.RESERVED.equals(openingState)
                    ? Action.ABORT : Action.ENSURE_COMMITTED;
        }
        boolean redeemedByThis = openingId.equals(serial.redeemedBy);
        if (redeemedByThis) {
            return keyInInventory && KeySerial.REDEEMED.equals(serial.state)
                    ? Action.ENSURE_COMMITTED_CLEAN_UP : Action.ENSURE_COMMITTED;
        }
        if (Opening.COMMITTED.equals(openingState) || Opening.DELIVERING.equals(openingState)
                || Opening.REVIEW.equals(openingState)) {
            return Action.ENSURE_COMMITTED;
        }
        if (KeySerial.RESERVED.equals(serial.state) && openingId.equals(serial.reservedBy)) {
            return keyInInventory ? Action.ABORT_RELEASE : Action.COMMIT;
        }
        if (KeySerial.ISSUED.equals(serial.state)) {
            return Action.ABORT;
        }
        return Action.ABORT_FOREIGN;
    }
}
