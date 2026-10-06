package org.hyzionstudios.mysticessentials.modules.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hyzionstudios.mysticessentials.api.chat.ChatDeliveryResult;
import org.hyzionstudios.mysticessentials.api.chat.ChatMute;
import org.hyzionstudios.mysticessentials.api.event.ChatMessagePublishedEvent;
import org.hyzionstudios.mysticessentials.api.mention.MentionScopeProvider;
import org.hyzionstudios.mysticessentials.api.model.PlayerProfile;
import org.hyzionstudios.mysticessentials.api.service.ChatService;
import org.hyzionstudios.mysticessentials.api.voice.ChannelVoicePresenceProvider;
import org.hyzionstudios.mysticessentials.core.integration.ManagedAccountsBridge;
import org.hyzionstudios.mysticessentials.core.message.MessageServiceImpl;
import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;
import org.hyzionstudios.mysticessentials.core.notification.NotificationServiceImpl;
import org.hyzionstudios.mysticessentials.modules.chat.itemlink.ItemLinkSubModule;
import org.hyzionstudios.mysticessentials.modules.chat.itemlink.ItemSnapshot;
import org.hyzionstudios.mysticessentials.modules.chat.mention.MentionSubModule;

import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.registry.Registration;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Root chat module. Owns the original public chat event pipeline and delegates
 * private messaging and channel routing to focused submodules.
 */
public final class ChatModule extends AbstractMysticModule implements ChatService {

    private static final Pattern PLAIN_URL = Pattern.compile("(?i)(?<!:)\\bhttps?://[^\\s<>]+");

    private ChatConfig config;
    private PrivateMessagingSubModule privateMessaging;
    private ChannelsSubModule channels;
    private ItemLinkSubModule itemLinks;
    private MentionSubModule mentions;
    /** The chat pipeline listener; registered at a set priority, so tracked here rather than by the base class. */
    private Registration chatListener;

    public ChatModule() {
        super("chat", "Chat", "1.0.0");
    }

    @Override
    public void onEnable() {
        config = core.configManager().loadModuleConfig(id(), ChatConfig.class, new ChatConfig());
        normalizeConfig();
        privateMessaging = new PrivateMessagingSubModule(core, this);
        channels = new ChannelsSubModule(core, this);

        itemLinks = new ItemLinkSubModule(core, core.itemInspection());
        mentions = new MentionSubModule(core);

        privateMessaging.enable(config.privateMessaging, this::registerCommand);
        channels.enable(config.channels, this::registerCommand);
        itemLinks.enable(this::registerCommand);
        mentions.enable(this::registerCommand);
        registerEvent(PlayerDisconnectEvent.class, event -> {
            itemLinks.invalidate(event.getPlayerRef().getUuid());
            mentions.invalidate(event.getPlayerRef().getUuid());
        });

        if (config.formatChat) {
            // LATE, so NORMAL handlers that cancel chat (the tutorial chat block,
            // other plugins) run first: the relay, the publish hook and mention
            // pings all happen inside the pipeline and must never see a blocked line.
            chatListener = core.plugin().getEventRegistry().registerAsyncGlobal(EventPriority.LATE,
                    PlayerChatEvent.class, future -> future.thenApply(this::applyChatPipeline));
        }
        log("Enabled chat submodules: privateMessaging=" + config.privateMessaging.enabled
                + ", channels=" + config.channels.enabled);
    }

    @Override
    public void onReload() {
        config = core.configManager().loadModuleConfig(id(), ChatConfig.class, new ChatConfig());
        normalizeConfig();
        if (privateMessaging != null) {
            privateMessaging.reload(config.privateMessaging);
        }
        if (channels != null) {
            channels.reload(config.channels);
        }
        if (itemLinks != null) {
            itemLinks.reload();
        }
        if (mentions != null) {
            mentions.reload();
        }
    }

    private void normalizeConfig() {
        ChatConfig defaults = new ChatConfig();
        if (config == null) {
            config = defaults;
            return;
        }
        if (config.defaultFormat == null) {
            config.defaultFormat = defaults.defaultFormat;
        }
        config.defaultFormat = preferDisplayName(config.defaultFormat);
        if (config.deliveryFormat == null || config.deliveryFormat.isBlank()) {
            config.deliveryFormat = defaults.deliveryFormat;
        }
        config.deliveryFormat = preferDisplayName(config.deliveryFormat);
        if (config.autoLinkPlainUrls == null) {
            config.autoLinkPlainUrls = defaults.autoLinkPlainUrls;
        }
        if (config.formats == null) {
            config.formats = defaults.formats;
        } else {
            config.formats.forEach(format -> {
                if (format != null) {
                    format.format = preferDisplayName(format.format);
                }
            });
        }
        if (config.messageColorPermissions == null) {
            config.messageColorPermissions = defaults.messageColorPermissions;
        } else {
            defaults.messageColorPermissions.forEach(config.messageColorPermissions::putIfAbsent);
        }
        if (config.privateMessaging == null) {
            config.privateMessaging = defaults.privateMessaging;
        }
        if (config.channels == null) {
            config.channels = defaults.channels;
        } else if (config.channels.channels == null) {
            config.channels.channels = defaults.channels.channels;
        }
        if (config.channels.defaultSpeak == null || config.channels.defaultSpeak.isBlank()) {
            config.channels.defaultSpeak = defaults.channels.defaultSpeak;
        }
        if (config.channels.channels != null) {
            config.channels.channels.forEach(channel -> {
                channel.format = preferDisplayName(channel.format);
                if (channel.groupFormats != null) {
                    channel.groupFormats.replaceAll((group, format) -> preferDisplayName(format));
                }
            });
        }
    }

    private static String preferDisplayName(String format) {
        return format == null ? null : format.replace("{player_name}", "{display_name}");
    }

    @Override
    public void onDisable() {
        if (chatListener != null) {
            try {
                chatListener.unregister();
            } catch (Throwable ignored) {
                // One-shot handle; already gone or engine shutting down.
            }
            chatListener = null;
        }
        if (privateMessaging != null) {
            privateMessaging.disable();
        }
        if (channels != null) {
            channels.disable();
        }
    }

    // ----- Chat formatting ---------------------------------------------------

    /**
     * The chat pipeline: sanitize, tokenize item links, route to a channel,
     * detect mentions, then render.
     *
     * <p>The ordering is load-bearing. Item links are tokenized <b>before</b>
     * mention detection, so an {@code @} inside an item's name or metadata cannot
     * be mistaken for a mention — by that point the item is an opaque token.
     * Routing runs before mentions so the mention scan only sees the players who
     * will actually receive the line. And nothing is turned into markup until the
     * final render step, so every intermediate consumer — the relay, the publish
     * hook, the console echo — sees inert tokens rather than raw
     * {@code <link>} tags.</p>
     */
    private PlayerChatEvent applyChatPipeline(PlayerChatEvent event) {
        if (event.isCancelled()) {
            return event;
        }
        PlayerRef sender = event.getSender();
        String prepared = preparePlayerMessage(sender, event.getContent());

        // Item tags become structured tokens, not markup. The captured snapshot,
        // if any, feeds each recipient's recent-links history below.
        ItemSnapshot pendingSnapshot = null;
        if (itemLinks != null) {
            ItemLinkSubModule.ExpandResult expanded =
                    itemLinks.expand(sender, prepared, senderChannelName(sender));
            prepared = expanded.content();
            pendingSnapshot = expanded.snapshot();
        }
        event.setContent(prepared);

        if (channels != null) {
            event = channels.route(event);
        }
        if (event.isCancelled()) {
            return event;
        }

        List<PlayerRef> recipients = recipients(event, sender);
        MentionSubModule.Result mentionResult = mentions == null
                ? null
                : mentions.process(sender, event.getContent(), senderChannelName(sender), recipients);
        if (mentionResult != null) {
            event.setContent(mentionResult.content());
        }

        if (pendingSnapshot != null && itemLinks != null) {
            itemLinks.recordHistory(pendingSnapshot, recipients);
        }
        // External consumers get the plain-text rendering; raw markup and internal
        // token syntax never leave this module.
        publishChatMessageEvent(sender, plainTextOf(event.getContent()));

        if (mentionResult != null && mentionResult.perViewer()) {
            deliverPerViewer(event, sender, recipients, mentionResult);
        } else {
            Set<UUID> mentioned = mentionResult == null ? Set.of() : mentionResult.mentioned();
            event.setFormatter((from, content) -> renderChatLine(from, content, null, mentioned));
        }

        if (mentionResult != null && mentionResult.hasMentions() && mentions != null) {
            mentions.notifyMentioned(sender, mentionResult.mentioned(),
                    senderChannelName(sender), plainTextOf(event.getContent()));
        }
        return event;
    }

    /**
     * Renders and sends the line once per recipient, so only the mentioned player
     * sees their name highlighted.
     *
     * <p>The engine formats a chat line once and sends the same {@link Message} to
     * every target, so per-viewer highlighting is only possible by taking over
     * delivery: targets are cleared and each recipient is sent their own render.
     * The formatter is still set to the neutral form because the server console
     * echo runs through it before the (now empty) target loop.</p>
     */
    private void deliverPerViewer(PlayerChatEvent event, PlayerRef sender,
            List<PlayerRef> recipients, MentionSubModule.Result mentionResult) {
        String content = event.getContent();
        Set<UUID> mentioned = mentionResult.mentioned();
        event.setFormatter((from, text) -> renderChatLine(from, text, null, mentioned));
        event.setTargets(List.of());
        for (PlayerRef recipient : recipients) {
            if (recipient == null) {
                continue;
            }
            try {
                recipient.sendMessage(
                        renderChatLine(sender, content, recipient.getUuid(), mentioned));
            } catch (Throwable t) {
                log("Per-viewer chat delivery failed for " + recipient.getUsername() + ": " + t);
            }
        }
    }

    /** The token-free, markup-free rendering handed to logs, relays, and bridges. */
    String plainTextOf(String content) {
        return ChatTokens.toPlainText(content,
                id -> itemLinks == null ? null : itemLinks.plainLabel(id));
    }

    /** Origin-server-only publish hook for external bridges (see ChatMessagePublishedEvent). */
    private void publishChatMessageEvent(PlayerRef sender, String content) {
        ChatConfig.Channel channel = channels == null
                ? null
                : channels.activeChannelFor(sender).orElse(null);
        UUID uuid = sender.getUuid();
        String primaryGroup = orEmpty(core.getPermissionService().primaryGroup(uuid));
        String rankPrefix = orEmpty(core.getPermissionService().prefix(uuid));
        core.getEventBus().publish(new ChatMessagePublishedEvent(
                uuid,
                sender.getUsername(),
                displayNameOf(sender),
                channel != null ? channel.id : "global",
                channel != null ? channels.displayNameOf(channel) : "global",
                content,
                channel != null && channel.crossServer,
                primaryGroup,
                rankPrefix));
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Recipients of a routed chat event (routed targets, or all online), including the sender. */
    private List<PlayerRef> recipients(PlayerChatEvent event, PlayerRef sender) {
        List<PlayerRef> targets = event.getTargets();
        Map<UUID, PlayerRef> byUuid = new LinkedHashMap<>();
        Collection<PlayerRef> source = targets != null
                ? targets : core.platform().onlinePlayers();
        for (PlayerRef target : source) {
            if (target != null) {
                byUuid.put(target.getUuid(), target);
            }
        }
        if (sender != null) {
            byUuid.put(sender.getUuid(), sender);
        }
        return new ArrayList<>(byUuid.values());
    }

    private String senderChannelName(PlayerRef sender) {
        if (channels == null) {
            return "Global";
        }
        return channels.displayNameFor(sender.getUuid())
                .orElse(channels.currentChannel(sender.getUuid()));
    }

    String preparePlayerMessage(PlayerRef sender, String raw) {
        // Strip any token delimiter the player typed before anything else runs, so
        // their text can never be mistaken for markup this module emitted.
        String result = sanitizeColors(sender, ChatTokens.sanitizeInput(raw));
        return enforceLength(result);
    }

    /**
     * Turns structured tokens into the markup a client renders. Every token has a
     * defined rendering and every failure has a clean fallback, so no code path
     * here can emit a raw tag or an unresolved token into chat.
     */
    private String expandTokens(String message, UUID viewer, Set<UUID> mentioned) {
        if (!ChatTokens.hasTokens(message)) {
            return message;
        }
        return ChatTokens.expand(message, token -> {
            if (token.isItem()) {
                return itemLinks == null ? null : itemLinks.renderToken(token.value());
            }
            if (token.isMention() && mentions != null) {
                return viewer == null
                        ? mentions.renderMentionNeutral(token.value())
                        : mentions.renderMention(token.value(), viewer, mentioned);
            }
            return null;
        });
    }

    /**
     * Renders the finished chat line for one viewer.
     *
     * <p>This is the <b>only</b> place structured tokens become client-visible
     * markup. {@code viewer} is null for the shared/console render; when present
     * it selects the mention highlight, which is why a mentioned player sees
     * {@code @Aether} while everybody else sees {@code Aether}.</p>
     */
    private Message renderChatLine(PlayerRef sender, String content, UUID viewer,
            Set<UUID> mentioned) {
        UUID uuid = sender.getUuid();
        String template = channels == null
                ? resolveFormat(uuid)
                : channels.formatFor(uuid).orElse(resolveFormat(uuid));
        // {display_name} honours a nickname set by the Nick module (profile
        // metadata), falling back to the real username.
        String displayName = displayNameOf(sender);
        String channelName = channels == null
                ? "global"
                : channels.displayNameFor(uuid).orElse(channels.currentChannel(uuid));
        String message = content == null ? "" : content;
        if (Boolean.TRUE.equals(config.autoLinkPlainUrls) && allows(sender, config.autoLinkPermission)) {
            message = autoLinkPlainUrls(message);
        }
        message = expandTokens(message, viewer, mentioned);
        // The player message and the player-chosen nickname are substituted after
        // placeholder resolution so player content is never parsed for
        // placeholders (design bible §17.3).
        String finalMessage = message;
        UnaryOperator<String> literalPipe = literal -> core.getMessageService()
                .resolvePlaceholders(uuid, literal
                        .replace("{player_name}", sender.getUsername())
                        .replace("{channel}", channelName))
                .replace("{display_name}", displayName)
                .replace("{message}", finalMessage);

        return core.getMessageService().colorize(literalPipe.apply(template));
    }

    private String displayNameOf(PlayerRef sender) {
        return core.getPlayerProfileService().getCached(sender.getUuid())
                .map(p -> p.getMetadata().get("nickname"))
                .filter(nick -> nick != null && !nick.isBlank())
                .map(ChatModule::colorTagsOnly)
                .orElse(sender.getUsername());
    }

    /**
     * Nicknames stored before the Nick module filtered them may carry link,
     * translation or other tags; only colour tags are rendered into chat lines.
     */
    private static String colorTagsOnly(String nickname) {
        return nickname.replaceAll("(?i)<(?!/?(?:(?:c|color):)?#[0-9a-f]{3}(?:[0-9a-f]{3})?>)[^>]*>", "");
    }

    String sanitizeColors(PlayerRef sender, String content) {
        Map<String, String> perms = config.messageColorPermissions;
        boolean legacy = allows(sender, perms.get("legacy"));
        boolean hex = allows(sender, perms.get("hex"));
        boolean gradient = allows(sender, perms.get("gradient"));
        boolean rainbow = allows(sender, perms.get("rainbow"));
        boolean minimessage = allows(sender, perms.get("minimessage"));
        boolean links = allows(sender, perms.get("links"));
        return ChatColors.sanitize(content, legacy, hex, gradient, rainbow, minimessage, links);
    }

    private boolean allows(PlayerRef sender, String permission) {
        return sender == null || permission == null || permission.isBlank() || sender.hasPermission(permission);
    }

    private String enforceLength(String value) {
        if (value == null || config.maxMessageLength <= 0) {
            return value;
        }
        int max = config.maxMessageLength;
        if (value.codePointCount(0, value.length()) <= max) {
            return value;
        }
        int end = value.offsetByCodePoints(0, max);
        return value.substring(0, end);
    }

    private String autoLinkPlainUrls(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        Matcher matcher = PLAIN_URL.matcher(value);
        StringBuilder result = new StringBuilder(value.length());
        while (matcher.find()) {
            String url = trimTrailingUrlPunctuation(matcher.group());
            String trailing = matcher.group().substring(url.length());
            matcher.appendReplacement(result, Matcher.quoteReplacement("<link:" + url + ">" + url + "</link>" + trailing));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String trimTrailingUrlPunctuation(String url) {
        while (!url.isEmpty() && ".,;:!?)\"]}".indexOf(url.charAt(url.length() - 1)) >= 0) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * Registers the voice-presence adapter used by the channel roster for live
     * speaking/mute indicators (design bible §11.4). A voice mod or the MysticIdentity
     * Discord bridge calls this; passing {@code null} restores the no-op provider.
     */
    public void registerVoicePresenceProvider(ChannelVoicePresenceProvider provider) {
        channels.setVoicePresenceProvider(provider);
    }

    // ----- ChatService -------------------------------------------------------

    @Override
    public String resolveFormat(UUID player) {
        if (config != null && config.formats != null) {
            return config.formats.stream()
                    .filter(f -> f.format != null)
                    .filter(f -> player != null && (f.permission == null
                            || core.getPermissionService().has(player, f.permission)))
                    .max(Comparator.comparingInt(f -> f.priority))
                    .map(f -> f.format)
                    .orElse(config.defaultFormat);
        }
        return config != null ? config.defaultFormat : "{player_name} &8» &f{message}";
    }

    @Override
    public CompletableFuture<Boolean> privateMessage(UUID from, UUID to, String message) {
        return privateMessaging == null
                ? CompletableFuture.completedFuture(false)
                : privateMessaging.privateMessage(from, to, message);
    }

    @Override
    public String currentChannel(UUID player) {
        return channels == null ? "global" : channels.currentChannel(player);
    }

    @Override
    public boolean setChannel(UUID player, String channelId) {
        return channels != null && channels.setChannel(player, channelId);
    }

    @Override
    public boolean createTemporaryChannel(UUID owner, String channelId, String permissionGate) {
        return channels != null && channels.createTemporaryChannel(owner, channelId, permissionGate);
    }

    @Override
    public boolean broadcastToChannel(String channelId, String senderName, String content) {
        return channels != null && channels.broadcastExternal(channelId, senderName, content, null);
    }

    @Override
    public boolean broadcastToChannel(String channelId, String senderName, String content, String format) {
        return channels != null && channels.broadcastExternal(channelId, senderName, content, format);
    }

    @Override
    public Set<String> temporaryChannelIds() {
        return channels == null ? Set.of() : channels.temporaryChannelIds();
    }

    // ----- Mutes, ignores and delivery for other mods ---------------------------

    @Override
    public Optional<ChatMute> activeMute(UUID player) {
        return core.moderation().activeMute(player);
    }

    @Override
    public Optional<ChatMute> activeMute(UUID player, String channelId) {
        Optional<ChatMute> serverMute = activeMute(player);
        if (serverMute.isPresent() || channels == null || player == null) {
            return serverMute;
        }
        return channels.channelMute(channelId, player);
    }

    @Override
    public boolean isIgnoring(UUID recipient, UUID sender) {
        NotificationServiceImpl notifications = core.notifications();
        if (notifications == null || recipient == null || sender == null || recipient.equals(sender)) {
            return false;
        }
        // The block list holds names, as the /mentions settings store them.
        String senderName = core.platform().findPlayer(sender).map(PlayerRef::getUsername)
                .or(() -> core.getPlayerProfileService().getCached(sender).map(PlayerProfile::getUsername))
                .orElse(null);
        return notifications.preferences(recipient).blocks(senderName);
    }

    @Override
    public ChatDeliveryResult deliver(UUID sender, Collection<UUID> recipients, String channelLabel,
            String message, String format) {
        if (config == null) {
            return new ChatDeliveryResult(ChatDeliveryResult.Status.UNAVAILABLE, 0, 0, "");
        }
        PlayerRef senderRef = core.platform().findPlayer(sender).orElse(null);
        ChatMute mute = senderRef == null ? null : activeMute(sender).orElse(null);
        String prepared = senderRef == null ? "" : preparePlayerMessage(senderRef, message);
        ChatDelivery.Plan plan = ChatDelivery.plan(sender, recipients, mute,
                prepared == null || prepared.isBlank(),
                uuid -> core.platform().findPlayer(uuid).isPresent(),
                // A managed child's policy (MysticIdentity) is asked per listener, as for
                // channel lines from another server.
                (recipient, from) -> isIgnoring(recipient, from)
                        || !core.managedAccounts().allowsInteraction(from, recipient,
                                ManagedAccountsBridge.TEXT_PUBLIC));
        switch (plan.status()) {
            case DELIVERED, SHADOW_MUTED -> {
                // Sent below.
            }
            case MUTED -> {
                core.getMessageService().sendKey(senderRef, "chat-you-muted", Map.of("reason", mute.reason()));
                return new ChatDeliveryResult(plan.status(), 0, plan.skipped(), mute.reason());
            }
            default -> {
                return new ChatDeliveryResult(plan.status(), 0, plan.skipped(), "");
            }
        }

        String label = colorTagsOnly(ChatTokens.sanitizeInput(channelLabel == null ? "" : channelLabel));
        Message line = renderDeliveredLine(senderRef, label, prepared, format);
        int delivered = 0;
        int failed = 0;
        for (UUID uuid : plan.recipients()) {
            PlayerRef recipient = uuid.equals(sender) ? senderRef : core.platform().findPlayer(uuid).orElse(null);
            if (recipient == null) {
                failed++;
                continue;
            }
            try {
                recipient.sendMessage(line);
                delivered++;
            } catch (Throwable t) {
                failed++;
                log("Chat delivery failed for " + recipient.getUsername() + ": " + t);
            }
        }
        // The console echo public chat gets from the engine.
        log("[" + label + "] " + senderRef.getUsername() + ": " + plainTextOf(prepared)
                + (plan.status() == ChatDeliveryResult.Status.SHADOW_MUTED ? " (shadow-muted)" : ""));
        return new ChatDeliveryResult(plan.status(), delivered, plan.skipped() + failed, "");
    }

    /**
     * Renders a line handed over by {@link #deliver}. As in public chat, the player's
     * text, their nickname and the caller's label are filled in after placeholder
     * resolution, so none of them is ever parsed for placeholders (design bible §17.3).
     */
    private Message renderDeliveredLine(PlayerRef sender, String label, String content, String format) {
        String template = format == null || format.isBlank() ? config.deliveryFormat : format;
        String message = content;
        if (Boolean.TRUE.equals(config.autoLinkPlainUrls) && allows(sender, config.autoLinkPermission)) {
            message = autoLinkPlainUrls(message);
        }
        UUID uuid = sender.getUuid();
        String rendered = MessageServiceImpl.fillParams(template, Map.of(
                        "player_name", sender.getUsername(),
                        "display_name", displayNameOf(sender),
                        "channel", label,
                        "message", message),
                text -> core.getMessageService().resolvePlaceholders(uuid, text));
        return core.getMessageService().colorize(rendered);
    }

    // ----- Mention scopes ----------------------------------------------------

    @Override
    public void registerMentionScope(MentionScopeProvider provider) {
        if (mentions != null) {
            mentions.registerScope(provider);
        }
    }

    @Override
    public boolean unregisterMentionScope(String scopeId) {
        return mentions != null && mentions.unregisterScope(scopeId);
    }

    @Override
    public List<MentionScopeProvider> mentionScopes() {
        return mentions == null ? List.of() : mentions.availableScopes();
    }
}
