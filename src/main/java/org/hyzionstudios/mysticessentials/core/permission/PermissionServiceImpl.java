package org.hyzionstudios.mysticessentials.core.permission;

import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.service.PermissionService;
import org.hyzionstudios.mysticessentials.core.MysticCore;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.user.User;

/**
 * {@link PermissionService} backed by the verified Hytale permission check
 * ({@code PlayerRef.hasPermission}) for gating, with LuckPerms wired in for the
 * richer lookups Hytale does not expose (primary group).
 *
 * <p>Permission checks intentionally go through {@code PlayerRef.hasPermission}
 * rather than the LuckPerms API directly: on a LuckPerms server the LuckPerms
 * platform is the registered permission provider, so those checks already reflect
 * LuckPerms (and correctly fall back to Hytale's built-in handling otherwise).
 * This avoids bypassing the platform and the offline-user / query-context
 * pitfalls of reading LuckPerms cached data directly.</p>
 */
public final class PermissionServiceImpl implements PermissionService {

    private final MysticCore core;
    private LuckPerms luckPerms;

    public PermissionServiceImpl(MysticCore core) {
        this.core = core;
    }

    public void init(boolean enabledInConfig) {
        if (enabledInConfig) {
            try {
                luckPerms = LuckPermsProvider.get();
            } catch (Throwable t) {
                luckPerms = null;
            }
        }
        core.log(Level.INFO, "Permission integration: LuckPerms "
                + (luckPerms != null ? "connected" : "not present, using Hytale permissions"));
    }

    @Override
    public boolean isLuckPermsAvailable() {
        return luckPerms != null;
    }

    @Override
    public boolean has(UUID player, String permission) {
        return core.platform().findPlayer(player)
                .map(ref -> ref.hasPermission(permission))
                .orElse(false);
    }

    @Override
    public String primaryGroup(UUID player) {
        if (luckPerms != null) {
            try {
                User user = luckPerms.getUserManager().getUser(player);
                if (user != null) {
                    return user.getPrimaryGroup();
                }
            } catch (Throwable t) {
                core.log(Level.WARNING, "LuckPerms primaryGroup lookup failed: " + t);
            }
            return null;
        }
        return "default";
    }

    @Override
    public String prefix(UUID player) {
        return meta(player, true);
    }

    @Override
    public String suffix(UUID player) {
        return meta(player, false);
    }

    private String meta(UUID player, boolean prefix) {
        if (luckPerms == null) {
            return "";
        }
        try {
            User user = luckPerms.getUserManager().getUser(player);
            if (user == null) {
                return "";
            }
            String value = prefix
                    ? user.getCachedData().getMetaData().getPrefix()
                    : user.getCachedData().getMetaData().getSuffix();
            return value == null ? "" : value;
        } catch (Throwable t) {
            core.log(Level.WARNING, "LuckPerms meta lookup failed: " + t);
            return "";
        }
    }

    @Override
    public String metaValue(UUID player, String key) {
        if (luckPerms == null || player == null || key == null) {
            return null;
        }
        try {
            User user = luckPerms.getUserManager().getUser(player);
            return user == null ? null : user.getCachedData().getMetaData().getMetaValue(key);
        } catch (Throwable t) {
            core.log(Level.WARNING, "LuckPerms meta lookup for '" + key + "' failed: " + t);
            return null;
        }
    }

    @Override
    public AutoCloseable onUserDataRecalculated(Consumer<UUID> listener) {
        if (luckPerms == null) {
            return null;
        }
        try {
            EventSubscription<UserDataRecalculateEvent> subscription = luckPerms.getEventBus()
                    .subscribe(UserDataRecalculateEvent.class,
                            event -> listener.accept(event.getUser().getUniqueId()));
            return subscription::close;
        } catch (Throwable t) {
            core.log(Level.WARNING, "LuckPerms event subscription failed: " + t);
            return null;
        }
    }

    @Override
    public OptionalInt limit(UUID player, String basePermission, boolean unlimitedIsMax) {
        return core.platform().findPlayer(player).map(ref -> {
            if (unlimitedIsMax && ref.hasPermission(basePermission + ".unlimited")) {
                return OptionalInt.of(Integer.MAX_VALUE);
            }
            int best = -1;
            // Probe a sensible range of numeric suffix limits (e.g. home.limit.<n>).
            for (int i = 0; i <= 128; i++) {
                if (ref.hasPermission(basePermission + "." + i)) {
                    best = Math.max(best, i);
                }
            }
            return best >= 0 ? OptionalInt.of(best) : OptionalInt.empty();
        }).orElse(OptionalInt.empty());
    }
}
