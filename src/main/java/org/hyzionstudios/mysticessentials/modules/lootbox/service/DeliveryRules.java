package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.util.Locale;

import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.Opening;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PlayerLootState;

/**
 * The delivery rules of §4.4 that decide whether a reward runs again: which
 * openings recovery resumes, which actions of an opening still run, and what
 * settling an opening does to the player document. Engine-free;
 * {@link RewardService} and {@link OpeningService#recover} apply them.
 */
public final class DeliveryRules {

    /** What one action does on a delivery pass. */
    public enum Step {
        /** Already delivered, in the pending inbox, or dismissed: leave it. */
        SETTLED,
        /** Interrupted (or already in review) and not safe to repeat: hand it to staff. */
        REVIEW,
        /** Run it now. */
        RUN
    }

    private DeliveryRules() {
    }

    /** Whether recovery resumes the delivery of {@code opening}. */
    public static boolean resumes(Opening opening) {
        return opening != null && !opening.isTerminal();
    }

    /**
     * What happens to {@code action} on this pass. An action found ATTEMPTED may
     * have taken effect before a crash, so it only runs again when replaying it is
     * harmless or staff force it ({@code /lootbox reconcile … regrant}).
     */
    public static Step step(Opening.Fulfillment action, boolean replaySafe, boolean force) {
        if (action.isSettled()) {
            return Step.SETTLED;
        }
        boolean interrupted = Opening.Fulfillment.ATTEMPTED.equals(action.state)
                || Opening.Fulfillment.REVIEW.equals(action.state);
        return interrupted && !force && !replaySafe ? Step.REVIEW : Step.RUN;
    }

    /** Replaying is harmless: inbox entries are keyed, nodes are idempotent, some commands are declared so. */
    public static boolean replaySafe(LootBoxDraft.RewardSpec reward) {
        String type = reward.type == null ? "" : reward.type.toUpperCase(Locale.ROOT);
        return "PERMISSION".equals(type) || ("COMMAND".equals(type) && reward.commandIdempotent);
    }

    /** Closes {@code openingId}'s delivery on the player document and shows {@code status} on its receipt. */
    public static void settle(PlayerLootState state, String openingId, String status) {
        state.openDeliveries.remove(openingId);
        for (PlayerLootState.Receipt receipt : state.receipts) {
            if (openingId.equals(receipt.openingId)) {
                receipt.status = status;
            }
        }
    }
}
