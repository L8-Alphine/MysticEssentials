package org.hyzionstudios.mysticessentials.core.integration;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Soft integration with MysticIdentity's managed (parentally supervised) accounts
 * ({@code org.hyzionstudios.mysticidentity.MysticIdentityProvider}). Detected by
 * class presence and resolved lazily per call — the provider registers when the
 * MysticIdentity agent starts, which may be after this plugin — mirroring
 * {@link VanishBridge}. MysticIdentity owns the policy; chat only asks it at the
 * moment of delivery and never caches the answer.
 *
 * <p>Two questions, both fail-open (everyone allowed) when MysticIdentity is absent,
 * disabled in the config, or throws: {@link #allowsInteraction} for a message
 * between two players ({@code TEXT_PRIVATE}, {@code TEXT_PUBLIC}) and
 * {@link #allows} for one player's own capability ({@code TEXT_CROSS_PLATFORM}).
 * A child's guardians and trusted staff are exempt inside MysticIdentity itself,
 * so a caller never special-cases them here.</p>
 */
public final class ManagedAccountsBridge {
    private static final String PROVIDER_CLASS =
            "org.hyzionstudios.mysticidentity.MysticIdentityProvider";
    private static final String CAPABILITY_CLASS =
            "org.hyzionstudios.mysticidentity.api.managed.ManagedCapability";

    /** A private message between two players. */
    public static final String TEXT_PRIVATE = "TEXT_PRIVATE";
    /** A public line from one player reaching one listener. */
    public static final String TEXT_PUBLIC = "TEXT_PUBLIC";
    /** Text crossing from another platform (a bridged Discord line) to a player. */
    public static final String TEXT_CROSS_PLATFORM = "TEXT_CROSS_PLATFORM";

    private final MysticCore core;
    private boolean enabled;
    private boolean present;
    private Method providerGet;
    private Method apiManaged;
    private Method check;
    private Method checkInteraction;
    private Method isAllowed;
    private Class<?> capabilityClass;

    public ManagedAccountsBridge(MysticCore core) {
        this.core = core;
    }

    public void init(boolean enabledInConfig) {
        enabled = enabledInConfig;
        clear();
        if (!enabled) {
            core.log(Level.INFO, "Managed accounts integration: disabled in config");
            return;
        }
        try {
            ClassLoader loader = ManagedAccountsBridge.class.getClassLoader();
            Class<?> provider = Class.forName(PROVIDER_CLASS, false, loader);
            providerGet = provider.getMethod("get");
            Class<?> api = Class.forName(
                    "org.hyzionstudios.mysticidentity.api.service.MysticIdentityApi", false, loader);
            apiManaged = api.getMethod("managed");
            Class<?> service = apiManaged.getReturnType();
            capabilityClass = Class.forName(CAPABILITY_CLASS, false, loader);
            check = service.getMethod("check", UUID.class, capabilityClass);
            checkInteraction = service.getMethod("checkInteraction", UUID.class, UUID.class, capabilityClass);
            isAllowed = check.getReturnType().getMethod("isAllowed");
            present = true;
        } catch (Throwable t) {
            clear();
        }
        core.log(Level.INFO, "Managed accounts integration: MysticIdentity "
                + (present ? "detected" : "not present"));
    }

    public boolean isAvailable() {
        return enabled && present;
    }

    /**
     * @return whether {@code source} may reach {@code target} through {@code capability};
     *         always {@code true} for the same player, a {@code null} side, or without
     *         MysticIdentity.
     */
    public boolean allowsInteraction(UUID source, UUID target, String capability) {
        if (!isAvailable() || source == null || target == null || source.equals(target)) {
            return true;
        }
        try {
            Object service = service();
            if (service == null) {
                return true;
            }
            Object decision = checkInteraction.invoke(service, source, target, capability(capability));
            return Boolean.TRUE.equals(isAllowed.invoke(decision));
        } catch (Throwable t) {
            return true;
        }
    }

    /** @return whether {@code player} has {@code capability}; {@code true} without MysticIdentity. */
    public boolean allows(UUID player, String capability) {
        if (!isAvailable() || player == null) {
            return true;
        }
        try {
            Object service = service();
            if (service == null) {
                return true;
            }
            Object decision = check.invoke(service, player, capability(capability));
            return Boolean.TRUE.equals(isAllowed.invoke(decision));
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * The listeners a line from {@code sender} may reach. A {@code null} sender is text
     * from outside the game (a bridge), judged on each listener's own
     * {@link #TEXT_CROSS_PLATFORM}; a player is judged per pair on {@code capability}.
     * Returns the same collection when nobody is affected.
     */
    public Collection<PlayerRef> reachable(UUID sender, Collection<PlayerRef> listeners, String capability) {
        if (!isAvailable() || listeners.isEmpty()) {
            return listeners;
        }
        List<PlayerRef> all = listeners instanceof List<PlayerRef> list ? list : new ArrayList<>(listeners);
        List<PlayerRef> kept = null;
        for (int i = 0; i < all.size(); i++) {
            PlayerRef listener = all.get(i);
            boolean keep = sender == null
                    ? allows(listener.getUuid(), TEXT_CROSS_PLATFORM)
                    : allowsInteraction(sender, listener.getUuid(), capability);
            if (keep) {
                if (kept != null) {
                    kept.add(listener);
                }
            } else if (kept == null) {
                kept = new ArrayList<>(all.subList(0, i));
            }
        }
        return kept == null ? listeners : kept;
    }

    private Object service() throws Exception {
        Object maybe = providerGet.invoke(null);
        if (maybe instanceof Optional<?> optional) {
            return optional.map(api -> {
                try {
                    return apiManaged.invoke(api);
                } catch (Exception e) {
                    return null;
                }
            }).orElse(null);
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object capability(String name) {
        return Enum.valueOf((Class<? extends Enum>) capabilityClass, name);
    }

    private void clear() {
        present = false;
        providerGet = null;
        apiManaged = null;
        check = null;
        checkInteraction = null;
        isAllowed = null;
        capabilityClass = null;
    }
}
