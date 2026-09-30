package org.hyzionstudios.mysticessentials.core.integration;

import java.util.Optional;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.plugin.PluginBase;

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
    private static final PluginIdentifier PLUGIN_ID =
            new PluginIdentifier("org.hyzionstudios", "mysticmoderation");

    private final MysticCore core;
    private boolean enabled;

    public ModerationBridge(MysticCore core) {
        this.core = core;
    }

    public void init(boolean enabledInConfig) {
        enabled = enabledInConfig;
        if (!enabled) {
            core.log(Level.INFO, "Moderation integration: disabled in config");
            return;
        }
        core.log(Level.INFO, "Moderation integration: MysticModeration "
                + (plugin().isPresent() ? "detected" : "not present yet"));
    }

    public boolean isAvailable() {
        return enabled && api().isPresent();
    }

    public Optional<Object> api() {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            PluginBase moderationPlugin = plugin().orElse(null);
            if (moderationPlugin == null) {
                return Optional.empty();
            }
            Class<?> provider = Class.forName(PROVIDER_CLASS, false,
                    moderationPlugin.getClass().getClassLoader());
            Object result = provider.getMethod("get").invoke(null);
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

    private Optional<PluginBase> plugin() {
        try {
            return Optional.ofNullable(HytaleServer.get().getPluginManager().getPlugin(PLUGIN_ID));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }
}
