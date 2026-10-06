package org.hyzionstudios.mysticessentials.core.integration;

import java.lang.reflect.Method;
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

    private static final String PROVIDER_CLASS =
            "org.hyzionstudios.mysticvanish.api.MysticVanishProvider";

    private final MysticCore core;
    /**
     * The resolved MysticVanish hooks, or {@code null} when unavailable. Published
     * as one immutable value so a caller racing a reload never sees a
     * half-cleared set.
     */
    private volatile Hooks hooks;

    /** The reflective handles, resolved together. */
    private record Hooks(Method providerGet, Method providerRegistered, Method isVanished,
            Method canSee) {
    }

    public VanishBridge(MysticCore core) {
        this.core = core;
    }

    public void init(boolean enabledInConfig) {
        if (!enabledInConfig) {
            hooks = null;
            core.log(Level.INFO, "Vanish integration: disabled in config");
            return;
        }
        Hooks resolved;
        try {
            Class<?> provider = Class.forName(PROVIDER_CLASS, false,
                    VanishBridge.class.getClassLoader());
            Class<?> api = provider.getMethod("get").getReturnType();
            resolved = new Hooks(provider.getMethod("get"), provider.getMethod("isRegistered"),
                    api.getMethod("isVanished", UUID.class),
                    api.getMethod("canSee", UUID.class, UUID.class));
        } catch (Throwable t) {
            resolved = null;
        }
        hooks = resolved;
        core.log(Level.INFO, "Vanish integration: MysticVanish "
                + (resolved != null ? "detected" : "not present"));
    }

    public boolean isAvailable() {
        return available(hooks);
    }

    private static boolean available(Hooks h) {
        try {
            return h != null && Boolean.TRUE.equals(h.providerRegistered().invoke(null));
        } catch (Throwable t) {
            return false;
        }
    }

    /** @return {@code true} if the player is currently vanished. */
    public boolean isVanished(UUID player) {
        Hooks h = hooks;
        if (!available(h) || player == null) {
            return false;
        }
        try {
            Object api = h.providerGet().invoke(null);
            return Boolean.TRUE.equals(h.isVanished().invoke(api, player));
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
        Hooks h = hooks;
        if (!available(h) || target == null || viewer == null || viewer.equals(target)) {
            return true;
        }
        try {
            Object api = h.providerGet().invoke(null);
            return !Boolean.TRUE.equals(h.isVanished().invoke(api, target))
                    || Boolean.TRUE.equals(h.canSee().invoke(api, viewer, target));
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
