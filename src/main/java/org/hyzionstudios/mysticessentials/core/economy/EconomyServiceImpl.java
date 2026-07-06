package org.hyzionstudios.mysticessentials.core.economy;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.service.EconomyService;
import org.hyzionstudios.mysticessentials.core.MysticCore;

import com.hypixel.hytale.server.core.universe.PlayerRef;

import net.cfh.vault.VaultUnlocked;
import net.milkbowl.vault2.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;

/**
 * {@link EconomyService} bridge to VaultUnlocked (Vault2 API).
 *
 * <p>The economy provider is resolved <b>lazily</b> on each call via
 * {@link VaultUnlocked#economy()} rather than cached at startup, because an
 * economy plugin frequently registers its provider <i>after</i> Mystic
 * Essentials has started. Detecting VaultUnlocked itself (class presence) is kept
 * separate from whether a provider is currently registered, so logging is
 * accurate and a late-registered economy is picked up automatically.</p>
 *
 * <p>When no provider is available every operation is a safe no-op success, so a
 * missing economy never blocks gameplay (a paid warp simply becomes free).</p>
 */
public final class EconomyServiceImpl implements EconomyService {

    private static final String PLUGIN = "MysticEssentials";

    private final MysticCore core;
    private boolean vaultPresent;

    public EconomyServiceImpl(MysticCore core) {
        this.core = core;
    }

    public void init(boolean enabledInConfig) {
        vaultPresent = enabledInConfig && isClassPresent("net.cfh.vault.VaultUnlocked");
        if (!enabledInConfig) {
            core.log(Level.INFO, "Economy integration: disabled in config.");
            return;
        }
        if (!vaultPresent) {
            core.log(Level.INFO, "Economy integration: VaultUnlocked not present (costs/payouts run as no-ops).");
            return;
        }
        Economy economy = resolve();
        if (economy != null) {
            core.log(Level.INFO, "Economy integration: VaultUnlocked connected (" + safeName(economy) + ").");
        } else {
            core.log(Level.INFO, "Economy integration: VaultUnlocked detected, waiting for an economy provider "
                    + "(resolved lazily — a provider that registers later will be used automatically).");
        }
    }

    /** Resolves the currently-registered economy provider, or {@code null} if none is available. */
    private Economy resolve() {
        if (!vaultPresent) {
            return null;
        }
        try {
            return VaultUnlocked.economy().filter(Economy::isEnabled).orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public boolean isAvailable() {
        return resolve() != null;
    }

    @Override
    public double balance(UUID player) {
        Economy economy = resolve();
        if (economy == null) {
            return 0.0;
        }
        ensureAccount(economy, player);
        return economy.balance(PLUGIN, player).doubleValue();
    }

    @Override
    public boolean has(UUID player, double amount) {
        Economy economy = resolve();
        if (economy == null) {
            return true;
        }
        ensureAccount(economy, player);
        return economy.has(PLUGIN, player, BigDecimal.valueOf(amount));
    }

    @Override
    public boolean withdraw(UUID player, double amount) {
        Economy economy = resolve();
        if (economy == null) {
            return true;
        }
        ensureAccount(economy, player);
        EconomyResponse response = economy.withdraw(PLUGIN, player, BigDecimal.valueOf(amount));
        return response != null && response.transactionSuccess();
    }

    @Override
    public boolean deposit(UUID player, double amount) {
        Economy economy = resolve();
        if (economy == null) {
            return true;
        }
        ensureAccount(economy, player);
        EconomyResponse response = economy.deposit(PLUGIN, player, BigDecimal.valueOf(amount));
        return response != null && response.transactionSuccess();
    }

    @Override
    public String format(double amount) {
        Economy economy = resolve();
        if (economy == null) {
            return String.format("%.2f", amount);
        }
        return economy.format(BigDecimal.valueOf(amount));
    }

    private void ensureAccount(Economy economy, UUID player) {
        try {
            if (!economy.hasAccount(player)) {
                economy.createAccount(player, accountName(player));
            }
        } catch (Throwable t) {
            core.log(Level.WARNING, "Economy account check failed for " + player + ": " + t);
        }
    }

    private String accountName(UUID player) {
        return core.platform().findPlayer(player).map(PlayerRef::getUsername).orElse(player.toString());
    }

    private static String safeName(Economy economy) {
        try {
            return economy.getName();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static boolean isClassPresent(String name) {
        try {
            Class.forName(name, false, EconomyServiceImpl.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
