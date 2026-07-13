package org.hyzionstudios.mysticessentials.modules.chat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionProvider;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/** Channel routing, permission gates, temporary channels, and Redis-backed network chat. */
public final class ChannelsSubModule {

    private static final String REDIS_PREFIX = "chat-channel-";
    private static final String TEMP_INDEX_KEY = "chat:temp:index";
    private static final String TEMP_KEY_PREFIX = "chat:temp:";
    private static final Pattern HEX_COLOR = Pattern.compile("(?:&|<|color:|c:)#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})");
    private static final Map<Character, String> LEGACY_COLORS = Map.ofEntries(
            Map.entry('0', "#000000"),
            Map.entry('1', "#0000AA"),
            Map.entry('2', "#00AA00"),
            Map.entry('3', "#00AAAA"),
            Map.entry('4', "#AA0000"),
            Map.entry('5', "#AA00AA"),
            Map.entry('6', "#FFAA00"),
            Map.entry('7', "#AAAAAA"),
            Map.entry('8', "#555555"),
            Map.entry('9', "#5555FF"),
            Map.entry('a', "#55FF55"),
            Map.entry('b', "#55FFFF"),
            Map.entry('c', "#FF5555"),
            Map.entry('d', "#FF55FF"),
            Map.entry('e', "#FFFF55"),
            Map.entry('f', "#FFFFFF"));

    private final MysticCore core;
    private final ChatModule chat;
    private final Map<UUID, String> speakChannels = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> listeningChannels = new ConcurrentHashMap<>();
    private final Map<String, TemporaryChannel> temporaryChannels = new ConcurrentHashMap<>();
    private final Set<String> seenRemoteMessages = ConcurrentHashMap.newKeySet();
    private final Set<String> registeredAliases = ConcurrentHashMap.newKeySet();

    private ChatConfig.Channels config = new ChatConfig.Channels();
    private Map<String, ChatConfig.Channel> configuredChannels = Map.of();
    private Map<String, String> aliasToChannel = Map.of();
    private Consumer<MysticCommand> commandRegistrar;
    private com.hypixel.hytale.registry.Registration disconnectListener;

    public ChannelsSubModule(MysticCore core, ChatModule chat) {
        this.core = core;
        this.chat = chat;
    }

    public void enable(ChatConfig.Channels config, Consumer<MysticCommand> commandRegistrar) {
        this.commandRegistrar = commandRegistrar;
        reload(config);
        commandRegistrar.accept(new ChannelCommand());
        registerConfiguredAliasCommands(commandRegistrar);
        disconnectListener = core.platform().onEvent(PlayerDisconnectEvent.class, (PlayerDisconnectEvent event) ->
                handleDisconnect(event.getPlayerRef()));
    }

    public void reload(ChatConfig.Channels config) {
        this.config = config == null ? new ChatConfig.Channels() : config;
        Map<String, ChatConfig.Channel> next = new HashMap<>();
        Map<String, String> aliases = new HashMap<>();
        if (this.config.channels != null) {
            for (ChatConfig.Channel channel : this.config.channels) {
                if (channel.id == null || channel.id.isBlank()) {
                    continue;
                }
                String id = normalize(channel.id);
                next.put(id, channel);
                indexAliases(aliases, channel);
                if (channel.crossServer && !core.redis().isEnabled()) {
                    core.log(Level.WARNING, "Chat channel '" + channel.id
                            + "' is crossServer=true but Redis is disabled; messages will stay local until Redis works.");
                } else if (channel.crossServer) {
                    core.redis().subscribe(REDIS_PREFIX + redisTopic(channel), this::handleRemoteChannelMessage);
                }
            }
        }
        configuredChannels = next;
        loadRedisTemporaryChannels();
        for (TemporaryChannel temp : temporaryChannels.values()) {
            indexAliases(aliases, temp.channel);
        }
        aliasToChannel = aliases;
    }

    public void disable() {
        if (disconnectListener != null) {
            try {
                disconnectListener.unregister();
            } catch (Throwable ignored) {
                // One-shot handle; already gone or engine shutting down.
            }
            disconnectListener = null;
        }
        speakChannels.clear();
        listeningChannels.clear();
        temporaryChannels.clear();
        seenRemoteMessages.clear();
    }

    public PlayerChatEvent route(PlayerChatEvent event) {
        if (!config.enabled || event.isCancelled()) {
            return event;
        }
        PlayerRef sender = event.getSender();
        ChatConfig.Channel channel = channelForSender(sender).orElse(null);
        if (channel == null || !channel.enabled) {
            core.getMessageService().sendKey(sender, "chat-channel-unavailable");
            event.setCancelled(true);
            return event;
        }
        if (!canSpeak(sender, channel)) {
            core.getMessageService().sendKey(sender, "chat-channel-no-speak");
            event.setCancelled(true);
            return event;
        }
        event.setTargets(localRecipients(channel, sender, event.getTargets()));
        if (channel.crossServer && core.redis().isEnabled()) {
            publishRemote(channel, sender, event.getContent());
        }
        return event;
    }

    public String currentChannel(UUID player) {
        return speakChannels.getOrDefault(player, normalize(config.defaultSpeak));
    }

    public boolean setChannel(UUID player, String channelId) {
        String id = resolveChannelId(channelId);
        if (findChannel(id).isEmpty()) {
            return false;
        }
        speakChannels.put(player, id);
        return true;
    }

    public Optional<String> formatFor(UUID player) {
        return findChannel(currentChannel(player)).map(channel -> formatForGroup(player, channel));
    }

    public Optional<String> displayNameFor(UUID player) {
        return findChannel(currentChannel(player)).map(channel ->
                channel.displayName == null || channel.displayName.isBlank() ? channel.id : channel.displayName);
    }

    // ----- Custom UI support -------------------------------------------------

    /** The channel id the player currently speaks in. */
    public String currentChannelId(PlayerRef player) {
        return currentChannel(player.getUuid());
    }

    /** The display name of the player's current speaking channel. */
    public String currentDisplayName(PlayerRef player) {
        return displayNameFor(player.getUuid()).orElse(currentChannel(player.getUuid()));
    }

    /** Display rows for the channel-browser UI: channels the player may see. */
    public List<ChannelPages.ChannelRow> channelRowsFor(PlayerRef player) {
        List<ChannelPages.ChannelRow> rows = new ArrayList<>();
        String speaking = currentChannel(player.getUuid());
        for (ChatConfig.Channel channel : visibleChannels(player)) {
            String id = normalize(channel.id);
            StringBuilder access = new StringBuilder();
            if (id.equals(speaking)) {
                access.append("speaking ");
            }
            if (isListening(player, channel)) {
                access.append("listening ");
            }
            if (channel.password != null && !channel.password.isBlank()) {
                access.append("locked ");
            }
            rows.add(new ChannelPages.ChannelRow(id, displayName(channel),
                    channel.prefix == null ? "" : channel.prefix,
                    access.toString().trim(),
                    channelColor(channel),
                    temporaryChannels.containsKey(id) ? "Temp Channel" : "Server Channel"));
        }
        return rows;
    }

    /** Opens the channel-browser custom UI page for the player, falling back to the text menu. */
    public void openChannelUi(PlayerRef player) {
        core.log(Level.INFO, "[chat] Opening channel UI for " + player.getUsername());
        if (!core.platform().openPage(player, new ChannelPages.ChannelsPage(core, this, player))) {
            showChannelMenu(player);
        }
    }

    /** Opens the temporary-channel creation custom UI page for the player. */
    public void openTempChannelUi(PlayerRef player) {
        core.platform().openPage(player, new ChannelPages.TempChannelPage(core, this, player));
    }

    /** Opens the manager UI for the player's own temporary channel. */
    public void openTempManageUi(PlayerRef player) {
        core.platform().openPage(player, new ChannelPages.TempChannelManagePage(core, this, player));
    }

    void switchChannelWithFeedback(PlayerRef player, String channelId, String password) {
        SwitchResult result = switchChannel(player, channelId, password);
        ChatConfig.Channel target = findChannel(channelId).orElse(null);
        switch (result) {
            case SWITCHED -> core.getMessageService().sendKey(player, "chat-channel-switched",
                    Map.of("channel", displayName(target)));
            case PASSWORD_REQUIRED -> core.getMessageService().sendKey(player, "chat-channel-password-required");
            case NO_LISTEN_PERMISSION -> core.getMessageService().sendKey(player, "chat-channel-no-listen");
            case NO_SPEAK_PERMISSION -> core.getMessageService().sendKey(player, "chat-channel-no-speak");
            case UNKNOWN -> core.getMessageService().sendKey(player, "chat-channel-unknown");
        }
    }

    void joinChannelWithFeedback(PlayerRef player, String channelId, String password) {
        ChatConfig.Channel target = findChannel(channelId).orElse(null);
        JoinResult result = joinChannel(player, target, password);
        switch (result) {
            case JOINED -> core.getMessageService().sendKey(player, "chat-channel-joined",
                    Map.of("channel", displayName(target)));
            case ALREADY_LISTENING -> core.getMessageService().sendKey(player, "chat-channel-already-listening",
                    Map.of("channel", displayName(target)));
            case PASSWORD_REQUIRED -> core.getMessageService().sendKey(player, "chat-channel-password-required");
            case NO_LISTEN_PERMISSION -> core.getMessageService().sendKey(player, "chat-channel-no-listen");
            case UNKNOWN -> core.getMessageService().sendKey(player, "chat-channel-unknown");
        }
    }

    void leaveChannelWithFeedback(PlayerRef player, String channelId) {
        ChatConfig.Channel target = findChannel(channelId).orElse(null);
        LeaveResult result = leaveChannel(player, target);
        switch (result) {
            case LEFT -> core.getMessageService().sendKey(player, "chat-channel-left",
                    Map.of("channel", displayName(target)));
            case NOT_LISTENING -> core.getMessageService().sendKey(player, "chat-channel-not-listening",
                    Map.of("channel", displayName(target)));
            case CURRENT_CHANNEL -> core.getMessageService().sendKey(player, "chat-channel-current");
            case UNKNOWN -> core.getMessageService().sendKey(player, "chat-channel-unknown");
        }
    }

    /** The temporary channel owned by {@code owner}, if any. */
    public Optional<ChatConfig.Channel> ownedTemporaryChannel(UUID owner) {
        pruneExpired();
        for (TemporaryChannel temp : temporaryChannels.values()) {
            if (owner.equals(temp.owner)) {
                return Optional.of(temp.channel);
            }
        }
        return Optional.empty();
    }

    /** When the owner's temporary channel expires ({@code Instant.MAX} = with the last player). */
    public Optional<Instant> ownedTemporaryChannelExpiry(UUID owner) {
        for (TemporaryChannel temp : temporaryChannels.values()) {
            if (owner.equals(temp.owner)) {
                return Optional.of(temp.expiresAt);
            }
        }
        return Optional.empty();
    }

    /** Updates the password/prefix of the owner's temporary channel. Blank password removes it. */
    public boolean updateTemporaryChannel(UUID owner, String password, String prefix) {
        for (Map.Entry<String, TemporaryChannel> entry : temporaryChannels.entrySet()) {
            TemporaryChannel temp = entry.getValue();
            if (owner.equals(temp.owner)) {
                temp.channel.password = blankToNull(password);
                temp.channel.prefix = temporaryPrefix(entry.getKey(), prefix);
                temp.channel.format = temporaryFormat(entry.getKey(), temp.channel.prefix);
                saveRedisTemporaryChannel(entry.getKey(), temp);
                return true;
            }
        }
        return false;
    }

    /** Closes (deletes) the owner's temporary channel and moves everyone off it. */
    public boolean closeTemporaryChannel(UUID owner) {
        String id = null;
        for (Map.Entry<String, TemporaryChannel> entry : temporaryChannels.entrySet()) {
            if (owner.equals(entry.getValue().owner)) {
                id = entry.getKey();
                break;
            }
        }
        if (id == null) {
            return false;
        }
        temporaryChannels.remove(id);
        if (core.redis().isEnabled()) {
            core.redis().cacheDelete(TEMP_KEY_PREFIX + id);
            saveRedisTemporaryIndex();
        }
        String fallback = normalize(config.defaultSpeak);
        for (Set<String> listening : listeningChannels.values()) {
            listening.remove(id);
        }
        for (Map.Entry<UUID, String> entry : speakChannels.entrySet()) {
            if (id.equals(entry.getValue())) {
                entry.setValue(fallback);
            }
        }
        rebuildAliasIndex();
        return true;
    }

    public boolean createTemporaryChannel(UUID owner, String channelId, String permissionGate) {
        return createTemporaryChannel(owner, channelId, permissionGate, null, null, List.of());
    }

    public boolean createTemporaryChannel(UUID owner, String channelId, String permissionGate, String password,
            String prefix, List<String> aliases) {
        if (!config.enabled || !config.allowTemporaryChannels) {
            return false;
        }
        String id = normalize(channelId);
        if (id.isBlank() || configuredChannels.containsKey(id) || temporaryChannels.containsKey(id)) {
            return false;
        }
        String resolvedPrefix = temporaryPrefix(id, prefix);
        ChatConfig.Channel channel = new ChatConfig.Channel(id, id, "permission",
                temporaryFormat(id, resolvedPrefix));
        channel.prefix = resolvedPrefix;
        channel.password = blankToNull(password);
        channel.aliases = aliases == null ? new ArrayList<>() : new ArrayList<>(aliases);
        channel.joinPermission = blankToNull(permissionGate);
        channel.speakPermission = blankToNull(permissionGate);
        channel.listenPermission = blankToNull(permissionGate);
        channel.moderatorPermission = "mysticessentials.chat.channel." + id + ".moderator";
        Instant expires = core.redis().isEnabled()
                ? Instant.now().plusSeconds(temporaryChannelTtlSeconds())
                : Instant.MAX;
        TemporaryChannel temp = new TemporaryChannel(channel, owner, expires);
        temporaryChannels.put(id, temp);
        speakChannels.put(owner, id);
        listeningChannels(owner).add(id);
        Map<String, String> aliasesNext = new HashMap<>(aliasToChannel);
        indexAliases(aliasesNext, channel);
        aliasToChannel = aliasesNext;
        if (commandRegistrar != null) {
            registerAliasCommands(channel.aliases, commandRegistrar);
        }
        saveRedisTemporaryChannel(id, temp);
        return true;
    }

    private static String temporaryPrefix(String id, String prefix) {
        return blankToNull(prefix) == null ? "&8[&d" + id + "&8]" : prefix;
    }

    private static String temporaryFormat(String id, String prefix) {
        return temporaryPrefix(id, prefix) + " &f{display_name}: &f{message}";
    }

    boolean canCreateTemporaryChannel(PlayerRef player) {
        return config.createTemporaryPermission == null || config.createTemporaryPermission.isBlank()
                || player.hasPermission(config.createTemporaryPermission);
    }

    private Optional<ChatConfig.Channel> channelForSender(PlayerRef sender) {
        String id = currentChannel(sender.getUuid());
        Optional<ChatConfig.Channel> selected = findChannel(id);
        if (selected.isPresent()) {
            return selected;
        }
        return findChannel(config.defaultSpeak);
    }

    private Optional<ChatConfig.Channel> findChannel(String id) {
        pruneExpired();
        String normalized = resolveChannelId(id);
        TemporaryChannel temp = temporaryChannels.get(normalized);
        if (temp != null) {
            return Optional.of(temp.channel);
        }
        return Optional.ofNullable(configuredChannels.get(normalized));
    }

    private List<PlayerRef> localRecipients(ChatConfig.Channel channel, PlayerRef sender, List<PlayerRef> original) {
        List<PlayerRef> source = original == null ? new ArrayList<>(core.platform().onlinePlayers()) : original;
        List<PlayerRef> recipients = new ArrayList<>();
        for (PlayerRef target : source) {
            if (isListening(target, channel) && sameScope(sender, target, channel)) {
                recipients.add(target);
            }
        }
        return recipients;
    }

    private boolean sameScope(PlayerRef sender, PlayerRef target, ChatConfig.Channel channel) {
        String scope = channel.scope == null ? "server" : channel.scope.toLowerCase(Locale.ROOT);
        if ("world".equals(scope)) {
            return sender.getWorldUuid().equals(target.getWorldUuid());
        }
        return true;
    }

    private boolean canSpeak(PlayerRef player, ChatConfig.Channel channel) {
        return has(player, channel.joinPermission) && has(player, channel.speakPermission);
    }

    private boolean canListen(PlayerRef player, ChatConfig.Channel channel) {
        return has(player, channel.joinPermission) && has(player, channel.listenPermission);
    }

    private boolean isListening(PlayerRef player, ChatConfig.Channel channel) {
        return canListen(player, channel) && listeningChannels(player.getUuid()).contains(normalize(channel.id));
    }

    private Set<String> listeningChannels(UUID player) {
        return listeningChannels.computeIfAbsent(player, uuid -> {
            Set<String> joined = ConcurrentHashMap.newKeySet();
            if (config.defaultJoin != null) {
                for (String id : config.defaultJoin) {
                    String normalized = resolveChannelId(id);
                    if (!normalized.isBlank()) {
                        joined.add(normalized);
                    }
                }
            }
            String current = normalize(config.defaultSpeak);
            if (!current.isBlank()) {
                joined.add(current);
            }
            return joined;
        });
    }

    private boolean has(PlayerRef player, String permission) {
        return permission == null || permission.isBlank() || player.hasPermission(permission);
    }

    private void publishRemote(ChatConfig.Channel channel, PlayerRef sender, String content) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("messageId", UUID.randomUUID().toString());
        envelope.addProperty("networkId", core.redis().networkId());
        envelope.addProperty("originServerId", core.redis().serverId());
        envelope.addProperty("channelId", channel.id);
        envelope.addProperty("senderUuid", sender.getUuid().toString());
        envelope.addProperty("senderName", sender.getUsername());
        envelope.addProperty("content", content);
        envelope.addProperty("timestamp", Instant.now().toString());
        core.redis().publish(REDIS_PREFIX + redisTopic(channel), Json.toString(envelope));
    }

    private void handleRemoteChannelMessage(String payload) {
        JsonObject envelope = Json.asObject(Json.parse(payload));
        String messageId = envelope.has("messageId") ? envelope.get("messageId").getAsString() : "";
        if (!messageId.isBlank() && !seenRemoteMessages.add(messageId)) {
            return;
        }
        if (seenRemoteMessages.size() > 2048) {
            seenRemoteMessages.clear();
        }
        String channelId = envelope.has("channelId") ? envelope.get("channelId").getAsString() : "";
        ChatConfig.Channel channel = findChannel(channelId).orElse(null);
        if (channel == null || !channel.enabled) {
            return;
        }
        String senderName = envelope.has("senderName") ? envelope.get("senderName").getAsString() : "Remote";
        String senderUuid = envelope.has("senderUuid") ? envelope.get("senderUuid").getAsString() : "";
        String content = envelope.has("content") ? envelope.get("content").getAsString() : "";
        UUID placeholderContext = parseUuid(senderUuid);
        String line = formatForGroup(placeholderContext, channel)
                .replace("{player_name}", senderName)
                .replace("{display_name}", senderName)
                .replace("{channel}", displayName(channel))
                .replace("{server_id}", envelope.has("originServerId") ? envelope.get("originServerId").getAsString() : "")
                .replace("{message}", content);
        for (PlayerRef recipient : core.platform().onlinePlayers()) {
            if (isListening(recipient, channel)) {
                recipient.sendMessage(core.getMessageService().formatFor(placeholderContext, line));
            }
        }
    }

    private String formatForGroup(UUID player, ChatConfig.Channel channel) {
        if (player != null && channel.groupFormats != null && !channel.groupFormats.isEmpty()) {
            String group = core.getPermissionService().primaryGroup(player);
            if (group != null) {
                String groupFormat = channel.groupFormats.get(group.toLowerCase(Locale.ROOT));
                if (groupFormat != null && !groupFormat.isBlank()) {
                    return groupFormat;
                }
            }
        }
        return channel.format == null ? chat.resolveFormat(player) : channel.format;
    }

    private List<ChatConfig.Channel> visibleChannels(PlayerRef player) {
        pruneExpired();
        List<ChatConfig.Channel> result = new ArrayList<>();
        result.addAll(configuredChannels.values());
        for (TemporaryChannel temp : temporaryChannels.values()) {
            result.add(temp.channel);
        }
        result.removeIf(channel -> !channel.enabled || !canListen(player, channel));
        result.sort(Comparator.comparing(channel -> channel.id));
        return result;
    }

    private String channelColor(ChatConfig.Channel channel) {
        String raw = (channel.prefix == null ? "" : channel.prefix)
                + " " + (channel.format == null ? "" : channel.format);
        Matcher matcher = HEX_COLOR.matcher(raw);
        if (matcher.find()) {
            return normalizeHex(matcher.group(1));
        }
        String first = null;
        for (int i = 0; i + 1 < raw.length(); i++) {
            if (raw.charAt(i) != '&') {
                continue;
            }
            char code = Character.toLowerCase(raw.charAt(i + 1));
            String color = LEGACY_COLORS.get(code);
            if (color == null) {
                continue;
            }
            if (first == null) {
                first = color;
            }
            if ("078f".indexOf(code) < 0) {
                return color;
            }
        }
        return first == null ? "#7a9cc6" : first;
    }

    private static String normalizeHex(String hex) {
        if (hex == null || hex.length() != 3) {
            return "#" + hex;
        }
        return "#" + hex.charAt(0) + hex.charAt(0)
                + hex.charAt(1) + hex.charAt(1)
                + hex.charAt(2) + hex.charAt(2);
    }

    private SwitchResult switchChannel(PlayerRef player, String rawId, String password) {
        String id = resolveChannelId(rawId);
        ChatConfig.Channel channel = findChannel(id).orElse(null);
        if (channel == null || !channel.enabled) {
            return SwitchResult.UNKNOWN;
        }
        if (!canSpeak(player, channel)) {
            return SwitchResult.NO_SPEAK_PERMISSION;
        }
        JoinResult joined = joinChannel(player, channel, password);
        if (joined != JoinResult.JOINED && joined != JoinResult.ALREADY_LISTENING) {
            return joined == JoinResult.PASSWORD_REQUIRED ? SwitchResult.PASSWORD_REQUIRED : SwitchResult.NO_LISTEN_PERMISSION;
        }
        speakChannels.put(player.getUuid(), normalize(channel.id));
        return SwitchResult.SWITCHED;
    }

    private JoinResult joinChannel(PlayerRef player, ChatConfig.Channel channel, String password) {
        if (channel == null || !channel.enabled) {
            return JoinResult.UNKNOWN;
        }
        if (!canListen(player, channel)) {
            return JoinResult.NO_LISTEN_PERMISSION;
        }
        Set<String> listening = listeningChannels(player.getUuid());
        String id = normalize(channel.id);
        if (listening.contains(id)) {
            return JoinResult.ALREADY_LISTENING;
        }
        if (!passwordMatches(channel, password)) {
            return JoinResult.PASSWORD_REQUIRED;
        }
        listening.add(id);
        return JoinResult.JOINED;
    }

    private LeaveResult leaveChannel(PlayerRef player, ChatConfig.Channel channel) {
        if (channel == null || !channel.enabled) {
            return LeaveResult.UNKNOWN;
        }
        String id = normalize(channel.id);
        Set<String> listening = listeningChannels(player.getUuid());
        if (!listening.contains(id)) {
            return LeaveResult.NOT_LISTENING;
        }
        if (id.equals(currentChannel(player.getUuid()))) {
            String fallback = firstSpeakableJoinedChannel(player, id);
            if (fallback == null) {
                return LeaveResult.CURRENT_CHANNEL;
            }
            speakChannels.put(player.getUuid(), fallback);
        }
        listening.remove(id);
        return LeaveResult.LEFT;
    }

    private String firstSpeakableJoinedChannel(PlayerRef player, String excludedId) {
        for (ChatConfig.Channel channel : visibleChannels(player)) {
            String id = normalize(channel.id);
            if (!id.equals(excludedId) && listeningChannels(player.getUuid()).contains(id) && canSpeak(player, channel)) {
                return id;
            }
        }
        return null;
    }

    private boolean passwordMatches(ChatConfig.Channel channel, String supplied) {
        String expected = blankToNull(channel.password);
        return expected == null || expected.equals(supplied);
    }

    private void pruneExpired() {
        Instant now = Instant.now();
        boolean changed = false;
        for (Map.Entry<String, TemporaryChannel> entry : temporaryChannels.entrySet()) {
            if (entry.getValue().expiresAt.isBefore(now)) {
                temporaryChannels.remove(entry.getKey());
                changed = true;
            }
        }
        if (changed) {
            rebuildAliasIndex();
            saveRedisTemporaryIndex();
        }
    }

    private void handleDisconnect(PlayerRef player) {
        UUID leaving = player.getUuid();
        speakChannels.remove(leaving);
        listeningChannels.remove(leaving);
        if (temporaryChannels.isEmpty()) {
            return;
        }
        boolean anyOtherOnline = core.platform().onlinePlayers().stream()
                .anyMatch(online -> !online.getUuid().equals(leaving));
        if (!anyOtherOnline) {
            clearTemporaryChannels();
        }
    }

    private void clearTemporaryChannels() {
        if (temporaryChannels.isEmpty()) {
            return;
        }
        if (core.redis().isEnabled()) {
            List<String> keys = new ArrayList<>();
            keys.add(TEMP_INDEX_KEY);
            for (String id : temporaryChannels.keySet()) {
                keys.add(TEMP_KEY_PREFIX + id);
            }
            core.redis().cacheDelete(keys.toArray(String[]::new));
        }
        temporaryChannels.clear();
        for (Set<String> listening : listeningChannels.values()) {
            listening.removeIf(id -> !configuredChannels.containsKey(id));
        }
        speakChannels.entrySet().removeIf(entry -> !configuredChannels.containsKey(entry.getValue()));
        rebuildAliasIndex();
    }

    private void rebuildAliasIndex() {
        Map<String, String> aliases = new HashMap<>();
        for (ChatConfig.Channel channel : configuredChannels.values()) {
            indexAliases(aliases, channel);
        }
        for (TemporaryChannel temp : temporaryChannels.values()) {
            indexAliases(aliases, temp.channel);
        }
        aliasToChannel = aliases;
    }

    private void loadRedisTemporaryChannels() {
        if (!core.redis().isEnabled()) {
            return;
        }
        String rawIndex = core.redis().cacheGet(TEMP_INDEX_KEY);
        if (rawIndex == null || rawIndex.isBlank()) {
            return;
        }
        try {
            JsonElement parsed = Json.parse(rawIndex);
            if (!parsed.isJsonArray()) {
                return;
            }
            Instant now = Instant.now();
            for (JsonElement element : parsed.getAsJsonArray()) {
                String id = element.isJsonPrimitive() ? normalize(element.getAsString()) : "";
                if (id.isBlank() || configuredChannels.containsKey(id) || temporaryChannels.containsKey(id)) {
                    continue;
                }
                String raw = core.redis().cacheGet(TEMP_KEY_PREFIX + id);
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                TemporaryChannel temp = parseRedisTemporaryChannel(raw);
                if (temp == null || temp.expiresAt.isBefore(now)) {
                    continue;
                }
                temporaryChannels.put(normalize(temp.channel.id), temp);
                if (commandRegistrar != null) {
                    registerAliasCommands(temp.channel.aliases, commandRegistrar);
                }
            }
        } catch (Throwable t) {
            core.log(Level.WARNING, "Failed to load Redis temporary chat channels: " + t.getMessage());
        }
    }

    private TemporaryChannel parseRedisTemporaryChannel(String raw) {
        JsonObject object = Json.asObject(Json.parse(raw));
        JsonElement channelElement = object.get("channel");
        if (channelElement == null || !channelElement.isJsonObject()) {
            return null;
        }
        ChatConfig.Channel channel = Json.fromJson(channelElement, ChatConfig.Channel.class);
        if (channel == null || channel.id == null || channel.id.isBlank()) {
            return null;
        }
        UUID owner = parseUuid(object.has("owner") ? object.get("owner").getAsString() : null);
        Instant expires = Instant.MAX;
        if (object.has("expiresAt") && !object.get("expiresAt").isJsonNull()) {
            expires = Instant.parse(object.get("expiresAt").getAsString());
        }
        return new TemporaryChannel(channel, owner, expires);
    }

    private void saveRedisTemporaryChannel(String id, TemporaryChannel temp) {
        if (!core.redis().isEnabled()) {
            return;
        }
        long ttl = temporaryChannelTtlSeconds();
        JsonObject object = new JsonObject();
        object.add("channel", Json.toTree(temp.channel));
        object.addProperty("owner", temp.owner == null ? null : temp.owner.toString());
        if (!Instant.MAX.equals(temp.expiresAt)) {
            object.addProperty("expiresAt", temp.expiresAt.toString());
        }
        core.redis().cacheSet(TEMP_KEY_PREFIX + id, Json.toString(object), ttl);
        saveRedisTemporaryIndex();
    }

    private void saveRedisTemporaryIndex() {
        if (!core.redis().isEnabled()) {
            return;
        }
        JsonArray array = new JsonArray();
        for (String id : temporaryChannels.keySet()) {
            array.add(id);
        }
        core.redis().cacheSet(TEMP_INDEX_KEY, Json.toString(array), temporaryChannelTtlSeconds());
    }

    private long temporaryChannelTtlSeconds() {
        return Math.max(1, config.temporaryChannelDefaultMinutes) * 60L;
    }

    private void indexAliases(Map<String, String> aliases, ChatConfig.Channel channel) {
        if (channel == null || channel.id == null || channel.id.isBlank()) {
            return;
        }
        String channelId = normalize(channel.id);
        aliases.put(channelId, channelId);
        if (channel.aliases == null) {
            return;
        }
        for (String alias : channel.aliases) {
            String normalized = normalizeAlias(alias);
            if (!normalized.isBlank()) {
                aliases.put(normalized, channelId);
            }
        }
    }

    private void registerConfiguredAliasCommands(Consumer<MysticCommand> registrar) {
        if (registrar == null || config.channels == null) {
            return;
        }
        for (ChatConfig.Channel channel : config.channels) {
            registerAliasCommands(channel.aliases, registrar);
        }
    }

    private void registerAliasCommands(List<String> aliases, Consumer<MysticCommand> registrar) {
        if (aliases == null || registrar == null) {
            return;
        }
        for (String alias : aliases) {
            String normalized = normalizeAlias(alias);
            if (normalized.isBlank() || "channel".equals(normalized) || "ch".equals(normalized)) {
                continue;
            }
            if (registeredAliases.add(normalized)) {
                registrar.accept(new ChannelAliasCommand(normalized));
            }
        }
    }

    private String resolveChannelId(String raw) {
        String normalized = normalizeAlias(raw);
        return aliasToChannel.getOrDefault(normalized, normalized);
    }

    private ChannelInput parseChannelInput(String[] args, int startIndex, boolean allowPassword) {
        if (args.length <= startIndex) {
            return new ChannelInput("", null);
        }
        String full = joinArgs(args, startIndex, args.length);
        if (findChannel(full).isPresent() || !allowPassword || args.length - startIndex == 1) {
            return new ChannelInput(full, null);
        }
        String channel = joinArgs(args, startIndex, args.length - 1);
        if (findChannel(channel).isPresent()) {
            return new ChannelInput(channel, args[args.length - 1]);
        }
        String fallbackPassword = args.length > startIndex + 1 ? args[startIndex + 1] : null;
        return new ChannelInput(args[startIndex], fallbackPassword);
    }

    private static String joinArgs(String[] args, int startInclusive, int endExclusive) {
        StringBuilder joined = new StringBuilder();
        for (int i = startInclusive; i < endExclusive; i++) {
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(args[i]);
        }
        return joined.toString();
    }

    private List<String> parseAliases(String raw) {
        if (raw == null || raw.isBlank() || "-".equals(raw)) {
            return List.of();
        }
        List<String> aliases = new ArrayList<>();
        for (String alias : raw.split(",")) {
            String normalized = normalizeAlias(alias);
            if (!normalized.isBlank()) {
                aliases.add(normalized);
            }
        }
        return aliases;
    }

    private List<String> aliasesFor(ChatConfig.Channel channel) {
        if (channel.aliases == null || channel.aliases.isEmpty()) {
            return List.of();
        }
        List<String> aliases = new ArrayList<>();
        for (String alias : channel.aliases) {
            String normalized = normalizeAlias(alias);
            if (!normalized.isBlank()) {
                aliases.add("/" + normalized);
            }
        }
        return aliases;
    }

    private String commandLink(String label, String command) {
        return "<link:" + command + ">" + label + "</link>";
    }

    private String dashToNull(String value) {
        return value == null || "-".equals(value) ? null : value;
    }

    private String redisTopic(ChatConfig.Channel channel) {
        return channel.redisTopic == null || channel.redisTopic.isBlank() ? channel.id : channel.redisTopic;
    }

    private static String displayName(ChatConfig.Channel channel) {
        return channel.displayName == null || channel.displayName.isBlank() ? channel.id : channel.displayName;
    }

    private static String normalize(String id) {
        return id == null ? "" : id.toLowerCase(Locale.ROOT).trim();
    }

    private static String normalizeAlias(String id) {
        String normalized = normalize(id);
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static UUID parseUuid(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private enum SwitchResult {
        SWITCHED,
        UNKNOWN,
        NO_SPEAK_PERMISSION,
        NO_LISTEN_PERMISSION,
        PASSWORD_REQUIRED
    }

    private enum JoinResult {
        JOINED,
        ALREADY_LISTENING,
        UNKNOWN,
        NO_LISTEN_PERMISSION,
        PASSWORD_REQUIRED
    }

    private enum LeaveResult {
        LEFT,
        UNKNOWN,
        NOT_LISTENING,
        CURRENT_CHANNEL
    }

    private record ChannelInput(String channelId, String password) {
    }

    private record TemporaryChannel(ChatConfig.Channel channel, UUID owner, Instant expiresAt) {
    }

    private SuggestionProvider visibleChannelSuggestions() {
        return (commandSender, input, index, result) -> core.platform().findPlayer(commandSender.getUuid())
                .ifPresent(player -> visibleChannels(player).forEach(channel -> result.suggest(normalize(channel.id))));
    }

    private SuggestionProvider listeningChannelSuggestions() {
        return (commandSender, input, index, result) -> core.platform().findPlayer(commandSender.getUuid())
                .ifPresent(player -> {
                    Set<String> listening = listeningChannels(player.getUuid());
                    visibleChannels(player).stream()
                            .map(channel -> normalize(channel.id))
                            .filter(listening::contains)
                            .forEach(result::suggest);
                });
    }

    private abstract class PublicChannelCommand extends MysticCommand {
        PublicChannelCommand(MysticCore core, String name, String description) {
            super(core, name, description);
        }

        PublicChannelCommand(MysticCore core, String description) {
            super(core, description);
        }

        @Override
        protected boolean canGeneratePermission() {
            return false;
        }
    }

    private final class ChannelCommand extends PublicChannelCommand {
        ChannelCommand() {
            super(ChannelsSubModule.this.core, "channel", "Show, switch, or create chat channels.");
            allowExtraArguments();
            addAliases("ch");
            addSubCommand(new ChannelJoinSubCommand());
            addSubCommand(new ChannelLeaveSubCommand());
            addSubCommand(new ChannelSwitchSubCommand());
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            PlayerRef player = sender.player().orElse(null);
            if (player == null) {
                sender.replyKey("player-only");
                return;
            }
            String[] args = stripCommandToken(sender.args());
            if (args.length == 0 || "ui".equals(normalize(args[0])) || "menu".equals(normalize(args[0]))) {
                openChannelUi(player);
                return;
            }
            String action = normalize(args[0]);
            if ("list".equals(action)) {
                showChannelMenu(player);
                return;
            }
            if ("join".equals(action) || "listen".equals(action)) {
                if (args.length < 2) {
                    sender.replyKey("chat-channel-join-usage");
                    return;
                }
                ChannelInput input = parseChannelInput(args, 1, true);
                replyJoin(sender, player, input.channelId(), input.password());
                return;
            }
            if ("leave".equals(action) || "mute".equals(action) || "unlisten".equals(action)) {
                if (args.length < 2) {
                    sender.replyKey("chat-channel-leave-usage");
                    return;
                }
                replyLeave(sender, player, parseChannelInput(args, 1, false).channelId());
                return;
            }
            if ("switch".equals(action) || "speak".equals(action) || "use".equals(action)) {
                if (args.length < 2) {
                    sender.replyKey("chat-channel-switch-usage");
                    return;
                }
                ChannelInput input = parseChannelInput(args, 1, true);
                replySwitch(sender, player, input.channelId(), input.password());
                return;
            }
            if ("manage".equals(action)) {
                if (ownedTemporaryChannel(sender.uuid()).isEmpty()) {
                    sender.replyKey("chat-channel-no-temp-owned");
                    return;
                }
                openTempManageUi(player);
                return;
            }
            if ("temp".equals(action) || "create".equals(action)) {
                if (!sender.hasPermission(config.createTemporaryPermission)) {
                    sender.replyKey("chat-channel-temp-no-permission");
                    return;
                }
                if (args.length < 2) {
                    sender.replyKey("chat-channel-temp-usage");
                    return;
                }
                String password = args.length >= 3 ? dashToNull(args[2]) : null;
                String prefix = args.length >= 4 ? dashToNull(args[3]) : null;
                List<String> aliases = args.length >= 5 ? parseAliases(args[4]) : List.of();
                String permission = args.length >= 6 ? dashToNull(args[5]) : null;
                if (createTemporaryChannel(sender.uuid(), args[1], permission, password, prefix, aliases)) {
                    sender.replyKey("chat-channel-temp-created", Map.of("channel", normalize(args[1])));
                } else {
                    sender.replyKey("chat-channel-temp-failed");
                }
                return;
            }
            ChannelInput input = parseChannelInput(args, 0, true);
            replySwitch(sender, player, input.channelId(), input.password());
        }

        private String[] stripCommandToken(String[] raw) {
            if (raw.length == 0) {
                return raw;
            }
            String first = normalizeAlias(raw[0]);
            if (!"channel".equals(first) && !"ch".equals(first)) {
                return raw;
            }
            String[] stripped = new String[raw.length - 1];
            System.arraycopy(raw, 1, stripped, 0, stripped.length);
            return stripped;
        }
    }

    private final class ChannelJoinSubCommand extends PublicChannelCommand {
        private final RequiredArg<String> channel = withRequiredArg("channel", "Channel", ArgTypes.STRING)
                .suggest(visibleChannelSuggestions());

        ChannelJoinSubCommand() {
            super(ChannelsSubModule.this.core, "join", "Listen to a chat channel.");
            addAliases("listen");
            addUsageVariant(new ChannelJoinPasswordVariant());
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            PlayerRef player = sender.player().orElse(null);
            if (player == null) {
                sender.replyKey("player-only");
                return;
            }
            replyJoin(sender, player, sender.get(channel), null);
        }
    }

    private final class ChannelJoinPasswordVariant extends PublicChannelCommand {
        private final RequiredArg<String> channel = withRequiredArg("channel", "Channel", ArgTypes.STRING)
                .suggest(visibleChannelSuggestions());
        private final RequiredArg<String> password = withRequiredArg("password", "Password", ArgTypes.STRING);

        ChannelJoinPasswordVariant() {
            super(ChannelsSubModule.this.core, "Listen to a password-locked chat channel.");
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            PlayerRef player = sender.player().orElse(null);
            if (player == null) {
                sender.replyKey("player-only");
                return;
            }
            replyJoin(sender, player, sender.get(channel), sender.get(password));
        }
    }

    private final class ChannelLeaveSubCommand extends PublicChannelCommand {
        private final RequiredArg<String> channel = withRequiredArg("channel", "Channel", ArgTypes.STRING)
                .suggest(listeningChannelSuggestions());

        ChannelLeaveSubCommand() {
            super(ChannelsSubModule.this.core, "leave", "Stop listening to a chat channel.");
            addAliases("mute");
            addAliases("unlisten");
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            PlayerRef player = sender.player().orElse(null);
            if (player == null) {
                sender.replyKey("player-only");
                return;
            }
            replyLeave(sender, player, sender.get(channel));
        }
    }

    private final class ChannelSwitchSubCommand extends PublicChannelCommand {
        private final RequiredArg<String> channel = withRequiredArg("channel", "Channel", ArgTypes.STRING)
                .suggest(visibleChannelSuggestions());

        ChannelSwitchSubCommand() {
            super(ChannelsSubModule.this.core, "switch", "Speak in a chat channel.");
            addAliases("speak");
            addAliases("use");
            addUsageVariant(new ChannelSwitchPasswordVariant());
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            PlayerRef player = sender.player().orElse(null);
            if (player == null) {
                sender.replyKey("player-only");
                return;
            }
            replySwitch(sender, player, sender.get(channel), null);
        }
    }

    private final class ChannelSwitchPasswordVariant extends PublicChannelCommand {
        private final RequiredArg<String> channel = withRequiredArg("channel", "Channel", ArgTypes.STRING)
                .suggest(visibleChannelSuggestions());
        private final RequiredArg<String> password = withRequiredArg("password", "Password", ArgTypes.STRING);

        ChannelSwitchPasswordVariant() {
            super(ChannelsSubModule.this.core, "Speak in a password-locked chat channel.");
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            PlayerRef player = sender.player().orElse(null);
            if (player == null) {
                sender.replyKey("player-only");
                return;
            }
            replySwitch(sender, player, sender.get(channel), sender.get(password));
        }
    }

    private final class ChannelAliasCommand extends PublicChannelCommand {
        private final String alias;

        ChannelAliasCommand(String alias) {
            super(ChannelsSubModule.this.core, alias, "Switch to a chat channel.");
            this.alias = alias;
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            PlayerRef player = sender.player().orElse(null);
            if (player == null) {
                sender.replyKey("player-only");
                return;
            }
            replySwitch(sender, player, alias, null);
        }
    }

    private void replyJoin(MysticCommandSender sender, PlayerRef player, String channelId, String password) {
        ChatConfig.Channel target = findChannel(channelId).orElse(null);
        JoinResult result = joinChannel(player, target, password);
        switch (result) {
            case JOINED -> sender.replyKey("chat-channel-joined", Map.of("channel", displayName(target)));
            case ALREADY_LISTENING -> sender.replyKey("chat-channel-already-listening",
                    Map.of("channel", displayName(target)));
            case PASSWORD_REQUIRED -> sender.replyKey("chat-channel-password-required");
            case NO_LISTEN_PERMISSION -> sender.replyKey("chat-channel-no-listen");
            case UNKNOWN -> sender.replyKey("chat-channel-unknown");
        }
    }

    private void replyLeave(MysticCommandSender sender, PlayerRef player, String channelId) {
        ChatConfig.Channel target = findChannel(channelId).orElse(null);
        LeaveResult result = leaveChannel(player, target);
        switch (result) {
            case LEFT -> sender.replyKey("chat-channel-left", Map.of("channel", displayName(target)));
            case NOT_LISTENING -> sender.replyKey("chat-channel-not-listening",
                    Map.of("channel", displayName(target)));
            case CURRENT_CHANNEL -> sender.replyKey("chat-channel-current");
            case UNKNOWN -> sender.replyKey("chat-channel-unknown");
        }
    }

    private void replySwitch(MysticCommandSender sender, PlayerRef player, String channelId, String password) {
        SwitchResult result = switchChannel(player, channelId, password);
        ChatConfig.Channel target = findChannel(channelId).orElse(null);
        switch (result) {
            case SWITCHED -> sender.replyKey("chat-channel-switched", Map.of("channel", displayName(target)));
            case PASSWORD_REQUIRED -> sender.replyKey("chat-channel-password-required");
            case NO_LISTEN_PERMISSION -> sender.replyKey("chat-channel-no-listen");
            case NO_SPEAK_PERMISSION -> sender.replyKey("chat-channel-no-speak");
            case UNKNOWN -> sender.replyKey("chat-channel-unknown");
        }
    }

    private void showChannelMenu(PlayerRef player) {
        String current = currentChannel(player.getUuid());
        core.getMessageService().send(player, "&8&m--------------------------------");
        core.getMessageService().sendKey(player, "chat-channel-list-header", Map.of("channel", current));
        List<ChatConfig.Channel> channels = visibleChannels(player);
        if (channels.isEmpty()) {
            core.getMessageService().sendKey(player, "chat-channel-list-empty");
            core.getMessageService().send(player, "&8&m--------------------------------");
            return;
        }
        for (ChatConfig.Channel channel : channels) {
            String id = normalize(channel.id);
            boolean listening = listeningChannels(player.getUuid()).contains(id);
            boolean speaking = id.equals(current);
            String speak = speaking ? "&aSpeaking" : commandLink("&bSpeak", "/channel switch " + id);
            String listen;
            if (listening) {
                listen = commandLink("&cLeave", "/channel leave " + id);
            } else if (blankToNull(channel.password) == null) {
                listen = commandLink("&aListen", "/channel join " + id);
            } else {
                listen = "&ePassword";
            }
            String aliases = aliasesFor(channel).isEmpty() ? "" : " &8(" + String.join("&7, &f", aliasesFor(channel)) + "&8)";
            core.getMessageService().sendKey(player, "chat-channel-list-entry", Map.of(
                    "channel", displayName(channel),
                    "aliases", aliases,
                    "status", speak + " &8| " + listen));
        }
        core.getMessageService().sendKey(player, "chat-channel-list-help");
        core.getMessageService().send(player, "&8&m--------------------------------");
    }
}
