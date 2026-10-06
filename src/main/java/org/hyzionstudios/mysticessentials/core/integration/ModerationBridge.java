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
    private static final String PUNISHMENT_SERVICE =
            "org.hyzionstudios.mysticmoderation.api.service.PunishmentService";
    private static final String CHAT_GUARD_SERVICE =
            "org.hyzionstudios.mysticmoderation.api.service.ChatGuardService";
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
        try {
            return liveApi();
        } catch (ModerationUnavailableException e) {
            return Optional.empty();
        }
    }

    /**
     * The live MysticModeration API: empty when the integration is disabled in the
     * config, or MysticModeration is not loaded or not started.
     *
     * @throws ModerationUnavailableException when MysticModeration is loaded but its
     *         API cannot be reached
     */
    private Optional<Object> liveApi() throws ModerationUnavailableException {
        if (!enabled) {
            return Optional.empty();
        }
        PluginBase moderationPlugin = plugin().orElse(null);
        if (moderationPlugin == null) {
            return Optional.empty();
        }
        try {
            Class<?> provider = Class.forName(PROVIDER_CLASS, false,
                    moderationPlugin.getClass().getClassLoader());
            Object result = provider.getMethod("get").invoke(null);
            if (result instanceof Optional<?> optional) {
                return optional.map(Object.class::cast);
            }
            return Optional.ofNullable(result);
        } catch (Throwable t) {
            throw new ModerationUnavailableException(t);
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
     * The chat mute MysticModeration holds for a player; empty when it holds none, and
     * also when it cannot be asked (see {@link #lookupMute(UUID)} to tell those apart).
     */
    public Optional<ChatMute> activeMute(UUID player) {
        try {
            return lookupMute(player);
        } catch (ModerationUnavailableException e) {
            return Optional.empty();
        }
    }

    /**
     * The chat mute MysticModeration holds for a player, through its public API
     * ({@code ModerationServiceRegistry#find(PunishmentService)} then
     * {@code PunishmentService#activeMute(UUID)}): the same lookup its own chat gate
     * makes, where a {@code SHADOW_MUTE} shows the player's lines to them alone and any
     * other chat-blocking punishment blocks them. Empty when there is none, when the
     * integration is disabled, or when MysticModeration or its punishments module is
     * not running.
     *
     * @throws ModerationUnavailableException when MysticModeration is running but the
     *         lookup fails
     */
    public Optional<ChatMute> lookupMute(UUID player) throws ModerationUnavailableException {
        if (player == null) {
            return Optional.empty();
        }
        Object moderationApi = liveApi().orElse(null);
        if (moderationApi == null) {
            return Optional.empty();
        }
        try {
            Object punishments = service(moderationApi, PUNISHMENT_SERVICE).orElse(null);
            if (punishments == null) {
                return Optional.empty();
            }
            Object found = serviceType(moderationApi, PUNISHMENT_SERVICE).getMethod("activeMute", UUID.class)
                    .invoke(punishments, player);
            if (!(found instanceof Optional<?> mute) || mute.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(chatMuteOf(mute.get()));
        } catch (Throwable t) {
            throw new ModerationUnavailableException(t);
        }
    }

    /**
     * MysticModeration's chat guard verdict on a line ({@code ChatGuardService#evaluate}):
     * its word filter, link, caps and spam rules, chat lock and slow mode, with its own
     * bypass permission. {@link GuardVerdict#ALLOW} when the integration is disabled, or
     * MysticModeration or its chat guard module is not running.
     *
     * <p>The public {@code evaluate} does not record the line, so slow mode and the spam
     * rules measure it against the player's last public chat line only.</p>
     *
     * @throws ModerationUnavailableException when MysticModeration is running but the
     *         check fails
     */
    public GuardVerdict checkChat(UUID player, String username, String message)
            throws ModerationUnavailableException {
        if (player == null) {
            return GuardVerdict.ALLOW;
        }
        Object moderationApi = liveApi().orElse(null);
        if (moderationApi == null) {
            return GuardVerdict.ALLOW;
        }
        try {
            Object guard = service(moderationApi, CHAT_GUARD_SERVICE).orElse(null);
            if (guard == null) {
                return GuardVerdict.ALLOW;
            }
            Object check = serviceType(moderationApi, CHAT_GUARD_SERVICE)
                    .getMethod("evaluate", UUID.class, String.class, String.class)
                    .invoke(guard, player, username, message);
            return guardVerdictOf(check);
        } catch (Throwable t) {
            throw new ModerationUnavailableException(t);
        }
    }

    /** A service MysticModeration publishes; empty while its module is not running. */
    private static Optional<Object> service(Object moderationApi, String serviceClass)
            throws ReflectiveOperationException {
        // Resolved through the public interfaces: the implementing classes need not be public.
        Class<?> apiType = Class.forName(API_CLASS, false, moderationApi.getClass().getClassLoader());
        Method registryGetter = apiType.getMethod("serviceRegistry");
        Object registry = registryGetter.invoke(moderationApi);
        Object found = registryGetter.getReturnType().getMethod("find", Class.class)
                .invoke(registry, serviceType(moderationApi, serviceClass));
        return found instanceof Optional<?> optional ? optional.map(Object.class::cast) : Optional.empty();
    }

    private static Class<?> serviceType(Object moderationApi, String serviceClass)
            throws ClassNotFoundException {
        return Class.forName(serviceClass, false, moderationApi.getClass().getClassLoader());
    }

    /**
     * Reads a MysticModeration {@code ChatGuardService.ChatCheck} record: {@code ALLOW},
     * {@code BLOCK} (its {@code detail} is the feedback for the sender) or {@code REWRITE}
     * (its {@code detail} is the text to send instead).
     *
     * @throws ReflectiveOperationException also for an outcome this bridge does not know
     */
    public static GuardVerdict guardVerdictOf(Object check) throws ReflectiveOperationException {
        Object outcome = check.getClass().getMethod("outcome").invoke(check);
        Object detail = check.getClass().getMethod("detail").invoke(check);
        String text = detail instanceof String value ? value : "";
        String name = outcome instanceof Enum<?> constant ? constant.name() : String.valueOf(outcome);
        return switch (name) {
            case "ALLOW" -> GuardVerdict.ALLOW;
            case "BLOCK" -> new GuardVerdict(GuardVerdict.Outcome.BLOCK, text);
            case "REWRITE" -> text.isEmpty()
                    ? GuardVerdict.ALLOW
                    : new GuardVerdict(GuardVerdict.Outcome.REWRITE, text);
            default -> throw new ReflectiveOperationException("Unknown chat guard outcome " + name);
        };
    }

    /**
     * MysticModeration's chat guard verdict on one line.
     *
     * @param text for {@code BLOCK} the feedback for the sender, for {@code REWRITE} the
     *             text to send instead, otherwise empty
     */
    public record GuardVerdict(Outcome outcome, String text) {

        public static final GuardVerdict ALLOW = new GuardVerdict(Outcome.ALLOW, "");

        /** What the chat guard decided. */
        public enum Outcome {
            ALLOW, BLOCK, REWRITE
        }
    }

    /** MysticModeration is running but could not answer; callers refuse rather than guess. */
    public static final class ModerationUnavailableException extends Exception {

        private static final long serialVersionUID = 1L;

        ModerationUnavailableException(Throwable cause) {
            super(cause);
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
