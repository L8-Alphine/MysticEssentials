package org.hyzionstudios.mysticessentials.api.service;

import java.util.UUID;

/**
 * Economy costs and payouts, backed by VaultUnlocked when present. When no
 * economy is available every operation succeeds as a no-op so gameplay is never
 * blocked by a missing integration.
 */
public interface EconomyService {

    /** @return {@code true} if a VaultUnlocked economy provider is active. */
    boolean isAvailable();

    /** @return the player's current balance, or {@code 0} when no economy is present. */
    double balance(UUID player);

    /** @return {@code true} if the player can afford {@code amount} (always {@code true} without an economy). */
    boolean has(UUID player, double amount);

    /** Withdraws {@code amount}. @return {@code true} on success (or no-op success without an economy). */
    boolean withdraw(UUID player, double amount);

    /** Deposits {@code amount}. @return {@code true} on success (or no-op success without an economy). */
    boolean deposit(UUID player, double amount);

    /** Formats a currency amount for display using the provider's formatter. */
    String format(double amount);
}
