package org.hyzionstudios.mysticessentials.modules.lootbox.integration;

import java.util.Optional;

/**
 * Who may receive a {@code PAID} key grant under {@code PAID_RANDOM_RESTRICTED}
 * (P3-WP7, Design Bible §16: "block purchase paths ... for users identified as
 * minors"). Every check fails closed: a signal that is missing or unknown refuses
 * the grant.
 *
 * <ul>
 *   <li>MysticIdentity must be running; without it nobody can tell a managed
 *       (supervised) account from an adult one.</li>
 *   <li>The player's managed-account claim must be known and {@code false}.
 *       MysticIdentity allows economy capabilities for a managed account whose
 *       policy hasn't arrived yet, so its {@code check} alone is not enough.</li>
 *   <li>{@code isManaged} must be {@code false} and {@code REAL_MONEY_PURCHASE}
 *       must be allowed.</li>
 *   <li>With {@code requireAgeSignal} (the default), an explicit adult signal is
 *       needed too. MysticIdentity has none yet, so paid grants stay refused until
 *       it gains one or the operator, having verified ages another way, turns the
 *       requirement off.</li>
 * </ul>
 *
 * <p>Engine-free; {@link IdentityPaidGate} gathers the signals.</p>
 */
public final class PaidPolicy {

    /**
     * What MysticIdentity said about one player. {@code managedClaim} and
     * {@code adult} are empty when unknown.
     */
    public record Signals(boolean identityRunning, Optional<Boolean> managedClaim, boolean managed,
            boolean purchaseAllowed, Optional<Boolean> adult) {

        public static Signals absent() {
            return new Signals(false, Optional.empty(), false, false, Optional.empty());
        }
    }

    private PaidPolicy() {
    }

    /** {@code null} when the paid grant may go ahead, otherwise why it is refused. */
    public static String refusal(Signals signals, boolean requireAgeSignal) {
        if (signals == null || !signals.identityRunning()) {
            return "paid key grants need MysticIdentity to confirm the account is not a managed (child) account";
        }
        if (signals.managedClaim().isEmpty()) {
            return "MysticIdentity has not reported whether this is a managed account yet";
        }
        if (signals.managedClaim().get() || signals.managed()) {
            return "paid key grants are blocked for managed accounts";
        }
        if (!signals.purchaseAllowed()) {
            return "MysticIdentity does not allow real-money purchases for this account";
        }
        if (requireAgeSignal && !signals.adult().orElse(false)) {
            return "paid key grants need an age signal MysticIdentity does not provide yet "
                    + "(integrations.identity.requireAgeSignal)";
        }
        return null;
    }
}
