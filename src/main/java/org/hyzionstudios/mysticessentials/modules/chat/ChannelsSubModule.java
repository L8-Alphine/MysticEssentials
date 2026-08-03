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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hyzionstudios.mysticessentials.api.voice.ChannelVoicePresenceProvider;
import org.hyzionstudios.mysticessentials.api.notification.Notification;
import org.hyzionstudios.mysticessentials.api.notification.NotificationAudience;
import org.hyzionstudios.mysticessentials.api.notification.NotificationCategory;
import org.hyzionstudios.mysticessentials.api.notification.NotificationPriority;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.modules.chat.roster.ChannelActivity;
import org.hyzionstudios.mysticessentials.modules.chat.roster.ChannelMemberRole;
import org.hyzionstudios.mysticessentials.modules.chat.roster.ChannelMemberView;
import org.hyzionstudios.mysticessentials.modules.chat.roster.ChannelParticipation;
import org.hyzionstudios.mysticessentials.modules.chat.roster.RosterTags;
import org.hyzionstudios.mysticessentials.platform.command.MysticArgTypes;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.arguments.types.SingleArgumentType;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/** Channel routing, permission gates, temporary channels, and Redis-backed network chat. */
public final class ChannelsSubModule {

    private static final String REDIS_PREFIX = "chat-channel-";
    private static final String REDIS_STATE_TOPIC = "chat-channel-state";
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
    /** Channels the sender can see. */
    private final SingleArgumentType<String> visibleChannelArg = MysticArgTypes.dynamic(this::visibleChannelIds);
    /** Channels the sender is listening to. */
    private final SingleArgumentType<String> listeningChannelArg = MysticArgTypes.dynamic(this::listeningChannelIds);
    private final Map<UUID, Set<String>> listeningChannels = new ConcurrentHashMap<>();
    private final Map<String, TemporaryChannel> temporaryChannels = new ConcurrentHashMap<>();
    private final Map<UUID, TransferRequest> pendingTransfers = new ConcurrentHashMap<>();
    private final Set<String> seenRemoteMessages = ConcurrentHashMap.newKeySet();
    private final Set<String> registeredAliases = ConcurrentHashMap.newKeySet();
    private final ChannelAudit audit;
    /** Last time a player sent a message to a channel, for the recent-activity indicator (§15). */
    private final Map<UUID, Instant> lastTextActivity = new ConcurrentHashMap<>();
    private volatile ChannelVoicePresenceProvider voiceProvider = ChannelVoicePresenceProvider.NONE;

    private ChatConfig.Channels config = new ChatConfig.Channels();
    private Map<String, ChatConfig.Channel> configuredChannels = Map.of();
    private Map<String, String> aliasToChannel = Map.of();
    private Consumer<MysticCommand> commandRegistrar;
    private com.hypixel.hytale.registry.Registration disconnectListener;
    private com.hypixel.hytale.registry.Registration connectListener;
    private boolean stateSubscribed;

    public ChannelsSubModule(MysticCore core, ChatModule chat) {
        this.core = core;
        this.chat = chat;
        this.audit = new ChannelAudit(core);
    }

    public void enable(ChatConfig.Channels config, Consumer<MysticCommand> commandRegistrar) {
        this.commandRegistrar = commandRegistrar;
        reload(config);
        commandRegistrar.accept(new ChannelCommand());
        registerConfiguredAliasCommands(commandRegistrar);
        disconnectListener = core.platform().onEvent(PlayerDisconnectEvent.class, (PlayerDisconnectEvent event) ->
                handleDisconnect(event.getPlayerRef()));
        connectListener = core.platform().onEvent(PlayerConnectEvent.class, (PlayerConnectEvent event) ->
                handleOwnerReconnect(event.getPlayerRef().getUuid()));
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
        if (core.redis().isEnabled() && !stateSubscribed) {
            core.redis().subscribe(REDIS_STATE_TOPIC, this::handleRemoteState);
            stateSubscribed = true;
        }
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
        if (connectListener != null) {
            try {
                connectListener.unregister();
            } catch (Throwable ignored) {
                // One-shot handle; already gone or engine shutting down.
            }
            connectListener = null;
        }
        for (TemporaryChannel temp : temporaryChannels.values()) {
            cancelGrace(temp);
        }
        pendingTransfers.clear();
        speakChannels.clear();
        listeningChannels.clear();
        lastTextActivity.clear();
        temporaryChannels.clear();
        seenRemoteMessages.clear();
        voiceProvider = ChannelVoicePresenceProvider.NONE;
        stateSubscribed = false;
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
        TemporaryChannel temp = temporaryChannels.get(normalize(channel.id));
        ChannelParticipation participation = effectiveParticipation(sender, channel, temp);
        if (participation == ChannelParticipation.MUTED) {
            Mute mute = temp == null ? null : temp.mutes.get(sender.getUuid());
            core.getMessageService().sendKey(sender, "chat-channel-you-muted",
                    Map.of("reason", mute == null || mute.reason() == null ? "" : mute.reason()));
            event.setCancelled(true);
            return event;
        }
        if (participation == ChannelParticipation.LISTENER) {
            core.getMessageService().sendKey(sender, "chat-channel-you-listener");
            event.setCancelled(true);
            return event;
        }
        lastTextActivity.put(sender.getUuid(), Instant.now());
        event.setTargets(localRecipients(channel, sender, event.getTargets()));
        if (channel.crossServer && core.redis().isEnabled()) {
            publishRemote(channel, sender, event.getContent());
        }
        return event;
    }

    public String currentChannel(UUID player) {
        return speakChannels.getOrDefault(player, normalize(config.defaultSpeak));
    }

    /** The channel a local chat message from this sender routes to, if any. */
    public Optional<ChatConfig.Channel> activeChannelFor(PlayerRef sender) {
        return channelForSender(sender);
    }

    /** Public counterpart of {@link #displayName(ChatConfig.Channel)} for the publish hook. */
    public String displayNameOf(ChatConfig.Channel channel) {
        return displayName(channel);
    }

    /** The ids of temporary channels currently active on this server. */
    public Set<String> temporaryChannelIds() {
        pruneExpired();
        return Set.copyOf(temporaryChannels.keySet());
    }

    // ----- Temporary-channel lifecycle events (external bridges) -------------

    private void publishTempCreated(String channelId, UUID owner) {
        core.getEventBus().publish(
                new org.hyzionstudios.mysticessentials.api.event.TemporaryChannelCreatedEvent(channelId, owner));
    }

    /** No-op for configured channels: only temporary channels report membership. */
    private void publishTempMembership(String channelId, UUID player, boolean joined) {
        if (!temporaryChannels.containsKey(channelId)) {
            return;
        }
        core.getEventBus().publish(
                new org.hyzionstudios.mysticessentials.api.event.TemporaryChannelMembershipChangedEvent(
                        channelId, player, joined));
    }

    private void publishTempClosed(String channelId) {
        core.getEventBus().publish(
                new org.hyzionstudios.mysticessentials.api.event.TemporaryChannelClosedEvent(channelId));
    }

    /**
     * Delivers an externally sourced (bridge/system) message to THIS server's listeners
     * only. Deliberately no cross-server relay: bridge callers (MysticIdentity) already
     * fan the message out to every server, so a relay here would double-deliver.
     * Never fires ChatMessagePublishedEvent — see ChatService.broadcastToChannel.
     *
     * @param format render line overriding the channel format, or null for the channel's own
     */
    public boolean broadcastExternal(String channelId, String senderName, String content, String format) {
        ChatConfig.Channel channel = findChannel(channelId).orElse(null);
        if (channel == null || !channel.enabled) {
            return false;
        }
        deliverInbound(channel, null, senderName, "", content, format);
        return true;
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

    // ----- Channel roster (Phase 1: member state, role resolution, tags) -----

    /** @return {@code true} when the roster feature is enabled in config. */
    public boolean rosterEnabled() {
        return config.enabled && config.roster != null && config.roster.enabled;
    }

    /**
     * Registers the voice-presence adapter queried for live speaking/mute state (§11.4).
     * Passing {@code null} restores the no-op provider, which never reports speaking so
     * the roster cannot fabricate a live speaking indicator.
     */
    public void setVoicePresenceProvider(ChannelVoicePresenceProvider provider) {
        this.voiceProvider = provider == null ? ChannelVoicePresenceProvider.NONE : provider;
    }

    /** The currently registered voice-presence adapter ({@link ChannelVoicePresenceProvider#NONE} by default). */
    public ChannelVoicePresenceProvider voicePresenceProvider() {
        return voiceProvider;
    }

    /** Soft cap on rows shown in the compact roster (§25 pagination hint). */
    public int rosterMaxVisible() {
        int cap = config.roster == null ? 50 : config.roster.maximumVisibleMembers;
        return cap <= 0 ? Integer.MAX_VALUE : cap;
    }

    /** @return {@code true} when {@code player} may open a channel roster (§17 view permission). */
    public boolean canViewRoster(PlayerRef player) {
        if (!rosterEnabled()) {
            return false;
        }
        String node = config.roster.viewPermission;
        return node == null || node.isBlank() || player.hasPermission(node);
    }

    /** Opens the compact roster for the player's current speaking channel. */
    public void openRosterUi(PlayerRef player) {
        core.platform().openPage(player,
                new ChannelPages.ChannelRosterPage(core, this, player, currentChannelId(player)));
    }

    /** Opens the expanded member-management interface (§6, {@code /channel members}). */
    public void openMembersUi(PlayerRef player, String channelId) {
        String id = channelId == null || channelId.isBlank() ? currentChannelId(player) : resolveChannelId(channelId);
        core.platform().openPage(player, new ChannelPages.ChannelMembersPage(core, this, player, id, ""));
    }

    /**
     * The current members of a channel as roster rows, grouped authority-first then
     * speakers before listeners then alphabetically (§5.3). Membership is defined as
     * the online players currently listening to the channel.
     */
    public List<ChannelMemberView> rosterFor(String channelId) {
        ChatConfig.Channel channel = findChannel(channelId).orElse(null);
        if (channel == null) {
            return List.of();
        }
        TemporaryChannel temp = temporaryChannels.get(resolveChannelId(channelId));
        UUID ownerId = temp == null ? null : temp.owner;
        List<ChannelMemberView> members = new ArrayList<>();
        for (PlayerRef online : core.platform().onlinePlayers()) {
            if (isListening(online, channel)) {
                members.add(buildMemberView(online, channel, ownerId, temp));
            }
        }
        members.sort(Comparator
                .comparingInt(ChannelsSubModule::authorityRank)
                .thenComparingInt(ChannelsSubModule::participationRank)
                .thenComparing(view -> view.name().toLowerCase(Locale.ROOT)));
        return members;
    }

    /** A single member's roster view within a channel, if they are currently a member. */
    public Optional<ChannelMemberView> rosterMember(String channelId, UUID playerId) {
        return rosterFor(channelId).stream()
                .filter(view -> view.playerId().equals(playerId))
                .findFirst();
    }

    /** The player-owner of {@code channelId}, if it is an owned temporary channel. */
    public Optional<UUID> channelOwner(String channelId) {
        pruneExpired();
        TemporaryChannel temp = temporaryChannels.get(resolveChannelId(channelId));
        return temp == null ? Optional.empty() : Optional.ofNullable(temp.owner);
    }

    /** The display name of a channel by id, for roster headers. */
    public String displayNameOfId(String channelId) {
        return findChannel(channelId).map(ChannelsSubModule::displayName).orElse(normalize(channelId));
    }

    /** Whether a channel exists and is enabled. */
    public boolean channelExists(String channelId) {
        return findChannel(channelId).map(channel -> channel.enabled).orElse(false);
    }

    /** {@code true} when the channel is a temporary player-owned channel. */
    public boolean isTemporaryChannel(String channelId) {
        pruneExpired();
        return temporaryChannels.containsKey(resolveChannelId(channelId));
    }

    private ChannelMemberView buildMemberView(PlayerRef player, ChatConfig.Channel channel, UUID ownerId,
            TemporaryChannel temp) {
        ChannelMemberRole role = roleOf(player, channel, ownerId, temp);
        boolean staff = isStaff(player);
        ChannelParticipation participation = effectiveParticipation(player, channel, temp);
        String serverRank = "";
        if (config.roster.showServerRanks) {
            String group = core.getPermissionService().primaryGroup(player.getUuid());
            serverRank = group == null ? "" : group;
        }
        ChatConfig.Tag primary = RosterTags.primary(config.roster, role, staff);
        ChatConfig.Tag secondary = RosterTags.secondary(config.roster, role, staff);
        return new ChannelMemberView(
                player.getUuid(),
                player.getUsername(),
                role,
                staff,
                serverRank,
                participation,
                resolveActivity(player, channel, participation),
                RosterTags.text(primary, role.name()),
                secondary == null ? null : RosterTags.text(secondary, "STAFF"),
                RosterTags.color(primary));
    }

    /**
     * Resolves live activity (§2.1). A registered voice provider's speaking state wins
     * ({@link ChannelActivity#SPEAKING}); otherwise recent text activity, then the
     * participation-derived listening/idle fallback. Never fabricates SPEAKING without
     * a provider (§11.4).
     */
    private ChannelActivity resolveActivity(PlayerRef player, ChatConfig.Channel channel,
            ChannelParticipation participation) {
        ChatConfig.Activity activityConfig = config.roster == null ? null : config.roster.activity;
        if (activityConfig != null && activityConfig.activeSpeakerIndicator
                && voiceProvider.isSpeaking(player.getUuid(), normalize(channel.id))) {
            return ChannelActivity.SPEAKING;
        }
        int window = activityConfig == null ? 30 : Math.max(0, activityConfig.recentTextActivitySeconds);
        Instant last = lastTextActivity.get(player.getUuid());
        if (window > 0 && last != null && last.isAfter(Instant.now().minusSeconds(window))
                && normalize(channel.id).equals(currentChannel(player.getUuid()))) {
            return ChannelActivity.RECENTLY_ACTIVE;
        }
        return participation == ChannelParticipation.SPEAKER
                ? ChannelActivity.IDLE
                : ChannelActivity.LISTENING;
    }

    /**
     * Resolves a player's authority in a channel (§18.3). Server staff is intentionally
     * excluded here — it is a server-wide flag carried separately on the member view.
     * A temporary-channel moderator is either explicitly assigned ({@code temp.moderators})
     * or granted the channel's moderator permission node.
     */
    private ChannelMemberRole roleOf(PlayerRef player, ChatConfig.Channel channel, UUID ownerId,
            TemporaryChannel temp) {
        UUID uuid = player.getUuid();
        if (ownerId != null && ownerId.equals(uuid)) {
            return ChannelMemberRole.OWNER;
        }
        if (temp != null && temp.moderators.contains(uuid)) {
            return ChannelMemberRole.CHANNEL_MODERATOR;
        }
        String modNode = channel.moderatorPermission;
        if (modNode != null && !modNode.isBlank() && player.hasPermission(modNode)) {
            return ChannelMemberRole.CHANNEL_MODERATOR;
        }
        return ChannelMemberRole.MEMBER;
    }

    /**
     * The member's effective text participation (§11): a channel moderation mute wins
     * ({@link ChannelParticipation#MUTED}), then an assigned listener override, then the
     * permission-derived speaker/listener state.
     */
    private ChannelParticipation effectiveParticipation(PlayerRef player, ChatConfig.Channel channel,
            TemporaryChannel temp) {
        UUID uuid = player.getUuid();
        if (temp != null) {
            Mute mute = temp.mutes.get(uuid);
            if (mute != null && mute.isActive()) {
                return ChannelParticipation.MUTED;
            }
            if (temp.participationOverride.get(uuid) == ChannelParticipation.LISTENER) {
                return ChannelParticipation.LISTENER;
            }
        }
        return canSpeak(player, channel) ? ChannelParticipation.SPEAKER : ChannelParticipation.LISTENER;
    }

    private boolean isStaff(PlayerRef player) {
        String node = config.roster == null ? null : config.roster.staffPermission;
        return node != null && !node.isBlank() && player.hasPermission(node);
    }

    private static int authorityRank(ChannelMemberView view) {
        return switch (view.role()) {
            case OWNER -> 0;
            case CHANNEL_MODERATOR -> 1;
            case MEMBER -> view.staff() ? 2 : 3;
        };
    }

    private static int participationRank(ChannelMemberView view) {
        return switch (view.participation()) {
            case SPEAKER -> 0;
            case LISTENER -> 1;
            case MUTED -> 2;
        };
    }

    // ----- Channel management actions (Phase 2, server-authoritative §24) -----

    /** Outcome of a management action; the command/UI layer maps it to a message. */
    public enum ManageResult {
        OK,
        NOT_TEMPORARY,
        NO_PERMISSION,
        TARGET_NOT_MEMBER,
        TARGET_IS_OWNER,
        TARGET_PROTECTED_STAFF,
        TARGET_INVALID,
        ALREADY,
        NOT_MODERATOR,
        DISABLED,
        NOT_ELIGIBLE,
        EXPIRED
    }

    public boolean isOwnerOf(String channelId, UUID uuid) {
        return channelOwner(channelId).map(owner -> owner.equals(uuid)).orElse(false);
    }

    public boolean isModeratorOf(String channelId, UUID uuid) {
        TemporaryChannel temp = temporaryChannels.get(resolveChannelId(channelId));
        return temp != null && temp.moderators.contains(uuid);
    }

    public boolean isBannedFrom(String channelId, UUID uuid) {
        TemporaryChannel temp = temporaryChannels.get(resolveChannelId(channelId));
        return temp != null && temp.banned.contains(uuid);
    }

    public boolean isChannelLocked(String channelId) {
        return findChannel(channelId).map(channel -> channel.locked).orElse(false);
    }

    public Optional<Instant> memberJoinedAt(String channelId, UUID uuid) {
        TemporaryChannel temp = temporaryChannels.get(resolveChannelId(channelId));
        return temp == null ? Optional.empty() : Optional.ofNullable(temp.joinedAt.get(uuid));
    }

    public Optional<String> muteReason(String channelId, UUID uuid) {
        TemporaryChannel temp = temporaryChannels.get(resolveChannelId(channelId));
        if (temp == null) {
            return Optional.empty();
        }
        Mute mute = temp.mutes.get(uuid);
        return mute != null && mute.isActive() ? Optional.ofNullable(mute.reason()) : Optional.empty();
    }

    /** {@code true} when {@code actor} may perform owner-level management on the channel. */
    public boolean canOwnerManageChannel(PlayerRef actor, String channelId) {
        TemporaryChannel temp = temporaryChannels.get(resolveChannelId(channelId));
        return temp != null && canOwnerManage(actor, temp);
    }

    /** {@code true} when {@code actor} may perform moderator-level management on the channel. */
    public boolean canModerateChannel(PlayerRef actor, String channelId) {
        TemporaryChannel temp = temporaryChannels.get(resolveChannelId(channelId));
        return temp != null && canModerate(actor, temp);
    }

    public boolean hasStaffOverride(PlayerRef actor) {
        return actor.hasPermission(STAFF_OVERRIDE_PERM);
    }

    public ManageResult assignModerator(PlayerRef actor, String channelId, UUID target) {
        TemporaryChannel temp = temporaryChannels.get(resolveChannelId(channelId));
        if (temp == null) {
            return ManageResult.NOT_TEMPORARY;
        }
        boolean allowed = canOwnerManage(actor, temp)
                || (isChannelModerator(actor.getUuid(), temp) && management().moderatorsCanPromoteModerators);
        if (!allowed) {
            return ManageResult.NO_PERMISSION;
        }
        if (!isMember(temp, target)) {
            return ManageResult.TARGET_NOT_MEMBER;
        }
        if (temp.owner != null && temp.owner.equals(target)) {
            return ManageResult.TARGET_IS_OWNER;
        }
        if (!temp.moderators.add(target)) {
            return ManageResult.ALREADY;
        }
        persist(channelId, temp);
        audit.record("MODERATOR_ASSIGNED", channelId, actor.getUuid(), target, null);
        core.getEventBus().publish(
                new org.hyzionstudios.mysticessentials.api.event.ChannelModeratorChangedEvent(
                        resolveChannelId(channelId), target, actor.getUuid(), true));
        notify(target, "chat-channel-mod-assigned", Map.of("channel", displayNameOfId(channelId)));
        return ManageResult.OK;
    }

    public ManageResult removeModerator(PlayerRef actor, String channelId, UUID target) {
        TemporaryChannel temp = temporaryChannels.get(resolveChannelId(channelId));
        if (temp == null) {
            return ManageResult.NOT_TEMPORARY;
        }
        if (!canOwnerManage(actor, temp)) {
            return ManageResult.NO_PERMISSION;
        }
        if (!temp.moderators.remove(target)) {
            return ManageResult.NOT_MODERATOR;
        }
        persist(channelId, temp);
        audit.record("MODERATOR_REMOVED", channelId, actor.getUuid(), target, null);
        core.getEventBus().publish(
                new org.hyzionstudios.mysticessentials.api.event.ChannelModeratorChangedEvent(
                        resolveChannelId(channelId), target, actor.getUuid(), false));
        notify(target, "chat-channel-mod-removed", Map.of("channel", displayNameOfId(channelId)));
        return ManageResult.OK;
    }

    /** {@code listener=true} restricts the member to listener; {@code false} restores speaker (clears mute + override). */
    public ManageResult setListenerMode(PlayerRef actor, String channelId, UUID target, boolean listener) {
        TemporaryChannel temp = temporaryChannels.get(resolveChannelId(channelId));
        if (temp == null) {
            return ManageResult.NOT_TEMPORARY;
        }
        if (!canModerate(actor, temp) || (isOnlyModerator(actor, temp) && !management().moderatorsCanChangeParticipation)) {
            return ManageResult.NO_PERMISSION;
        }
        ManageResult guard = guardTarget(temp, target);
        if (guard != ManageResult.OK) {
            return guard;
        }
        if (listener) {
            temp.participationOverride.put(target, ChannelParticipation.LISTENER);
            audit.record("SET_LISTENER", channelId, actor.getUuid(), target, null);
            notify(target, "chat-channel-set-listener", Map.of("channel", displayNameOfId(channelId)));
        } else {
            temp.participationOverride.remove(target);
            temp.mutes.remove(target);
            audit.record("SET_SPEAKER", channelId, actor.getUuid(), target, null);
            notify(target, "chat-channel-set-speaker", Map.of("channel", displayNameOfId(channelId)));
        }
        persist(channelId, temp);
        return ManageResult.OK;
    }

    public ManageResult muteMember(PlayerRef actor, String channelId, UUID target, long durationSeconds, String reason) {
        TemporaryChannel temp = temporaryChannels.get(resolveChannelId(channelId));
        if (temp == null) {
            return ManageResult.NOT_TEMPORARY;
        }
        if (!canModerate(actor, temp) || (isOnlyModerator(actor, temp) && !management().moderatorsCanMuteMembers)) {
            return ManageResult.NO_PERMISSION;
        }
        ManageResult guard = guardTarget(temp, target);
        if (guard != ManageResult.OK) {
            return guard;
        }
        Instant expiresAt = durationSeconds > 0 ? Instant.now().plusSeconds(durationSeconds) : null;
        temp.mutes.put(target, new Mute(expiresAt, blankToNull(reason), actor.getUuid()));
        persist(channelId, temp);
        audit.record("MEMBER_MUTED", channelId, actor.getUuid(), target,
                "duration=" + (durationSeconds > 0 ? durationSeconds + "s" : "permanent")
                        + " reason=" + (reason == null ? "" : reason));
        notify(target, "chat-channel-you-muted-notice", Map.of(
                "channel", displayNameOfId(channelId),
                "reason", reason == null ? "" : reason));
        return ManageResult.OK;
    }

    public ManageResult unmuteMember(PlayerRef actor, String channelId, UUID target) {
        TemporaryChannel temp = temporaryChannels.get(resolveChannelId(channelId));
        if (temp == null) {
            return ManageResult.NOT_TEMPORARY;
        }
        if (!canModerate(actor, temp) || (isOnlyModerator(actor, temp) && !management().moderatorsCanMuteMembers)) {
            return ManageResult.NO_PERMISSION;
        }
        if (temp.mutes.remove(target) == null) {
            return ManageResult.ALREADY;
        }
        persist(channelId, temp);
        audit.record("MEMBER_UNMUTED", channelId, actor.getUuid(), target, null);
        notify(target, "chat-channel-you-unmuted", Map.of("channel", displayNameOfId(channelId)));
        return ManageResult.OK;
    }

    public ManageResult removeMember(PlayerRef actor, String channelId, UUID target) {
        TemporaryChannel temp = temporaryChannels.get(resolveChannelId(channelId));
        if (temp == null) {
            return ManageResult.NOT_TEMPORARY;
        }
        if (!canModerate(actor, temp) || (isOnlyModerator(actor, temp) && !management().moderatorsCanRemoveMembers)) {
            return ManageResult.NO_PERMISSION;
        }
        ManageResult guard = guardTarget(temp, target);
        if (guard != ManageResult.OK) {
            return guard;
        }
        detach(temp, resolveChannelId(channelId), target);
        persist(channelId, temp);
        audit.record("MEMBER_REMOVED", channelId, actor.getUuid(), target, null);
        notify(target, "chat-channel-you-removed", Map.of("channel", displayNameOfId(channelId)));
        return ManageResult.OK;
    }

    public ManageResult banMember(PlayerRef actor, String channelId, UUID target) {
        TemporaryChannel temp = temporaryChannels.get(resolveChannelId(channelId));
        if (temp == null) {
            return ManageResult.NOT_TEMPORARY;
        }
        boolean allowed = canOwnerManage(actor, temp)
                || (isChannelModerator(actor.getUuid(), temp) && management().moderatorsCanBanMembers);
        if (!allowed) {
            return ManageResult.NO_PERMISSION;
        }
        ManageResult guard = guardTarget(temp, target);
        if (guard != ManageResult.OK) {
            return guard;
        }
        temp.banned.add(target);
        detach(temp, resolveChannelId(channelId), target);
        persist(channelId, temp);
        audit.record("MEMBER_BANNED", channelId, actor.getUuid(), target, null);
        notify(target, "chat-channel-you-banned", Map.of("channel", displayNameOfId(channelId)));
        return ManageResult.OK;
    }

    public ManageResult unbanMember(PlayerRef actor, String channelId, UUID target) {
        TemporaryChannel temp = temporaryChannels.get(resolveChannelId(channelId));
        if (temp == null) {
            return ManageResult.NOT_TEMPORARY;
        }
        boolean allowed = canOwnerManage(actor, temp)
                || (isChannelModerator(actor.getUuid(), temp) && management().moderatorsCanBanMembers);
        if (!allowed) {
            return ManageResult.NO_PERMISSION;
        }
        if (!temp.banned.remove(target)) {
            return ManageResult.ALREADY;
        }
        persist(channelId, temp);
        audit.record("MEMBER_UNBANNED", channelId, actor.getUuid(), target, null);
        return ManageResult.OK;
    }

    public ManageResult setLocked(PlayerRef actor, String channelId, boolean locked) {
        TemporaryChannel temp = temporaryChannels.get(resolveChannelId(channelId));
        if (temp == null) {
            return ManageResult.NOT_TEMPORARY;
        }
        if (!canOwnerManage(actor, temp)) {
            return ManageResult.NO_PERMISSION;
        }
        if (temp.channel.locked == locked) {
            return ManageResult.ALREADY;
        }
        temp.channel.locked = locked;
        persist(channelId, temp);
        audit.record(locked ? "CHANNEL_LOCKED" : "CHANNEL_UNLOCKED", channelId, actor.getUuid(), null, null);
        return ManageResult.OK;
    }

    // ----- Ownership transfer (§9) --------------------------------------------

    public ManageResult requestTransfer(PlayerRef actor, String channelId, UUID target) {
        TemporaryChannel temp = temporaryChannels.get(resolveChannelId(channelId));
        if (temp == null) {
            return ManageResult.NOT_TEMPORARY;
        }
        if (!config.tempManagement.ownershipTransfer.enabled) {
            return ManageResult.DISABLED;
        }
        if (!canOwnerManage(actor, temp)) {
            return ManageResult.NO_PERMISSION;
        }
        ManageResult eligibility = transferEligible(temp, target);
        if (eligibility != ManageResult.OK) {
            return eligibility;
        }
        if (!config.tempManagement.ownershipTransfer.targetMustAccept) {
            performTransfer(resolveChannelId(channelId), temp, target, "REQUEST");
            return ManageResult.OK;
        }
        pendingTransfers.values().removeIf(req -> req.channelId().equals(resolveChannelId(channelId)));
        UUID requestId = UUID.randomUUID();
        int ttl = Math.max(5, config.tempManagement.ownershipTransfer.requestExpirationSeconds);
        TransferRequest request = new TransferRequest(requestId, resolveChannelId(channelId),
                actor.getUuid(), target, Instant.now().plusSeconds(ttl));
        pendingTransfers.put(requestId, request);
        audit.record("OWNERSHIP_TRANSFER_REQUESTED", channelId, actor.getUuid(), target, "request=" + requestId);
        promptTransferTarget(request);
        core.scheduler().runLater(() -> expireTransfer(requestId), ttl, TimeUnit.SECONDS);
        return ManageResult.OK;
    }

    public ManageResult acceptTransfer(PlayerRef target, UUID requestId) {
        TransferRequest request = pendingTransfers.get(requestId);
        if (request == null || !request.to().equals(target.getUuid())) {
            return ManageResult.TARGET_INVALID;
        }
        if (request.expiresAt().isBefore(Instant.now())) {
            pendingTransfers.remove(requestId);
            return ManageResult.EXPIRED;
        }
        TemporaryChannel temp = temporaryChannels.get(request.channelId());
        if (temp == null) {
            pendingTransfers.remove(requestId);
            return ManageResult.NOT_TEMPORARY;
        }
        ManageResult eligibility = transferEligible(temp, target.getUuid());
        if (eligibility != ManageResult.OK) {
            pendingTransfers.remove(requestId);
            return eligibility;
        }
        pendingTransfers.remove(requestId);
        performTransfer(request.channelId(), temp, target.getUuid(), "REQUEST");
        return ManageResult.OK;
    }

    public ManageResult declineTransfer(PlayerRef target, UUID requestId) {
        TransferRequest request = pendingTransfers.get(requestId);
        if (request == null || !request.to().equals(target.getUuid())) {
            return ManageResult.TARGET_INVALID;
        }
        pendingTransfers.remove(requestId);
        audit.record("OWNERSHIP_TRANSFER_DECLINED", request.channelId(), target.getUuid(), request.from(),
                "request=" + requestId);
        notify(request.from(), "chat-channel-transfer-declined", Map.of(
                "player", nameOf(target.getUuid()),
                "channel", displayNameOfId(request.channelId())));
        return ManageResult.OK;
    }

    /** Staff-forced transfer (§12.4). Bypasses target acceptance; requires the staff override node. */
    public ManageResult forceTransfer(PlayerRef staff, String channelId, UUID target) {
        TemporaryChannel temp = temporaryChannels.get(resolveChannelId(channelId));
        if (temp == null) {
            return ManageResult.NOT_TEMPORARY;
        }
        if (!hasStaffOverride(staff)) {
            return ManageResult.NO_PERMISSION;
        }
        if (!isMember(temp, target)) {
            return ManageResult.TARGET_NOT_MEMBER;
        }
        performTransfer(resolveChannelId(channelId), temp, target, "FORCED");
        return ManageResult.OK;
    }

    /** The pending transfer awaiting {@code target}, if any (for UI/command accept without an id). */
    public Optional<UUID> pendingTransferFor(UUID target) {
        return pendingTransfers.values().stream()
                .filter(req -> req.to().equals(target) && req.expiresAt().isAfter(Instant.now()))
                .map(TransferRequest::requestId)
                .findFirst();
    }

    private ManageResult transferEligible(TemporaryChannel temp, UUID target) {
        if (target == null || target.equals(temp.owner)) {
            return ManageResult.TARGET_INVALID;
        }
        if (temp.banned.contains(target)) {
            return ManageResult.TARGET_INVALID;
        }
        PlayerRef ref = core.platform().findPlayer(target).orElse(null);
        if (ref == null || !isMember(temp, target)) {
            return ManageResult.TARGET_NOT_MEMBER;
        }
        String ownPerm = config.createTemporaryPermission;
        if (ownPerm != null && !ownPerm.isBlank() && !ref.hasPermission(ownPerm)) {
            return ManageResult.NOT_ELIGIBLE;
        }
        return ManageResult.OK;
    }

    private void performTransfer(String id, TemporaryChannel temp, UUID newOwner, String source) {
        // Only the server holding the ownership lease may finalize a change (§10.3),
        // so two servers can never promote different owners for the same channel.
        String token = core.redis().serverId() + ":" + UUID.randomUUID();
        if (!acquireOwnershipLease(id, token)) {
            core.log(Level.WARNING, "Skipped ownership change for channel '" + id
                    + "' (" + source + "): another server holds the ownership lease.");
            return;
        }
        try {
            performTransferLocked(id, temp, newOwner, source);
        } finally {
            releaseOwnershipLease(id, token);
        }
    }

    private void performTransferLocked(String id, TemporaryChannel temp, UUID newOwner, String source) {
        UUID previous = temp.owner;
        temp.owner = newOwner;
        temp.ownerDisconnectedAt = null;
        cancelGrace(temp);
        // The new owner can no longer be a moderator/muted/restricted of their own channel.
        temp.moderators.remove(newOwner);
        temp.mutes.remove(newOwner);
        temp.participationOverride.remove(newOwner);
        temp.banned.remove(newOwner);
        temp.joinedAt.putIfAbsent(newOwner, Instant.now());
        listeningChannels(newOwner).add(id);
        speakChannels.put(newOwner, id);
        // Demote the previous owner per policy.
        if (previous != null && !previous.equals(newOwner)) {
            switch (previousOwnerRole()) {
                case "MEMBER" -> {
                }
                case "LISTENER" -> temp.participationOverride.put(previous, ChannelParticipation.LISTENER);
                case "REMOVE" -> detach(temp, id, previous);
                default -> temp.moderators.add(previous); // CHANNEL_MODERATOR
            }
        }
        persist(id, temp);
        audit.record("OWNERSHIP_TRANSFERRED", id, previous, newOwner, "source=" + source);
        core.getEventBus().publish(
                new org.hyzionstudios.mysticessentials.api.event.ChannelOwnershipTransferredEvent(
                        id, previous, newOwner, source));
        notify(newOwner, "chat-channel-transfer-received", Map.of("channel", displayNameOfId(id)));
        if (previous != null) {
            notify(previous, "chat-channel-transfer-done", Map.of(
                    "player", nameOf(newOwner), "channel", displayNameOfId(id)));
        }
    }

    private void expireTransfer(UUID requestId) {
        TransferRequest request = pendingTransfers.remove(requestId);
        if (request == null) {
            return;
        }
        notify(request.from(), "chat-channel-transfer-expired", Map.of(
                "player", nameOf(request.to()), "channel", displayNameOfId(request.channelId())));
        notify(request.to(), "chat-channel-transfer-expired-target",
                Map.of("channel", displayNameOfId(request.channelId())));
    }

    private void promptTransferTarget(TransferRequest request) {
        core.platform().findPlayer(request.to()).ifPresent(target -> {
            notify(target.getUuid(), "chat-channel-transfer-offer", Map.of(
                    "player", nameOf(request.from()),
                    "channel", displayNameOfId(request.channelId())));
            String accept = commandLink("&a[Accept]", "/channel owner accept " + request.requestId());
            String decline = commandLink("&c[Decline]", "/channel owner decline " + request.requestId());
            core.getMessageService().send(target, accept + " &7 " + decline);
        });
    }

    // ----- Owner-disconnect grace + succession (§10) --------------------------

    private void handleOwnerDisconnect(UUID leaving) {
        int grace = Math.max(0, config.tempManagement.ownerDisconnect.gracePeriodSeconds);
        for (Map.Entry<String, TemporaryChannel> entry : temporaryChannels.entrySet()) {
            TemporaryChannel temp = entry.getValue();
            if (!leaving.equals(temp.owner)) {
                continue;
            }
            temp.ownerDisconnectedAt = Instant.now();
            String id = entry.getKey();
            if (grace == 0) {
                runSuccession(id, leaving);
            } else {
                cancelGrace(temp);
                temp.graceFuture = core.scheduler().runLater(() -> runSuccession(id, leaving), grace, TimeUnit.SECONDS);
            }
        }
    }

    private void handleOwnerReconnect(UUID uuid) {
        for (Map.Entry<String, TemporaryChannel> entry : temporaryChannels.entrySet()) {
            TemporaryChannel temp = entry.getValue();
            if (uuid.equals(temp.owner) && temp.ownerDisconnectedAt != null) {
                temp.ownerDisconnectedAt = null;
                cancelGrace(temp);
                // The owner returned within grace: restore their membership in the channel.
                listeningChannels(uuid).add(entry.getKey());
            }
        }
    }

    private void runSuccession(String channelId, UUID expectedOwner) {
        TemporaryChannel temp = temporaryChannels.get(channelId);
        if (temp == null || !expectedOwner.equals(temp.owner) || temp.ownerDisconnectedAt == null) {
            return; // Ownership changed, channel gone, or the owner already returned.
        }
        if (core.platform().findPlayer(expectedOwner).isPresent()) {
            temp.ownerDisconnectedAt = null; // Owner is back online.
            return;
        }
        UUID successor = pickSuccessor(temp, successionMode());
        if (successor == null) {
            successor = pickSuccessor(temp, fallbackMode());
        }
        if (successor != null) {
            performTransfer(channelId, temp, successor, "SUCCESSION");
            return;
        }
        if ("CLOSE_CHANNEL".equals(successionMode()) || "CLOSE_CHANNEL".equals(fallbackMode())) {
            audit.record("CHANNEL_SUCCESSION_CLOSE", channelId, null, expectedOwner, null);
            closeTemporaryChannelById(channelId);
        }
        // KEEP_OWNERSHIP or no candidate: the disconnected owner keeps ownership.
    }

    private UUID pickSuccessor(TemporaryChannel temp, String mode) {
        List<PlayerRef> online = onlineMembers(temp);
        online.removeIf(ref -> ref.getUuid().equals(temp.owner));
        return switch (mode) {
            case "PROMOTE_MODERATOR" -> online.stream()
                    .filter(ref -> temp.moderators.contains(ref.getUuid()))
                    .min(Comparator.comparing(ref -> joinInstant(temp, ref.getUuid())))
                    .map(PlayerRef::getUuid)
                    .orElse(null);
            case "PROMOTE_OLDEST_MEMBER" -> online.stream()
                    .min(Comparator.comparing(ref -> joinInstant(temp, ref.getUuid())))
                    .map(PlayerRef::getUuid)
                    .orElse(null);
            default -> null; // KEEP_OWNERSHIP / CLOSE_CHANNEL produce no successor here.
        };
    }

    // ----- Management helpers -------------------------------------------------

    private boolean canOwnerManage(PlayerRef actor, TemporaryChannel temp) {
        return isChannelOwner(actor.getUuid(), temp) || hasStaffOverride(actor);
    }

    private boolean canModerate(PlayerRef actor, TemporaryChannel temp) {
        return isChannelOwner(actor.getUuid(), temp) || isChannelModerator(actor.getUuid(), temp)
                || hasStaffOverride(actor);
    }

    private boolean isOnlyModerator(PlayerRef actor, TemporaryChannel temp) {
        return !isChannelOwner(actor.getUuid(), temp) && !hasStaffOverride(actor)
                && isChannelModerator(actor.getUuid(), temp);
    }

    private boolean isChannelOwner(UUID uuid, TemporaryChannel temp) {
        return temp.owner != null && temp.owner.equals(uuid);
    }

    private boolean isChannelModerator(UUID uuid, TemporaryChannel temp) {
        if (temp.moderators.contains(uuid)) {
            return true;
        }
        String modNode = temp.channel.moderatorPermission;
        if (modNode == null || modNode.isBlank()) {
            return false;
        }
        return core.platform().findPlayer(uuid).map(ref -> ref.hasPermission(modNode)).orElse(false);
    }

    /** Rejects targets that must not be punished: the owner, or a staff-override holder (§8.2). */
    private ManageResult guardTarget(TemporaryChannel temp, UUID target) {
        if (target == null) {
            return ManageResult.TARGET_INVALID;
        }
        if (temp.owner != null && temp.owner.equals(target)) {
            return ManageResult.TARGET_IS_OWNER;
        }
        if (!isMember(temp, target)) {
            return ManageResult.TARGET_NOT_MEMBER;
        }
        boolean targetIsStaff = core.platform().findPlayer(target)
                .map(this::hasStaffOverride).orElse(false);
        if (targetIsStaff) {
            return ManageResult.TARGET_PROTECTED_STAFF;
        }
        return ManageResult.OK;
    }

    private boolean isMember(TemporaryChannel temp, UUID uuid) {
        Set<String> listening = listeningChannels.get(uuid);
        return listening != null && listening.contains(normalize(temp.channel.id));
    }

    private List<PlayerRef> onlineMembers(TemporaryChannel temp) {
        List<PlayerRef> members = new ArrayList<>();
        for (PlayerRef online : core.platform().onlinePlayers()) {
            if (isListening(online, temp.channel)) {
                members.add(online);
            }
        }
        return members;
    }

    /** Removes a member from a temporary channel and clears their per-member state. */
    private void detach(TemporaryChannel temp, String id, UUID uuid) {
        Set<String> listening = listeningChannels.get(uuid);
        if (listening != null) {
            listening.remove(id);
        }
        temp.moderators.remove(uuid);
        temp.mutes.remove(uuid);
        temp.participationOverride.remove(uuid);
        if (id.equals(speakChannels.get(uuid))) {
            speakChannels.put(uuid, normalize(config.defaultSpeak));
        }
        publishTempMembership(id, uuid, false);
    }

    private void cancelGrace(TemporaryChannel temp) {
        if (temp.graceFuture != null) {
            temp.graceFuture.cancel(false);
            temp.graceFuture = null;
        }
    }

    private void persist(String channelId, TemporaryChannel temp) {
        temp.version++;
        if (core.redis().isEnabled()) {
            String id = resolveChannelId(channelId);
            saveRedisTemporaryChannel(id, temp);
            publishChannelState("update", id, temp.version);
        }
    }

    // ----- Cross-server state propagation (Phase 4, §19.3) --------------------

    /** Announces a temporary-channel change so other servers refresh their copy. */
    private void publishChannelState(String action, String id, long version) {
        if (!core.redis().isEnabled()) {
            return;
        }
        JsonObject envelope = new JsonObject();
        envelope.addProperty("action", action);
        envelope.addProperty("channelId", id);
        envelope.addProperty("version", version);
        envelope.addProperty("originServerId", core.redis().serverId());
        envelope.addProperty("correlationId", UUID.randomUUID().toString());
        envelope.addProperty("timestamp", Instant.now().toString());
        core.redis().publish(REDIS_STATE_TOPIC, Json.toString(envelope));
    }

    private void handleRemoteState(String payload) {
        JsonObject envelope = Json.asObject(Json.parse(payload));
        String origin = envelope.has("originServerId") ? envelope.get("originServerId").getAsString() : "";
        if (origin.equals(core.redis().serverId())) {
            return; // Our own broadcast echoed back.
        }
        String id = envelope.has("channelId") ? normalize(envelope.get("channelId").getAsString()) : "";
        if (id.isBlank() || configuredChannels.containsKey(id)) {
            return;
        }
        String action = envelope.has("action") ? envelope.get("action").getAsString() : "";
        if ("closed".equals(action)) {
            applyRemoteClose(id);
            return;
        }
        long version = envelope.has("version") ? envelope.get("version").getAsLong() : 0;
        applyRemoteUpdate(id, version);
    }

    /**
     * Applies a remote temporary-channel update by re-reading the authoritative Redis
     * blob, but only when it is strictly newer than the local copy (§19.3 version guard).
     */
    private void applyRemoteUpdate(String id, long version) {
        TemporaryChannel local = temporaryChannels.get(id);
        if (local != null && local.version >= version) {
            return;
        }
        String raw = core.redis().cacheGet(TEMP_KEY_PREFIX + id);
        if (raw == null || raw.isBlank()) {
            return;
        }
        TemporaryChannel remote = parseRedisTemporaryChannel(raw);
        if (remote == null || (local != null && local.version >= remote.version)) {
            return;
        }
        if (local != null) {
            cancelGrace(local);
        }
        temporaryChannels.put(id, remote);
        // Alias-index the channel so /channel switch <alias> resolves; standalone alias
        // command registration is left to the owning server (avoid off-thread registration).
        rebuildAliasIndex();
    }

    private void applyRemoteClose(String id) {
        TemporaryChannel removed = temporaryChannels.remove(id);
        if (removed == null) {
            return;
        }
        cancelGrace(removed);
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
        publishTempClosed(id);
    }

    // ----- Ownership lease (Phase 4, §10.3) -----------------------------------

    private String ownershipLeaseKey(String id) {
        return core.redis().networkId() + ":channel:" + id + ":owner-lease";
    }

    /** Acquires the cross-server ownership lease (single-server = always granted). */
    private boolean acquireOwnershipLease(String id, String token) {
        return !core.redis().isEnabled() || core.redis().lockAcquire(ownershipLeaseKey(id), token, 15);
    }

    private void releaseOwnershipLease(String id, String token) {
        if (core.redis().isEnabled()) {
            core.redis().lockRelease(ownershipLeaseKey(id), token);
        }
    }

    private void notify(UUID uuid, String key, Map<String, String> placeholders) {
        core.platform().findPlayer(uuid).ifPresent(ref -> {
            if (core.notifications() == null) {
                core.getMessageService().sendKey(ref, key, placeholders);
                return;
            }
            boolean important = key.contains("offer") || key.contains("received")
                    || key.contains("muted") || key.contains("removed") || key.contains("banned");
            String message = core.getMessageService().plainFromKey(key, placeholders);
            core.notifications().send(Notification.builder()
                    .category(NotificationCategory.CHANNEL)
                    .priority(important ? NotificationPriority.IMPORTANT : NotificationPriority.NORMAL)
                    .title(key.contains("transfer") ? "Channel Ownership" : "Channel Update")
                    .subtitle(message)
                    .message(message)
                    .source("mysticessentials:channels")
                    .build(), NotificationAudience.player(uuid));
        });
    }

    private String nameOf(UUID uuid) {
        return core.platform().findPlayer(uuid)
                .map(PlayerRef::getUsername)
                .orElseGet(() -> uuid == null ? "?" : uuid.toString().substring(0, 8));
    }

    private Instant joinInstant(TemporaryChannel temp, UUID uuid) {
        return temp.joinedAt.getOrDefault(uuid, Instant.MAX);
    }

    private ChatConfig.Moderation management() {
        return config.tempManagement.moderation;
    }

    private String previousOwnerRole() {
        String role = config.tempManagement.ownershipTransfer.previousOwnerRole;
        return role == null ? "CHANNEL_MODERATOR" : role.toUpperCase(Locale.ROOT);
    }

    private String successionMode() {
        String mode = config.tempManagement.ownerDisconnect.successionMode;
        return mode == null ? "PROMOTE_MODERATOR" : mode.toUpperCase(Locale.ROOT);
    }

    private String fallbackMode() {
        String mode = config.tempManagement.ownerDisconnect.fallbackMode;
        return mode == null ? "PROMOTE_OLDEST_MEMBER" : mode.toUpperCase(Locale.ROOT);
    }

    private static final String STAFF_OVERRIDE_PERM = "mysticessentials.channel.staff.override";

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
            case BANNED -> core.getMessageService().sendKey(player, "chat-channel-banned");
            case LOCKED -> core.getMessageService().sendKey(player, "chat-channel-locked");
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
        return id != null && closeTemporaryChannelById(id);
    }

    /** Closes a temporary channel by id, moving everyone off it. Used by succession + staff close. */
    public boolean closeTemporaryChannelById(String channelId) {
        String id = resolveChannelId(channelId);
        TemporaryChannel temp = temporaryChannels.remove(id);
        if (temp == null) {
            return false;
        }
        cancelGrace(temp);
        pendingTransfers.values().removeIf(req -> req.channelId().equals(id));
        if (core.redis().isEnabled()) {
            core.redis().cacheDelete(TEMP_KEY_PREFIX + id);
            saveRedisTemporaryIndex();
            publishChannelState("closed", id, temp.version + 1);
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
        publishTempClosed(id);
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
        temp.joinedAt.put(owner, Instant.now());
        temporaryChannels.put(id, temp);
        speakChannels.put(owner, id);
        listeningChannels(owner).add(id);
        publishTempCreated(id, owner);
        Map<String, String> aliasesNext = new HashMap<>(aliasToChannel);
        indexAliases(aliasesNext, channel);
        aliasToChannel = aliasesNext;
        if (commandRegistrar != null) {
            registerAliasCommands(channel.aliases, commandRegistrar);
        }
        saveRedisTemporaryChannel(id, temp);
        publishChannelState("update", id, temp.version);
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

    /**
     * Relays a message to the other servers on the network.
     *
     * <p>The content is flattened to plain text first. Structured tokens only mean
     * something on the server that created them — a peer holds no snapshot for an
     * item code — so relaying them raw would put unrenderable text in front of
     * every player on every other server. Resolving here, where the snapshot
     * still exists, is what lets a remote reader see {@code [Scarlet Requiem]}.</p>
     */
    private void publishRemote(ChatConfig.Channel channel, PlayerRef sender, String content) {
        publishEnvelope(channel, sender.getUuid().toString(), sender.getUsername(),
                chat.plainTextOf(content));
    }

    private void publishEnvelope(ChatConfig.Channel channel, String senderUuid, String senderName, String content) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("messageId", UUID.randomUUID().toString());
        envelope.addProperty("networkId", core.redis().networkId());
        envelope.addProperty("originServerId", core.redis().serverId());
        envelope.addProperty("channelId", channel.id);
        envelope.addProperty("senderUuid", senderUuid);
        envelope.addProperty("senderName", senderName);
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
        String originServerId = envelope.has("originServerId") ? envelope.get("originServerId").getAsString() : "";
        deliverInbound(channel, parseUuid(senderUuid), senderName, originServerId, content, null);
    }

    /** Formats and delivers an inbound (remote or externally injected) message to local listeners. */
    private void deliverInbound(ChatConfig.Channel channel, UUID placeholderContext, String senderName,
            String originServerId, String content, String formatOverride) {
        String template = formatOverride != null && !formatOverride.isBlank()
                ? formatOverride
                : formatForGroup(placeholderContext, channel);
        // Inbound content is flattened again on arrival. The sending server should
        // already have done this, but a peer running an older build (or an external
        // injector) must not be able to put raw tokens or markup on local screens.
        String safeContent = ChatTokens.toPlainText(content);
        String line = template
                .replace("{player_name}", senderName)
                .replace("{display_name}", senderName)
                .replace("{channel}", displayName(channel))
                .replace("{server_id}", originServerId == null ? "" : originServerId)
                .replace("{message}", safeContent);
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
        TemporaryChannel temp = temporaryChannels.get(id);
        if (temp != null && temp.banned.contains(player.getUuid())) {
            return JoinResult.BANNED;
        }
        if (channel.locked) {
            return JoinResult.LOCKED;
        }
        if (!passwordMatches(channel, password)) {
            return JoinResult.PASSWORD_REQUIRED;
        }
        listening.add(id);
        if (temp != null) {
            temp.joinedAt.putIfAbsent(player.getUuid(), Instant.now());
        }
        publishTempMembership(id, player.getUuid(), true);
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
        publishTempMembership(id, player.getUuid(), false);
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
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, TemporaryChannel> entry : temporaryChannels.entrySet()) {
            if (entry.getValue().expiresAt.isBefore(now)) {
                temporaryChannels.remove(entry.getKey());
                expired.add(entry.getKey());
            }
        }
        if (!expired.isEmpty()) {
            rebuildAliasIndex();
            saveRedisTemporaryIndex();
            expired.forEach(this::publishTempClosed);
        }
    }

    private void handleDisconnect(PlayerRef player) {
        UUID leaving = player.getUuid();
        Set<String> wasListening = listeningChannels.get(leaving);
        if (wasListening != null) {
            for (String id : List.copyOf(wasListening)) {
                publishTempMembership(id, leaving, false);
            }
        }
        speakChannels.remove(leaving);
        listeningChannels.remove(leaving);
        lastTextActivity.remove(leaving);
        if (temporaryChannels.isEmpty()) {
            return;
        }
        boolean anyOtherOnline = core.platform().onlinePlayers().stream()
                .anyMatch(online -> !online.getUuid().equals(leaving));
        if (!anyOtherOnline) {
            clearTemporaryChannels();
            return;
        }
        // Others remain: if a channel owner left, start the grace + succession timer (§10).
        handleOwnerDisconnect(leaving);
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
        List<String> cleared = List.copyOf(temporaryChannels.keySet());
        temporaryChannels.clear();
        for (Set<String> listening : listeningChannels.values()) {
            listening.removeIf(id -> !configuredChannels.containsKey(id));
        }
        speakChannels.entrySet().removeIf(entry -> !configuredChannels.containsKey(entry.getValue()));
        rebuildAliasIndex();
        cleared.forEach(this::publishTempClosed);
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
                // Restored channels re-announce so external bridges reconcile after restart.
                publishTempCreated(normalize(temp.channel.id), temp.owner);
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
        TemporaryChannel temp = new TemporaryChannel(channel, owner, expires);
        if (object.has("version") && !object.get("version").isJsonNull()) {
            temp.version = object.get("version").getAsLong();
        }
        readUuids(object, "moderators").forEach(temp.moderators::add);
        readUuids(object, "banned").forEach(temp.banned::add);
        if (object.has("joinedAt") && object.get("joinedAt").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("joinedAt").entrySet()) {
                UUID uuid = parseUuid(entry.getKey());
                if (uuid != null) {
                    try {
                        temp.joinedAt.put(uuid, Instant.parse(entry.getValue().getAsString()));
                    } catch (Exception ignored) {
                        // Skip a malformed timestamp; join time is best-effort.
                    }
                }
            }
        }
        if (object.has("mutes") && object.get("mutes").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("mutes").entrySet()) {
                UUID uuid = parseUuid(entry.getKey());
                if (uuid == null || !entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject m = entry.getValue().getAsJsonObject();
                Instant muteExpires = m.has("expiresAt") ? tryInstant(m.get("expiresAt").getAsString()) : null;
                String reason = m.has("reason") ? m.get("reason").getAsString() : null;
                UUID by = parseUuid(m.has("by") ? m.get("by").getAsString() : null);
                temp.mutes.put(uuid, new Mute(muteExpires, reason, by));
            }
        }
        if (object.has("participationOverride") && object.get("participationOverride").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("participationOverride").entrySet()) {
                UUID uuid = parseUuid(entry.getKey());
                if (uuid != null && "LISTENER".equals(entry.getValue().getAsString())) {
                    temp.participationOverride.put(uuid, ChannelParticipation.LISTENER);
                }
            }
        }
        return temp;
    }

    private static JsonArray uuidArray(Set<UUID> uuids) {
        JsonArray array = new JsonArray();
        for (UUID uuid : uuids) {
            array.add(uuid.toString());
        }
        return array;
    }

    private static JsonObject instantMap(Map<UUID, Instant> map) {
        JsonObject object = new JsonObject();
        map.forEach((uuid, instant) -> object.addProperty(uuid.toString(), instant.toString()));
        return object;
    }

    private static List<UUID> readUuids(JsonObject object, String key) {
        List<UUID> result = new ArrayList<>();
        if (object.has(key) && object.get(key).isJsonArray()) {
            for (JsonElement element : object.getAsJsonArray(key)) {
                UUID uuid = element.isJsonPrimitive() ? parseUuid(element.getAsString()) : null;
                if (uuid != null) {
                    result.add(uuid);
                }
            }
        }
        return result;
    }

    private static Instant tryInstant(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : Instant.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private void saveRedisTemporaryChannel(String id, TemporaryChannel temp) {
        if (!core.redis().isEnabled()) {
            return;
        }
        long ttl = temporaryChannelTtlSeconds();
        JsonObject object = new JsonObject();
        object.add("channel", Json.toTree(temp.channel));
        object.addProperty("owner", temp.owner == null ? null : temp.owner.toString());
        object.addProperty("version", temp.version);
        if (!Instant.MAX.equals(temp.expiresAt)) {
            object.addProperty("expiresAt", temp.expiresAt.toString());
        }
        object.add("moderators", uuidArray(temp.moderators));
        object.add("banned", uuidArray(temp.banned));
        object.add("joinedAt", instantMap(temp.joinedAt));
        JsonObject mutes = new JsonObject();
        temp.mutes.forEach((uuid, mute) -> {
            JsonObject m = new JsonObject();
            if (mute.expiresAt() != null) {
                m.addProperty("expiresAt", mute.expiresAt().toString());
            }
            if (mute.reason() != null) {
                m.addProperty("reason", mute.reason());
            }
            if (mute.by() != null) {
                m.addProperty("by", mute.by().toString());
            }
            mutes.add(uuid.toString(), m);
        });
        object.add("mutes", mutes);
        JsonObject overrides = new JsonObject();
        temp.participationOverride.forEach((uuid, mode) -> overrides.addProperty(uuid.toString(), mode.name()));
        object.add("participationOverride", overrides);
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
        PASSWORD_REQUIRED,
        BANNED,
        LOCKED
    }

    private enum LeaveResult {
        LEFT,
        UNKNOWN,
        NOT_LISTENING,
        CURRENT_CHANNEL
    }

    private record ChannelInput(String channelId, String password) {
    }

    /**
     * Live state for one temporary channel. Mutable (owner changes on transfer/
     * succession); membership rosters remain the online-listener set, but per-member
     * moderation state (mods, bans, mutes, participation overrides, join times) lives
     * here and is persisted to Redis so it survives restarts and reaches other servers.
     */
    private static final class TemporaryChannel {
        ChatConfig.Channel channel;
        UUID owner;
        Instant expiresAt;
        final Set<UUID> moderators = ConcurrentHashMap.newKeySet();
        final Set<UUID> banned = ConcurrentHashMap.newKeySet();
        final Map<UUID, Mute> mutes = new ConcurrentHashMap<>();
        final Map<UUID, ChannelParticipation> participationOverride = new ConcurrentHashMap<>();
        final Map<UUID, Instant> joinedAt = new ConcurrentHashMap<>();
        volatile Instant ownerDisconnectedAt;
        transient ScheduledFuture<?> graceFuture;
        /** Monotonic state version for cross-server conflict resolution (§19.3). */
        volatile long version;

        TemporaryChannel(ChatConfig.Channel channel, UUID owner, Instant expiresAt) {
            this.channel = channel;
            this.owner = owner;
            this.expiresAt = expiresAt;
        }
    }

    /** A channel-level moderation mute: optional expiry ({@code null} = permanent), reason, actor. */
    private record Mute(Instant expiresAt, String reason, UUID by) {
        boolean isActive() {
            return expiresAt == null || expiresAt.isAfter(Instant.now());
        }
    }

    /** A pending ownership-transfer request awaiting the target's acceptance (§9). */
    private record TransferRequest(UUID requestId, String channelId, UUID from, UUID to, Instant expiresAt) {
    }

    /** Channel ids the sender can see. */
    private List<String> visibleChannelIds(CommandSender commandSender) {
        return core.platform().findPlayer(commandSender.getUuid())
                .map(player -> visibleChannels(player).stream().map(channel -> normalize(channel.id)).toList())
                .orElseGet(List::of);
    }

    /** Channel ids the sender can see and is currently listening to. */
    private List<String> listeningChannelIds(CommandSender commandSender) {
        return core.platform().findPlayer(commandSender.getUuid())
                .map(player -> {
                    Set<String> listening = listeningChannels(player.getUuid());
                    return visibleChannels(player).stream()
                            .map(channel -> normalize(channel.id))
                            .filter(listening::contains)
                            .toList();
                })
                .orElseGet(List::of);
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
            if ("leave".equals(action) || "unlisten".equals(action)) {
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
            if ("members".equals(action) || "roster".equals(action)) {
                if (!canViewRoster(player)) {
                    sender.replyKey(rosterEnabled() ? "chat-channel-roster-no-permission" : "chat-channel-roster-disabled");
                    return;
                }
                String targetChannel = args.length >= 2 ? args[1] : currentChannelId(player);
                if ("roster".equals(action)) {
                    core.platform().openPage(player,
                            new ChannelPages.ChannelRosterPage(core, ChannelsSubModule.this, player,
                                    resolveChannelId(targetChannel)));
                } else {
                    openMembersUi(player, targetChannel);
                }
                return;
            }
            if (MANAGEMENT_ACTIONS.contains(action)) {
                runManagement(sender, player, action, args);
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
        private final RequiredArg<String> channel = withRequiredArg("channel", "Channel", visibleChannelArg);

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
        private final RequiredArg<String> channel = withRequiredArg("channel", "Channel", visibleChannelArg);
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
        private final RequiredArg<String> channel = withRequiredArg("channel", "Channel", listeningChannelArg);

        ChannelLeaveSubCommand() {
            super(ChannelsSubModule.this.core, "leave", "Stop listening to a chat channel.");
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
        private final RequiredArg<String> channel = withRequiredArg("channel", "Channel", visibleChannelArg);

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
        private final RequiredArg<String> channel = withRequiredArg("channel", "Channel", visibleChannelArg);
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
            case BANNED -> sender.replyKey("chat-channel-banned");
            case LOCKED -> sender.replyKey("chat-channel-locked");
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

    // ----- Management command handling (Phase 2) ------------------------------

    private static final Set<String> MANAGEMENT_ACTIONS = Set.of(
            "owner", "moderator", "mod", "member", "mute", "unmute", "lock", "unlock", "close");

    /** The temporary channel these management commands act on: the one the player is in or owns. */
    private String manageableChannelId(PlayerRef player) {
        String current = currentChannelId(player);
        if (isTemporaryChannel(current)) {
            return current;
        }
        return ownedTemporaryChannel(player.getUuid())
                .map(channel -> normalize(channel.id))
                .orElse(current);
    }

    private void runManagement(MysticCommandSender sender, PlayerRef player, String action, String[] args) {
        switch (action) {
            case "owner" -> runOwnerAction(sender, player, args);
            case "moderator", "mod" -> runModeratorAction(sender, player, args);
            case "member" -> runMemberAction(sender, player, args);
            case "mute" -> runMute(sender, player, args);
            case "unmute" -> withTarget(sender, args, 1, target ->
                    replyManage(sender, unmuteMember(player, manageableChannelId(player), target),
                            "chat-channel-manage-unmuted", target));
            case "lock" -> replyManage(sender, setLocked(player, manageableChannelId(player), true),
                    "chat-channel-manage-locked", null);
            case "unlock" -> replyManage(sender, setLocked(player, manageableChannelId(player), false),
                    "chat-channel-manage-unlocked", null);
            case "close" -> {
                if (!canOwnerManageChannel(player, manageableChannelId(player))) {
                    sender.replyKey("chat-channel-manage-no-permission");
                    return;
                }
                sender.replyKey(closeTemporaryChannelById(manageableChannelId(player))
                        ? "chat-channel-temp-closed" : "chat-channel-temp-not-owned");
            }
            default -> sender.replyKey("chat-channel-unknown");
        }
    }

    private void runOwnerAction(MysticCommandSender sender, PlayerRef player, String[] args) {
        if (args.length < 2) {
            sender.replyKey("chat-channel-owner-usage");
            return;
        }
        String sub = normalize(args[1]);
        switch (sub) {
            case "transfer" -> withTarget(sender, args, 2, target ->
                    replyManage(sender, requestTransfer(player, manageableChannelId(player), target),
                            "chat-channel-transfer-sent", target));
            case "accept" -> {
                UUID requestId = args.length >= 3 ? parseUuid(args[2]) : pendingTransferFor(player.getUuid()).orElse(null);
                if (requestId == null) {
                    sender.replyKey("chat-channel-transfer-none");
                    return;
                }
                replyManage(sender, acceptTransfer(player, requestId), "chat-channel-transfer-accepted", null);
            }
            case "decline" -> {
                UUID requestId = args.length >= 3 ? parseUuid(args[2]) : pendingTransferFor(player.getUuid()).orElse(null);
                if (requestId == null) {
                    sender.replyKey("chat-channel-transfer-none");
                    return;
                }
                replyManage(sender, declineTransfer(player, requestId), "chat-channel-transfer-declined-self", null);
            }
            case "force-transfer" -> withTarget(sender, args, 2, target ->
                    replyManage(sender, forceTransfer(player, manageableChannelId(player), target),
                            "chat-channel-transfer-forced", target));
            default -> sender.replyKey("chat-channel-owner-usage");
        }
    }

    private void runModeratorAction(MysticCommandSender sender, PlayerRef player, String[] args) {
        if (args.length < 3) {
            sender.replyKey("chat-channel-moderator-usage");
            return;
        }
        String sub = normalize(args[1]);
        withTarget(sender, args, 2, target -> {
            switch (sub) {
                case "add" -> replyManage(sender, assignModerator(player, manageableChannelId(player), target),
                        "chat-channel-manage-mod-added", target);
                case "remove" -> replyManage(sender, removeModerator(player, manageableChannelId(player), target),
                        "chat-channel-manage-mod-removed", target);
                default -> sender.replyKey("chat-channel-moderator-usage");
            }
        });
    }

    private void runMemberAction(MysticCommandSender sender, PlayerRef player, String[] args) {
        if (args.length < 3) {
            sender.replyKey("chat-channel-member-usage");
            return;
        }
        String sub = normalize(args[1]);
        withTarget(sender, args, 2, target -> {
            String channelId = manageableChannelId(player);
            switch (sub) {
                case "remove", "kick" -> replyManage(sender, removeMember(player, channelId, target),
                        "chat-channel-manage-removed", target);
                case "ban" -> replyManage(sender, banMember(player, channelId, target),
                        "chat-channel-manage-banned", target);
                case "unban" -> replyManage(sender, unbanMember(player, channelId, target),
                        "chat-channel-manage-unbanned", target);
                case "speaker" -> replyManage(sender, setListenerMode(player, channelId, target, false),
                        "chat-channel-manage-speaker", target);
                case "listener" -> replyManage(sender, setListenerMode(player, channelId, target, true),
                        "chat-channel-manage-listener", target);
                default -> sender.replyKey("chat-channel-member-usage");
            }
        });
    }

    private void runMute(MysticCommandSender sender, PlayerRef player, String[] args) {
        if (args.length < 2) {
            sender.replyKey("chat-channel-mute-usage");
            return;
        }
        withTarget(sender, args, 1, target -> {
            long duration = 0;
            int reasonStart = 2;
            if (args.length >= 3) {
                long parsed = parseDurationSeconds(args[2]);
                if (parsed > 0) {
                    duration = parsed;
                    reasonStart = 3;
                }
            }
            String reason = args.length > reasonStart ? joinArgs(args, reasonStart, args.length) : null;
            replyManage(sender, muteMember(player, manageableChannelId(player), target, duration, reason),
                    "chat-channel-manage-muted", target);
        });
    }

    /** Resolves the arg at {@code index} to an online player (or a raw UUID) and runs {@code action}. */
    private void withTarget(MysticCommandSender sender, String[] args, int index, Consumer<UUID> action) {
        if (args.length <= index) {
            sender.replyKey("chat-channel-target-required");
            return;
        }
        String raw = args[index];
        UUID direct = parseUuid(raw);
        if (direct != null) {
            action.accept(direct);
            return;
        }
        UUID online = core.platform().findPlayerByName(raw).map(PlayerRef::getUuid).orElse(null);
        if (online == null) {
            sender.replyKey("chat-channel-target-offline", Map.of("player", raw));
            return;
        }
        action.accept(online);
    }

    private void replyManage(MysticCommandSender sender, ManageResult result, String successKey, UUID target) {
        Map<String, String> placeholders = target == null ? Map.of() : Map.of("player", nameOf(target));
        switch (result) {
            case OK -> sender.replyKey(successKey, placeholders);
            case NOT_TEMPORARY -> sender.replyKey("chat-channel-not-temp");
            case NO_PERMISSION -> sender.replyKey("chat-channel-manage-no-permission");
            case TARGET_NOT_MEMBER -> sender.replyKey("chat-channel-target-not-member");
            case TARGET_IS_OWNER -> sender.replyKey("chat-channel-target-owner");
            case TARGET_PROTECTED_STAFF -> sender.replyKey("chat-channel-target-staff");
            case TARGET_INVALID -> sender.replyKey("chat-channel-target-invalid");
            case ALREADY -> sender.replyKey("chat-channel-manage-already");
            case NOT_MODERATOR -> sender.replyKey("chat-channel-not-moderator");
            case DISABLED -> sender.replyKey("chat-channel-transfer-disabled");
            case NOT_ELIGIBLE -> sender.replyKey("chat-channel-target-not-eligible");
            case EXPIRED -> sender.replyKey("chat-channel-transfer-expired-self");
        }
    }

    /** Parses a duration like {@code 30}, {@code 10m}, {@code 2h}, {@code 1d} to seconds; 0 if invalid. */
    private static long parseDurationSeconds(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        long multiplier = 1;
        char unit = value.charAt(value.length() - 1);
        if (!Character.isDigit(unit)) {
            multiplier = switch (unit) {
                case 's' -> 1;
                case 'm' -> 60;
                case 'h' -> 3600;
                case 'd' -> 86400;
                default -> 0;
            };
            value = value.substring(0, value.length() - 1);
        }
        if (multiplier == 0) {
            return 0;
        }
        try {
            long number = Long.parseLong(value.trim());
            return number > 0 ? number * multiplier : 0;
        } catch (NumberFormatException e) {
            return 0;
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
