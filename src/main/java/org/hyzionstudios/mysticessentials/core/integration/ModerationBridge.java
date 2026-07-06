package org.hyzionstudios.mysticessentials.core.integration;

import java.util.Optional;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;

/**
 * Soft integration with MysticModeration.
 *
 * <p>The moderation plugin already detects Mystic Essentials and may register
 * itself as an external module. This bridge gives Essentials a matching,
 * fail-open view of MysticModeration's API for diagnostics, reload hooks, and
 * future module-to-module calls without making MysticModeration a hard runtime
 * dependency.</p>
 */
public final class ModerationBridge {

    private static final String PROVIDER_CLASS = "org.hyzionstudios.mysticmoderation.api.MysticModerationProvider";

    private final MysticCore core;
    private boolean enabled;
    private boolean present;

    public ModerationBridge(MysticCore core) {
        this.core = core;
    }

    public void init(boolean enabledInConfig) {
        enabled = enabledInConfig;
        if (!enabled) {
            core.log(Level.INFO, "Moderation integration: disabled in config");
            return;
        }
        try {
            Class.forName(PROVIDER_CLASS);
            present = true;
        } catch (Throwable t) {
            present = false;
        }
        core.log(Level.INFO, "Moderation integration: MysticModeration "
                + (present ? "detected" : "not present"));
    }

    public boolean isAvailable() {
        return enabled && present && api().isPresent();
    }

    public Optional<Object> api() {
        if (!enabled || !present) {
            return Optional.empty();
        }
        try {
            Object result = Class.forName(PROVIDER_CLASS).getMethod("get").invoke(null);
            if (result instanceof Optional<?> optional) {
                return optional.map(Object.class::cast);
            }
            return Optional.ofNullable(result);
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    public boolean reload() {
        return api().map(moderationApi -> {
            try {
                Object result = moderationApi.getClass().getMethod("reload").invoke(moderationApi);
                return Boolean.TRUE.equals(result);
            } catch (Throwable t) {
                return false;
            }
        }).orElse(false);
    }
}
