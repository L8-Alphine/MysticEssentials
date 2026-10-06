package org.hyzionstudios.mysticessentials.modules.chat;

import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.hyzionstudios.mysticessentials.api.event.PrivateMessageEvent;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.integration.ManagedAccountsBridge;
import org.hyzionstudios.mysticessentials.core.network.NetworkPlayerService.NetworkPlayer;
import org.hyzionstudios.mysticessentials.api.notification.Notification;
import org.hyzionstudios.mysticessentials.api.notification.NotificationAudience;
import org.hyzionstudios.mysticessentials.api.notification.NotificationCategory;
import org.hyzionstudios.mysticessentials.api.notification.NotificationPriority;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.platform.command.MysticArgTypes;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/** Private messaging, reply targets, social spy, Redis relay, and offline-mail fallback. */
public final class PrivateMessagingSubModule {

    private static final String CHANNEL_PM = "pm";

    private final MysticCore core;
    private final ChatModule chat;
    private final Map<UUID, UUID> replyTargets = new ConcurrentHashMap<>();
    private final Consumer<String> redisHandler = this::handleRemotePm;

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
        core.redis().subscribe(CHANNEL_PM, redisHandler);
    }

    public void reload(ChatConfig.PrivateMessaging config) {
        this.config = config == null ? new ChatConfig.PrivateMessaging() : config;
    }

    public void disable() {
        core.redis().unsubscribe(CHANNEL_PM, redisHandler);
        replyTargets.clear();
    }

    public CompletableFuture<Boolean> privateMessage(UUID from, UUID to, String message) {
        return privateMessage(from, to, message, false);
    }

    /**
     * @param hideUnseen treat a local target the sender cannot see (vanish) as offline,
     *                   for name-resolved sends that must not reveal a vanished player
     */
    private CompletableFuture<Boolean> privateMessage(UUID from, UUID to, String message, boolean hideUnseen) {
        if (!config.enabled) {
            return CompletableFuture.completedFuture(false);
        }
        Optional<PlayerRef> target = core.platform().findPlayer(to)
                .filter(ref -> !hideUnseen || core.vanish().canSee(from, ref.getUuid()));
        Optional<PlayerRef> sender = core.platform().findPlayer(from);
        // A managed child's policy (MysticIdentity) decides whether these two may message
        // at all; guardians and trusted staff are exempt inside that answer. Asked here so
        // /msg, /reply and API callers all refuse the same way, before any delivery path.
        if (!core.managedAccounts().allowsInteraction(from, to,
                ManagedAccountsBridge.TEXT_PRIVATE)) {
            sender.ifPresent(ref -> core.getMessageService().sendKey(ref, "pm-blocked",
                    Map.of("target", target.map(PlayerRef::getUsername).orElse("that player"))));
            return CompletableFuture.completedFuture(false);
        }
        String fromName = sender.map(PlayerRef::getUsername).orElse("Server");
        String prepared = sender.map(ref -> chat.preparePlayerMessage(ref, message)).orElse(message);
        if (target.isPresent()) {
            deliverLocalPm(from, fromName, target.get(), prepared);
            return CompletableFuture.completedFuture(true);
        }
        // Elsewhere on the network: relay by UUID. The Redis roster says whether the
        // player is online at all, so a truly offline player falls through to mail
        // instead of a relay nobody receives.
        NetworkPlayer remote = remotePlayer(to).orElse(null);
        if (remote != null) {
            publishPm(from.toString(), fromName, to.toString(), null, prepared);
            echoToSender(from, remote.username(), prepared);
            return CompletableFuture.completedFuture(true);
        }
        if (config.offlineToMail && core.getMailService() != null) {
            return core.getMailService().send(from, fromName, to, prepared).thenApply(ignored -> true);
        }
        return CompletableFuture.completedFuture(false);
    }

    /** A player online on another network server (never a remotely vanished one). */
    private Optional<NetworkPlayer> remotePlayer(UUID uuid) {
        if (!config.allowCrossServer || !core.redis().isEnabled() || core.networkPlayers() == null) {
            return Optional.empty();
        }
        return core.networkPlayers().find(uuid)
                .filter(player -> !player.local(core.networkPlayers().localServerId()))
                .filter(player -> !player.vanished());
    }

    private Optional<NetworkPlayer> remotePlayerByName(String username) {
        if (!config.allowCrossServer || !core.redis().isEnabled() || core.networkPlayers() == null) {
            return Optional.empty();
        }
        return core.networkPlayers().findByName(username)
                .filter(player -> !player.local(core.networkPlayers().localServerId()))
                .filter(player -> !player.vanished());
    }

    private void deliverLocalPm(UUID from, String fromName, PlayerRef target, String message) {
        notifyPrivateMessage(target, fromName, message);
        replyTargets.put(target.getUuid(), from);
        core.platform().findPlayer(from).ifPresent(ref -> {
            core.getMessageService().sendKey(ref, "pm-sent",
                    Map.of("target", target.getUsername(), "message", message));
            replyTargets.put(from, target.getUuid());
        });
        notifySocialSpies(from, fromName, target, message);
        core.getEventBus().publish(new PrivateMessageEvent(
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
        if (!config.enabled || !config.allowCrossServer) {
            return;
        }
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
        UUID fromUuid = null;
        if (o.has("fromUuid")) {
            fromUuid = UUID.fromString(o.get("fromUuid").getAsString());
        }
        // The origin server may not know this child's policy; the server the child is on
        // does, so the pair is judged again here. Dropped quietly: the sender already saw
        // their echo on their own server.
        if (!core.managedAccounts().allowsInteraction(fromUuid, target.getUuid(),
                ManagedAccountsBridge.TEXT_PRIVATE)) {
            return;
        }
        notifyPrivateMessage(target, fromName, message);
        if (fromUuid != null) {
            replyTargets.put(target.getUuid(), fromUuid);
        }
        notifySocialSpies(fromUuid, fromName, target, message);
        core.getEventBus().publish(new PrivateMessageEvent(
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

    private void notifyPrivateMessage(PlayerRef target, String fromName, String privateMessage) {
        Map<String, String> params = Map.of("sender", fromName, "message", privateMessage);
        if (core.notifications() == null) {
            core.getMessageService().sendKey(target, "pm-received", params);
            return;
        }
        String message = core.getMessageService().plainFromKey("pm-received", params);
        core.notifications().send(Notification.builder()
                .category(NotificationCategory.MESSAGE)
                .priority(NotificationPriority.NORMAL)
                .title("Message from " + fromName)
                .subtitle(privateMessage)
                .message(message)
                .showAsTitle(false)
                .source("mysticessentials:private-message")
                .build(), NotificationAudience.player(target.getUuid()));
    }

    private boolean allows(PlayerRef player, String permission) {
        return permission == null || permission.isBlank() || player.hasPermission(permission);
    }

    private final class MessageCommand extends MysticCommand {
        private final RequiredArg<String> targetName = withRequiredArg("player", "Target player",
                MysticArgTypes.NETWORK_PLAYER_NAME);
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
            // Online on another network server: resolve the name through the Redis
            // roster and relay by UUID, the same path /reply and the API use.
            Optional<NetworkPlayer> remote = remotePlayerByName(name);
            if (remote.isPresent()) {
                privateMessage(sender.uuid(), remote.get().uuid(), body);
                return;
            }
            if (config.offlineToMail && core.getMailService() != null) {
                core.getPlayerProfileService().resolveUuid(name).thenAccept(found -> {
                    if (found.isPresent()) {
                        // The name lookup above already hid a vanished player; this
                        // fallback must not deliver to (and confirm) them either.
                        privateMessage(sender.uuid(), found.get(), body, true);
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
