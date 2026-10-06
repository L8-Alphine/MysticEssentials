package org.hyzionstudios.mysticessentials.core.integration;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.chat.ChatMute;
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
    private static final String API_CLASS = "org.hyzionstudios.mysticmoderation.api.MysticModerationAPI";
    /** The {@code PunishmentType} whose author alone sees their lines. */
    private static final String SHADOW_MUTE = "SHADOW_MUTE";
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

    /**
     * The chat mute MysticModeration holds for a player, through its public API
     * ({@code MysticModerationAPI#punishments()} then {@code PunishmentService#activeMute(UUID)}):
     * the same lookup its own chat gate makes, where a {@code SHADOW_MUTE} shows the
     * player's lines to them alone and any other chat-blocking punishment blocks them.
     * Empty when MysticModeration is absent, disabled in the config, or fails.
     */
    public Optional<ChatMute> activeMute(UUID player) {
        if (player == null) {
            return Optional.empty();
        }
        Object moderationApi = api().orElse(null);
        if (moderationApi == null) {
            return Optional.empty();
        }
        try {
            // Resolved through the public interfaces: the implementing classes need not be public.
            Class<?> apiType = Class.forName(API_CLASS, false, moderationApi.getClass().getClassLoader());
            Method punishments = apiType.getMethod("punishments");
            Object service = punishments.invoke(moderationApi);
            if (service == null) {
                return Optional.empty();
            }
            Object found = punishments.getReturnType().getMethod("activeMute", UUID.class)
                    .invoke(service, player);
            if (!(found instanceof Optional<?> mute) || mute.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(chatMuteOf(mute.get()));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /** Reads a MysticModeration {@code Punishment} record into a {@link ChatMute}. */
    public static ChatMute chatMuteOf(Object punishment) throws ReflectiveOperationException {
        Object type = punishment.getClass().getMethod("type").invoke(punishment);
        Object reason = punishment.getClass().getMethod("reason").invoke(punishment);
        Object expiresAt = punishment.getClass().getMethod("expiresAt").invoke(punishment);
        String typeName = type instanceof Enum<?> constant ? constant.name() : String.valueOf(type);
        return new ChatMute(ChatMute.Scope.SERVER,
                reason instanceof String text ? text : "",
                expiresAt instanceof Instant instant ? instant : null,
                SHADOW_MUTE.equals(typeName));
    }

    private Optional<PluginBase> plugin() {
        try {
            return Optional.ofNullable(HytaleServer.get().getPluginManager().getPlugin(PLUGIN_ID));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }
}
