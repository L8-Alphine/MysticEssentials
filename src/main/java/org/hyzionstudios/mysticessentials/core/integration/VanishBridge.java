package org.hyzionstudios.mysticessentials.core.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Soft integration with MysticVanish
 * ({@code org.hyzionstudios.mysticvanish.api.MysticVanishProvider}). Detected by
 * class presence and resolved lazily per call (the provider may register after
 * this plugin starts), mirroring the VaultUnlocked pattern. All callers go
 * through {@link #isVanished(UUID)} / {@link #canSee(UUID, UUID)} so player
 * lists, suggestions, and join/leave-style announcements can hide vanished
 * players consistently. Fails open (nobody vanished) when MysticVanish is
 * absent or disabled in the config.
 */
public final class VanishBridge {

    private final MysticCore core;
    private boolean enabled;
    private boolean present;

    public VanishBridge(MysticCore core) {
        this.core = core;
    }

    public void init(boolean enabledInConfig) {
        enabled = enabledInConfig;
        if (!enabled) {
            core.log(Level.INFO, "Vanish integration: disabled in config");
            return;
        }
        try {
            Class.forName("org.hyzionstudios.mysticvanish.api.MysticVanishProvider");
            present = true;
        } catch (Throwable t) {
            present = false;
        }
        core.log(Level.INFO, "Vanish integration: MysticVanish "
                + (present ? "detected" : "not present"));
    }

    public boolean isAvailable() {
        return enabled && present && providerRegistered();
    }

    private boolean providerRegistered() {
        try {
            return org.hyzionstudios.mysticvanish.api.MysticVanishProvider.isRegistered();
        } catch (Throwable t) {
            return false;
        }
    }

    /** @return {@code true} if the player is currently vanished. */
    public boolean isVanished(UUID player) {
        if (!isAvailable() || player == null) {
            return false;
        }
        try {
            return org.hyzionstudios.mysticvanish.api.MysticVanishProvider.get().isVanished(player);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * @return {@code true} if {@code viewer} is allowed to see {@code target}
     *         (always true when the target is not vanished or MysticVanish is
     *         absent). A {@code null} viewer means "the server" and sees everyone.
     */
    public boolean canSee(UUID viewer, UUID target) {
        if (!isAvailable() || target == null || viewer == null || viewer.equals(target)) {
            return true;
        }
        try {
            var api = org.hyzionstudios.mysticvanish.api.MysticVanishProvider.get();
            return !api.isVanished(target) || api.canSee(viewer, target);
        } catch (Throwable t) {
            return true;
        }
    }

    /** Online players visible to {@code viewer} (vanish-filtered; unfiltered without MysticVanish). */
    public List<PlayerRef> visiblePlayers(UUID viewer) {
        List<PlayerRef> visible = new ArrayList<>();
        for (PlayerRef online : core.platform().onlinePlayers()) {
            if (canSee(viewer, online.getUuid())) {
                visible.add(online);
            }
        }
        return visible;
    }
}
