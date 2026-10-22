package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.hyzionstudios.mysticessentials.modules.lootbox.api.RewardProvider;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.Opening;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PlayerLootState;

/**
 * The delivery rules of §4.4 that decide whether a reward runs again: which
 * openings recovery resumes, when staff may reconcile one, which actions of an
 * opening still run, and what settling an opening does to the player document.
 * Engine-free; {@link RewardService}, {@link OpeningService#recover} and
 * {@link OpeningService#reconcile} apply them.
 */
public final class DeliveryRules {

    /** What one action does on a delivery pass. */
    public enum Step {
        /** Already delivered, in the pending inbox, or dismissed: leave it. */
        SETTLED,
        /** Interrupted (or already in review) and not safe to repeat: hand it to staff. */
        REVIEW,
        /** Run it now. */
        RUN,
        /**
         * Interrupted, and its provider can say whether it was applied (P3-WP1):
         * ask it before deciding between settling, running again and review.
         */
        RECONCILE
    }

    /**
     * How long a delivery may run on this server before recovery treats it as
     * lost. Far beyond any real delivery (the longest wait inside one is the
     * platform's 30 s hold for a player's entity), but a world task the platform
     * drops never completes, and its opening should not wait for a restart.
     */
    public static final long STALLED_MS = TimeUnit.MINUTES.toMillis(10);

    private DeliveryRules() {
    }

    /**
     * Whether recovery resumes the delivery of {@code opening}. {@code runningSince}
     * is when this server started delivering it, or {@code null} if it is not. A
     * running delivery is left to finish until it stalls: recovery would find its
     * action ATTEMPTED, send an ITEM to REVIEW although it is being given, and run
     * a replay-safe action a second time beside it. Read {@code runningSince}
     * before loading {@code opening}: a delivery not running then has already
     * settled, so the load shows how it ended.
     */
    public static boolean resumes(Opening opening, Long runningSince, long now) {
        return opening != null && !opening.isTerminal()
                && (runningSince == null || now - runningSince >= STALLED_MS);
    }

    /**
     * {@code null} when staff may reconcile {@code opening} now; otherwise why not.
     * Only an opening in REVIEW is reconciled, and never while this server is
     * delivering it ({@code runningSince}, read before the load as for
     * {@link #resumes}): a regrant that passed the review check may not have
     * written its first mark yet, and a second regrant beside it would hand the
     * reward out twice. Asked in the player's lane, so no delivery starts between
     * the answer and the regrant's own.
     */
    public static String reconcileProblem(Opening opening, Long runningSince) {
        if (opening == null) {
            return "unknown opening";
        }
        if (runningSince != null) {
            return "already being delivered";
        }
        if (!Opening.REVIEW.equals(opening.state)) {
            return "not under review (" + opening.state + ")";
        }
        return null;
    }

    /**
     * What happens to {@code action} on this pass. An action found ATTEMPTED may
     * have taken effect before a crash, so it only runs again when replaying it is
     * harmless or staff force it ({@code /lootbox reconcile … regrant}).
     */
    public static Step step(Opening.Fulfillment action, boolean replaySafe, boolean force) {
        return step(action, replaySafe ? RewardProvider.Guarantee.IDEMPOTENT : RewardProvider.Guarantee.AT_MOST_ONCE,
                force);
    }

    /**
     * {@link #step(Opening.Fulfillment, boolean, boolean)} by the action's declared
     * guarantee (P3-WP1): idempotent actions run again, a reconcilable action
     * found ATTEMPTED is reconciled first, and everything else interrupted goes to
     * review unless staff force it. One already in review stays there.
     */
    public static Step step(Opening.Fulfillment action, RewardProvider.Guarantee guarantee, boolean force) {
        if (action.isSettled()) {
            return Step.SETTLED;
        }
        boolean attempted = Opening.Fulfillment.ATTEMPTED.equals(action.state);
        boolean interrupted = attempted || Opening.Fulfillment.REVIEW.equals(action.state);
        if (!interrupted || force) {
            return Step.RUN;
        }
        return switch (guarantee) {
            case IDEMPOTENT -> Step.RUN;
            case RECONCILABLE -> attempted ? Step.RECONCILE : Step.REVIEW;
            case AT_MOST_ONCE -> Step.REVIEW;
        };
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
