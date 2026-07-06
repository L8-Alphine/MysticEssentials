package org.hyzionstudios.mysticessentials.modules.chat;

import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/** Private messaging, reply targets, social spy, Redis relay, and offline-mail fallback. */
public final class PrivateMessagingSubModule {

    private static final String CHANNEL_PM = "pm";

    private final MysticCore core;
    private final ChatModule chat;
    private final java.util.Map<UUID, UUID> replyTargets = new java.util.concurrent.ConcurrentHashMap<>();

    private ChatConfig.PrivateMessaging config = new ChatConfig.PrivateMessaging();

    public PrivateMessagingSubModule(MysticCore core, ChatModule chat) {
        this.core = core;
        this.chat = chat;
    }

    public void enable(ChatConfig.PrivateMessaging config, Consumer<MysticCommand> commandRegistrar) {
        reload(config);
        if (!this.config.enabled) {
            return;
        }
        commandRegistrar.accept(new MessageCommand());
        commandRegistrar.accept(new ReplyCommand());
        if (this.config.allowCrossServer && core.redis().isEnabled()) {
            core.redis().subscribe(CHANNEL_PM, this::handleRemotePm);
        }
    }

    public void reload(ChatConfig.PrivateMessaging config) {
        this.config = config == null ? new ChatConfig.PrivateMessaging() : config;
    }

    public void disable() {
        replyTargets.clear();
    }

    public CompletableFuture<Boolean> privateMessage(UUID from, UUID to, String message) {
        if (!config.enabled) {
            return CompletableFuture.completedFuture(false);
        }
        Optional<PlayerRef> target = core.platform().findPlayer(to);
        Optional<PlayerRef> sender = core.platform().findPlayer(from);
        String fromName = sender.map(PlayerRef::getUsername).orElse("Server");
        String prepared = sender.map(ref -> chat.preparePlayerMessage(ref, message)).orElse(message);
        if (target.isPresent()) {
            deliverLocalPm(from, fromName, target.get(), prepared);
            return CompletableFuture.completedFuture(true);
        }
        if (config.allowCrossServer && core.redis().isEnabled()) {
            publishPm(from.toString(), fromName, to.toString(), null, prepared);
            echoToSender(from, "player", prepared);
            return CompletableFuture.completedFuture(true);
        }
        if (config.offlineToMail && core.getMailService() != null) {
            return core.getMailService().send(from, fromName, to, prepared).thenApply(ignored -> true);
        }
        return CompletableFuture.completedFuture(false);
    }

    private void deliverLocalPm(UUID from, String fromName, PlayerRef target, String message) {
        core.getMessageService().sendKey(target, "pm-received",
                Map.of("sender", fromName, "message", message));
        replyTargets.put(target.getUuid(), from);
        core.platform().findPlayer(from).ifPresent(ref -> {
            core.getMessageService().sendKey(ref, "pm-sent",
                    Map.of("target", target.getUsername(), "message", message));
            replyTargets.put(from, target.getUuid());
        });
        notifySocialSpies(from, fromName, target, message);
        core.getEventBus().publish(new org.hyzionstudios.mysticessentials.api.event.PrivateMessageEvent(
                from, fromName, target.getUuid(), target.getUsername(), message, false));
    }

    private void echoToSender(UUID from, String toLabel, String message) {
        core.platform().findPlayer(from).ifPresent(ref ->
                core.getMessageService().sendKey(ref, "pm-sent",
                        Map.of("target", toLabel, "message", message)));
    }

    private void publishPm(String fromUuid, String fromName, String toUuid, String toName, String message) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("fromUuid", fromUuid);
        envelope.addProperty("fromName", fromName);
        if (toUuid != null) {
            envelope.addProperty("toUuid", toUuid);
        }
        if (toName != null) {
            envelope.addProperty("toName", toName);
        }
        envelope.addProperty("message", message);
        core.redis().publish(CHANNEL_PM, Json.toString(envelope));
    }

    private void handleRemotePm(String payload) {
        JsonObject o = Json.asObject(Json.parse(payload));
        String message = o.has("message") ? o.get("message").getAsString() : "";
        String fromName = o.has("fromName") ? o.get("fromName").getAsString() : "Server";

        PlayerRef target = null;
        if (o.has("toUuid")) {
            target = core.platform().findPlayer(UUID.fromString(o.get("toUuid").getAsString())).orElse(null);
        } else if (o.has("toName")) {
            target = core.platform().findPlayerByName(o.get("toName").getAsString()).orElse(null);
        }
        if (target == null) {
            return;
        }
        core.getMessageService().sendKey(target, "pm-received",
                Map.of("sender", fromName, "message", message));
        UUID fromUuid = null;
        if (o.has("fromUuid")) {
            fromUuid = UUID.fromString(o.get("fromUuid").getAsString());
            replyTargets.put(target.getUuid(), fromUuid);
        }
        notifySocialSpies(fromUuid, fromName, target, message);
        core.getEventBus().publish(new org.hyzionstudios.mysticessentials.api.event.PrivateMessageEvent(
                fromUuid, fromName, target.getUuid(), target.getUsername(), message, true));
    }

    private void notifySocialSpies(UUID fromUuid, String fromName, PlayerRef target, String message) {
        if (!config.socialSpyEnabled) {
            return;
        }
        for (PlayerRef spy : core.platform().onlinePlayers()) {
            if (spy.getUuid().equals(fromUuid) || spy.getUuid().equals(target.getUuid())) {
                continue;
            }
            if (allows(spy, config.socialSpyPermission) && !allows(target, config.socialSpyExemptPermission)) {
                core.getMessageService().sendKey(spy, "pm-spy", Map.of(
                        "sender", fromName,
                        "target", target.getUsername(),
                        "message", message));
            }
        }
    }

    private boolean allows(PlayerRef player, String permission) {
        return permission == null || permission.isBlank() || player.hasPermission(permission);
    }

    private SuggestionProvider onlinePlayerSuggestions() {
        return (commandSender, input, index, result) -> {
            for (PlayerRef player : core.vanish().visiblePlayers(commandSender.getUuid())) {
                result.suggest(player.getUsername());
            }
        };
    }

    private final class MessageCommand extends MysticCommand {
        private final RequiredArg<String> targetName = withRequiredArg("player", "Target player", ArgTypes.STRING)
                .suggest(onlinePlayerSuggestions());
        private final RequiredArg<String> message =
                withRequiredArg("message", "Message", ArgTypes.GREEDY_STRING);

        MessageCommand() {
            super(PrivateMessagingSubModule.this.core, "msg", "Send a private message.");
            addAliases("tell", "w", "whisper");
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            if (!sender.hasPermission(config.messagePermission)) {
                sender.replyKey("pm-no-permission");
                return;
            }
            String name = sender.get(targetName);
            String body = sender.get(message);
            // Vanished players are treated as offline for senders who cannot see
            // them, so /msg does not leak their presence.
            Optional<PlayerRef> target = core.platform().findPlayerByName(name)
                    .filter(ref -> core.vanish().canSee(sender.uuid(), ref.getUuid()));
            if (target.isPresent()) {
                privateMessage(sender.uuid(), target.get().getUuid(), body);
                return;
            }
            if (config.allowCrossServer && core.redis().isEnabled()) {
                publishPm(sender.uuid().toString(), sender.name(), null, name, chat.preparePlayerMessage(
                        sender.player().orElse(null), body));
                echoToSender(sender.uuid(), name, body);
                return;
            }
            if (config.offlineToMail && core.getMailService() != null) {
                core.getPlayerProfileService().resolveUuid(name).thenAccept(found -> {
                    if (found.isPresent()) {
                        privateMessage(sender.uuid(), found.get(), body);
                    } else {
                        sender.replyKey("player-not-found");
                    }
                });
                return;
            }
            sender.replyKey("player-not-found");
        }
    }

    private final class ReplyCommand extends MysticCommand {
        private final RequiredArg<String> message =
                withRequiredArg("message", "Message", ArgTypes.GREEDY_STRING);

        ReplyCommand() {
            super(PrivateMessagingSubModule.this.core, "reply", "Reply to your last private message.");
            addAliases("r");
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            if (!sender.hasPermission(config.replyPermission)) {
                sender.replyKey("pm-reply-no-permission");
                return;
            }
            UUID target = replyTargets.get(sender.uuid());
            if (target == null) {
                sender.replyKey("pm-reply-none");
                return;
            }
            privateMessage(sender.uuid(), target, sender.get(message));
        }
    }
}
