package org.hyzionstudios.mysticessentials.modules.teleportation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.api.model.MysticLocation;
import org.hyzionstudios.mysticessentials.api.model.PlayerProfile;
import org.hyzionstudios.mysticessentials.api.model.TeleportRequest;
import org.hyzionstudios.mysticessentials.api.service.TeleportService;
import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;
import org.hyzionstudios.mysticessentials.core.teleport.TeleportServiceImpl;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Central teleportation feature: teleport requests ({@code /tpa}, {@code /tpahere},
 * {@code /tpaccept}, {@code /tpdeny}, {@code /tpcancel}), {@code /tphere},
 * {@code /tpall}, and {@code /back}. All movement is routed through the Core {@code TeleportService}
 * so warmups, cooldowns, and cancellation are applied consistently.
 *
 * <p>{@code /tpa} with no target opens the Teleport Requests UI (which includes
 * the player's favourites list). Commands use positional usage variants —
 * {@code /tpa [player]} — instead of flag-style optional args. Each target can
 * hold several pending inbound requests (one per requester); requests expire
 * after {@code requestExpirySeconds}. Timings live in
 * {@code modules/teleportation/config.json} ({@link TeleportationConfig}).</p>
 *
 * <p>Loaded early (no hard dependencies) because Spawn, Homes, Warps, Player
 * Warps, and AFK Rewards consume the teleport service.</p>
 */
public final class TeleportationModule extends AbstractMysticModule {

    private static final String DATA_KEY = "teleportation";
    private static final String FAVORITES_KEY = "favorites";
    private static final String TPA_DISABLED_KEY = "tpaDisabled";

    /** target UUID -> (requester UUID -> pending request), insertion-ordered. */
    private final Map<UUID, LinkedHashMap<UUID, PendingRequest>> pending = new ConcurrentHashMap<>();
    private final Set<UUID> tpaDisabled = ConcurrentHashMap.newKeySet();

    private TeleportationConfig config = new TeleportationConfig();

    public TeleportationModule() {
        super("teleportation", "Teleportation", "1.0.0");
    }

    record PendingRequest(UUID requester, String requesterName, boolean requesterTeleports, Instant created) {
        boolean expired(Duration ttl) {
            return created.plus(ttl).isBefore(Instant.now());
        }
    }

    private Duration requestTtl() {
        return Duration.ofSeconds(Math.max(1, config.requestExpirySeconds));
    }

    @Override
    public void onEnable() {
        loadConfig();
        registerCommand(new TpCommand());
        registerCommand(new TpaCommand());
        registerCommand(new TpaHereCommand());
        registerCommand(new TpAcceptCommand());
        registerCommand(new TpDenyCommand());
        registerCommand(new TpCancelCommand());
        registerCommand(new TpToggleCommand());
        registerCommand(new TpHereForceCommand());
        registerCommand(new TpAllCommand());
        registerCommand(new TopCommand());
        registerCommand(new BackCommand());
    }

    @Override
    public void onReload() {
        loadConfig();
    }

    @Override
    public void onDisable() {
        pending.clear();
        tpaDisabled.clear();
    }

    private void loadConfig() {
        config = core.configManager().loadModuleConfig(id(), TeleportationConfig.class,
                new TeleportationConfig());
        if (core.getTeleportService() instanceof TeleportServiceImpl service) {
            service.configure(config);
        }
    }

    // ----- Request state (shared by commands and the UI) ----------------------

    /** Pending, unexpired inbound requests for {@code target}, oldest first. */
    List<PendingRequest> incomingRequests(UUID target) {
        LinkedHashMap<UUID, PendingRequest> inbound = pending.get(target);
        if (inbound == null) {
            return List.of();
        }
        synchronized (inbound) {
            Duration ttl = requestTtl();
            inbound.values().removeIf(request -> request.expired(ttl));
            return new ArrayList<>(inbound.values());
        }
    }

    /** Sends a request; replaces any previous one from the same requester. */
    boolean sendRequest(PlayerRef requester, PlayerRef target, boolean requesterTeleports) {
        if (!acceptsTeleportRequests(target.getUuid())) {
            core.getMessageService().sendKey(requester, "teleport-requests-disabled-target",
                    Map.of("player", target.getUsername()));
            return false;
        }
        LinkedHashMap<UUID, PendingRequest> inbound =
                pending.computeIfAbsent(target.getUuid(), uuid -> new LinkedHashMap<>());
        synchronized (inbound) {
            inbound.remove(requester.getUuid());
            inbound.put(requester.getUuid(), new PendingRequest(requester.getUuid(),
                    requester.getUsername(), requesterTeleports, Instant.now()));
        }
        core.getMessageService().sendKey(target, requesterTeleports
                ? "teleport-request-incoming-to-you"
                : "teleport-request-incoming-to-them", Map.of("player", requester.getUsername()));
        return true;
    }

    /**
     * Accepts a request for {@code target}. {@code requester} may be null (accept
     * the most recent). @return the accepted request, or empty if none matched.
     */
    Optional<PendingRequest> acceptRequest(PlayerRef target, UUID requester) {
        PendingRequest request = takeRequest(target.getUuid(), requester);
        if (request == null) {
            return Optional.empty();
        }
        Optional<PlayerRef> requesterRef = core.platform().findPlayer(request.requester());
        if (requesterRef.isEmpty()) {
            core.getMessageService().sendKey(target, "teleport-target-offline");
            return Optional.empty();
        }
        if (request.requesterTeleports()) {
            teleportToPlayer(requesterRef.get(), target.getUuid(), "tpa");
        } else {
            teleportToPlayer(target, request.requester(), "tpahere");
        }
        core.getMessageService().sendKey(requesterRef.get(), "teleport-request-accepted-by",
                Map.of("player", target.getUsername()));
        return Optional.of(request);
    }

    /** Denies a request for {@code target}; null {@code requester} = the most recent. */
    Optional<PendingRequest> denyRequest(PlayerRef target, UUID requester) {
        PendingRequest request = takeRequest(target.getUuid(), requester);
        if (request != null) {
            core.platform().findPlayer(request.requester()).ifPresent(ref ->
                    core.getMessageService().sendKey(ref, "teleport-request-denied-by",
                            Map.of("player", target.getUsername())));
        }
        return Optional.ofNullable(request);
    }

    private PendingRequest takeRequest(UUID target, UUID requester) {
        LinkedHashMap<UUID, PendingRequest> inbound = pending.get(target);
        if (inbound == null) {
            return null;
        }
        synchronized (inbound) {
            Duration ttl = requestTtl();
            inbound.values().removeIf(request -> request.expired(ttl));
            if (inbound.isEmpty()) {
                return null;
            }
            if (requester != null) {
                return inbound.remove(requester);
            }
            UUID last = null;
            for (UUID key : inbound.keySet()) {
                last = key;
            }
            return inbound.remove(last);
        }
    }

    private void teleportToPlayer(PlayerRef who, UUID destinationPlayer, String type) {
        TeleportRequest request = TeleportRequest.builder()
                .type(type)
                .targetPlayer(destinationPlayer)
                .warmupSeconds(Math.max(0, config.tpaWarmupSeconds))
                .cooldownKey("tpa")
                .cooldownSeconds(Math.max(0, config.tpaCooldownSeconds))
                .build();
        core.getTeleportService().teleport(who, request);
    }

    // ----- Favourites (persisted in the player profile) ------------------------

    /** The player's favourite players: UUID &rarr; last-known name, insertion-ordered. */
    Map<UUID, String> favorites(UUID owner) {
        Map<UUID, String> result = new LinkedHashMap<>();
        core.getPlayerProfileService().getCached(owner).ifPresent(profile -> {
            synchronized (profile) {
                JsonObject favorites = favoritesObject(profile, false);
                if (favorites == null) {
                    return;
                }
                for (String key : favorites.keySet()) {
                    try {
                        result.put(UUID.fromString(key), favorites.get(key).getAsString());
                    } catch (RuntimeException ignored) {
                        // Skip malformed entries rather than breaking the whole list.
                    }
                }
            }
        });
        return result;
    }

    boolean isFavorite(UUID owner, UUID target) {
        return core.getPlayerProfileService().getCached(owner)
                .map(profile -> {
                    synchronized (profile) {
                        JsonObject favorites = favoritesObject(profile, false);
                        return favorites != null && favorites.has(target.toString());
                    }
                })
                .orElse(false);
    }

    boolean addFavorite(UUID owner, UUID target, String targetName) {
        if (owner.equals(target)) {
            return false;
        }
        return core.getPlayerProfileService().getCached(owner)
                .map(profile -> {
                    synchronized (profile) {
                        favoritesObject(profile, true).addProperty(target.toString(), targetName);
                    }
                    core.getPlayerProfileService().save(profile);
                    return true;
                })
                .orElse(false);
    }

    boolean removeFavorite(UUID owner, UUID target) {
        return core.getPlayerProfileService().getCached(owner)
                .map(profile -> {
                    synchronized (profile) {
                        JsonObject favorites = favoritesObject(profile, false);
                        if (favorites == null || !favorites.has(target.toString())) {
                            return false;
                        }
                        favorites.remove(target.toString());
                    }
                    core.getPlayerProfileService().save(profile);
                    return true;
                })
                .orElse(false);
    }

    private boolean acceptsTeleportRequests(UUID owner) {
        if (tpaDisabled.contains(owner)) {
            return false;
        }
        return core.getPlayerProfileService().getCached(owner)
                .map(profile -> {
                    synchronized (profile) {
                        JsonObject data = profile.getModuleData().get(DATA_KEY);
                        boolean disabled = jsonBoolean(data, TPA_DISABLED_KEY, false);
                        if (disabled) {
                            tpaDisabled.add(owner);
                        }
                        return !disabled;
                    }
                })
                .orElse(true);
    }

    private boolean setAcceptsTeleportRequests(UUID owner, boolean accepts) {
        if (accepts) {
            tpaDisabled.remove(owner);
        } else {
            tpaDisabled.add(owner);
        }
        core.getPlayerProfileService().getCached(owner).ifPresent(profile -> {
            synchronized (profile) {
                moduleData(profile, true).addProperty(TPA_DISABLED_KEY, !accepts);
            }
            core.getPlayerProfileService().save(profile);
        });
        return accepts;
    }

    private boolean toggleAcceptsTeleportRequests(UUID owner) {
        return setAcceptsTeleportRequests(owner, !acceptsTeleportRequests(owner));
    }

    private JsonObject favoritesObject(PlayerProfile profile, boolean create) {
        JsonObject data = moduleData(profile, create);
        if (data == null) {
            return null;
        }
        if (!data.has(FAVORITES_KEY)) {
            if (!create) {
                return null;
            }
            data.add(FAVORITES_KEY, new JsonObject());
        }
        return data.getAsJsonObject(FAVORITES_KEY);
    }

    private JsonObject moduleData(PlayerProfile profile, boolean create) {
        if (!create) {
            return profile.getModuleData().get(DATA_KEY);
        }
        return profile.getModuleData().computeIfAbsent(DATA_KEY, k -> new JsonObject());
    }

    private static boolean jsonBoolean(JsonObject data, String key, boolean fallback) {
        if (data == null || !data.has(key)) {
            return fallback;
        }
        try {
            return data.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    void openTpaUi(PlayerRef player) {
        core.platform().openPage(player, new TpaPages.TpaPage(core, this, player));
    }

    private void requestTo(MysticCommandSender sender, PlayerRef target, boolean requesterTeleports) {
        if (!sender.isPlayer()) {
            sender.replyKey("player-only");
            return;
        }
        if (target == null || !core.vanish().canSee(sender.uuid(), target.getUuid())) {
            sender.replyKey("player-not-found");
            return;
        }
        if (sender.uuid().equals(target.getUuid())) {
            sender.replyKey("teleport-request-self");
            return;
        }
        if (sendRequest(sender.player().orElseThrow(), target, requesterTeleports)) {
            sender.replyKey("teleport-request-sent", Map.of("player", target.getUsername()));
        }
    }

    /** Suggests usernames of players with a pending request to the sender. */
    private SuggestionProvider pendingRequesterSuggestions() {
        return (commandSender, input, index, result) ->
                incomingRequests(commandSender.getUuid()).forEach(r -> result.suggest(r.requesterName()));
    }

    /** Suggests online players the sender can see (vanish-aware). */
    private SuggestionProvider visiblePlayerSuggestions() {
        return (commandSender, input, index, result) ->
                core.vanish().visiblePlayers(commandSender.getUuid())
                        .forEach(p -> result.suggest(p.getUsername()));
    }

    private UUID resolveRequesterByName(MysticCommandSender sender, String name) {
        for (PendingRequest request : incomingRequests(sender.uuid())) {
            if (request.requesterName().equalsIgnoreCase(name)) {
                return request.requester();
            }
        }
        return null;
    }

    // ----- Commands ----------------------------------------------------------

    /** {@code /tp} opens the UI; {@code /tp <player>} force-teleports the executor to a player. */
    private final class TpCommand extends MysticCommand {
        TpCommand() {
            super(TeleportationModule.this.core, "tp", "Teleport to a player.");
            requirePermission(Permissions.TELEPORT_TP);
            addUsageVariant(new TpTargetVariant());
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            openTpaUi(sender.player().orElseThrow());
        }
    }

    private final class TpTargetVariant extends MysticCommand {
        private final RequiredArg<String> target = withRequiredArg("player", "Target player",
                ArgTypes.STRING).suggest(visiblePlayerSuggestions());

        TpTargetVariant() {
            super(TeleportationModule.this.core, "Teleport to a player.");
            requirePermission(Permissions.TELEPORT_TP);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            PlayerRef targetPlayer = core.platform().findPlayerByName(sender.get(target))
                    .filter(ref -> core.vanish().canSee(sender.uuid(), ref.getUuid()))
                    .orElse(null);
            if (targetPlayer == null) {
                sender.replyKey("player-not-found");
                return;
            }
            if (targetPlayer.getUuid().equals(sender.uuid())) {
                sender.replyKey("teleport-here-self");
                return;
            }
            core.getTeleportService().teleport(sender.player().orElseThrow(), TeleportRequest.builder()
                    .type("tp")
                    .targetPlayer(targetPlayer.getUuid())
                    .warmupSeconds(0)
                    .cooldownSeconds(0)
                    .build())
                    .thenAccept(result -> {
                        if (result != TeleportService.Result.SUCCESS) {
                            sender.replyKey("teleport-to-failed", Map.of(
                                    "player", targetPlayer.getUsername(),
                                    "reason", result.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')));
                        }
                    });
        }
    }

    /** {@code /tpa} opens the UI; {@code /tpa <player>} sends a request (positional variant). */
    private final class TpaCommand extends MysticCommand {
        TpaCommand() {
            super(TeleportationModule.this.core, "tpa", "Request to teleport to a player.");
            requirePermission(Permissions.TELEPORT_TPA);
            addUsageVariant(new TpaTargetVariant());
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            openTpaUi(sender.player().orElseThrow());
        }
    }

    private final class TpaTargetVariant extends MysticCommand {
        private final RequiredArg<String> target = withRequiredArg("player", "Target player",
                ArgTypes.STRING).suggest(visiblePlayerSuggestions());

        TpaTargetVariant() {
            super(TeleportationModule.this.core, "Request to teleport to a player.");
            requirePermission(Permissions.TELEPORT_TPA);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            requestTo(sender, core.platform().findPlayerByName(sender.get(target)).orElse(null), true);
        }
    }

    /** {@code /tpahere} opens the UI; {@code /tpahere <player>} asks them to come (positional variant). */
    private final class TpaHereCommand extends MysticCommand {
        TpaHereCommand() {
            super(TeleportationModule.this.core, "tpahere", "Request that a player teleports to you.");
            requirePermission(Permissions.TELEPORT_TPA);
            addUsageVariant(new TpaHereTargetVariant());
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            openTpaUi(sender.player().orElseThrow());
        }
    }

    private final class TpaHereTargetVariant extends MysticCommand {
        private final RequiredArg<String> target = withRequiredArg("player", "Target player",
                ArgTypes.STRING).suggest(visiblePlayerSuggestions());

        TpaHereTargetVariant() {
            super(TeleportationModule.this.core, "Request that a player teleports to you.");
            requirePermission(Permissions.TELEPORT_TPA);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            requestTo(sender, core.platform().findPlayerByName(sender.get(target)).orElse(null), false);
        }
    }

    /** {@code /tpaccept} accepts the most recent; {@code /tpaccept <player>} a specific one. */
    private final class TpAcceptCommand extends MysticCommand {
        TpAcceptCommand() {
            super(TeleportationModule.this.core, "tpaccept", "Accept a teleport request.");
            requirePermission(Permissions.TELEPORT_TPA);
            addUsageVariant(new TpAcceptNamedVariant());
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            Optional<PendingRequest> accepted = acceptRequest(sender.player().orElseThrow(), null);
            sender.replyKey(accepted.isPresent()
                    ? "teleport-request-accepted"
                    : "teleport-request-none");
        }
    }

    private final class TpAcceptNamedVariant extends MysticCommand {
        private final RequiredArg<String> from = withRequiredArg("player", "Requesting player",
                ArgTypes.STRING).suggest(pendingRequesterSuggestions());

        TpAcceptNamedVariant() {
            super(TeleportationModule.this.core, "Accept a specific teleport request.");
            requirePermission(Permissions.TELEPORT_TPA);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            UUID requester = resolveRequesterByName(sender, sender.get(from));
            if (requester == null) {
                sender.replyKey("teleport-request-none-from");
                return;
            }
            Optional<PendingRequest> accepted = acceptRequest(sender.player().orElseThrow(), requester);
            sender.replyKey(accepted.isPresent()
                    ? "teleport-request-accepted"
                    : "teleport-request-none");
        }
    }

    /** {@code /tpdeny} denies the most recent; {@code /tpdeny <player>} a specific one. */
    private final class TpDenyCommand extends MysticCommand {
        TpDenyCommand() {
            super(TeleportationModule.this.core, "tpdeny", "Deny a teleport request.");
            requirePermission(Permissions.TELEPORT_TPA);
            addUsageVariant(new TpDenyNamedVariant());
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            Optional<PendingRequest> denied = denyRequest(sender.player().orElseThrow(), null);
            sender.replyKey(denied.isPresent()
                    ? "teleport-request-denied"
                    : "teleport-request-none");
        }
    }

    private final class TpDenyNamedVariant extends MysticCommand {
        private final RequiredArg<String> from = withRequiredArg("player", "Requesting player",
                ArgTypes.STRING).suggest(pendingRequesterSuggestions());

        TpDenyNamedVariant() {
            super(TeleportationModule.this.core, "Deny a specific teleport request.");
            requirePermission(Permissions.TELEPORT_TPA);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            UUID requester = resolveRequesterByName(sender, sender.get(from));
            if (requester == null) {
                sender.replyKey("teleport-request-none-from");
                return;
            }
            Optional<PendingRequest> denied = denyRequest(sender.player().orElseThrow(), requester);
            sender.replyKey(denied.isPresent()
                    ? "teleport-request-denied"
                    : "teleport-request-none");
        }
    }

    private final class TpCancelCommand extends MysticCommand {
        TpCancelCommand() {
            super(TeleportationModule.this.core, "tpcancel", "Cancel your outgoing teleport requests.");
            requirePermission(Permissions.TELEPORT_TPA);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            boolean removed = false;
            for (LinkedHashMap<UUID, PendingRequest> inbound : pending.values()) {
                synchronized (inbound) {
                    removed |= inbound.remove(sender.uuid()) != null;
                }
            }
            sender.replyKey(removed ? "teleport-request-cancelled" : "teleport-request-none-outgoing");
        }
    }

    /** {@code /tptoggle} toggles whether other players may send you TPA requests. */
    private final class TpToggleCommand extends MysticCommand {
        TpToggleCommand() {
            super(TeleportationModule.this.core, "tptoggle", "Toggle incoming teleport requests.");
            requirePermission(Permissions.TELEPORT_TPA);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            boolean accepting = toggleAcceptsTeleportRequests(sender.uuid());
            sender.replyKey(accepting ? "teleport-requests-enabled" : "teleport-requests-disabled");
        }
    }

    /** {@code /tphere <player>} — force-teleports one player to the executor (admin). */
    private final class TpHereForceCommand extends MysticCommand {
        private final RequiredArg<String> target = withRequiredArg("player", "Target player",
                ArgTypes.STRING).suggest(visiblePlayerSuggestions());

        TpHereForceCommand() {
            super(TeleportationModule.this.core, "tphere", "Teleport a player to you.");
            requirePermission(Permissions.TELEPORT_TPHERE);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            PlayerRef targetPlayer = core.platform().findPlayerByName(sender.get(target))
                    .filter(ref -> core.vanish().canSee(sender.uuid(), ref.getUuid()))
                    .orElse(null);
            if (targetPlayer == null) {
                sender.replyKey("player-not-found");
                return;
            }
            if (targetPlayer.getUuid().equals(sender.uuid())) {
                sender.replyKey("teleport-here-self");
                return;
            }
            core.getTeleportService().teleport(targetPlayer, TeleportRequest.builder()
                    .type("tphere")
                    .targetPlayer(sender.uuid())
                    .warmupSeconds(0)
                    .cooldownSeconds(0)
                    .build())
                    .thenAccept(result -> {
                        if (result == TeleportService.Result.SUCCESS) {
                            sender.replyKey("teleport-here-success",
                                    Map.of("player", targetPlayer.getUsername()));
                            core.getMessageService().sendKey(targetPlayer, "teleport-here-target",
                                    Map.of("player", sender.name()));
                        } else {
                            sender.replyKey("teleport-here-failed", Map.of(
                                    "player", targetPlayer.getUsername(),
                                    "reason", result.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')));
                        }
                    });
        }
    }

    /** {@code /tpall} — teleports every online player to the executor (admin). */
    private final class TpAllCommand extends MysticCommand {
        TpAllCommand() {
            super(TeleportationModule.this.core, "tpall", "Teleport all players to you.");
            requirePermission(Permissions.TELEPORT_TPALL);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            UUID executor = sender.uuid();
            int moved = 0;
            for (PlayerRef online : core.platform().onlinePlayers()) {
                if (online.getUuid().equals(executor)) {
                    continue;
                }
                core.getTeleportService().teleport(online, TeleportRequest.builder()
                        .type("tpall").targetPlayer(executor).build());
                core.getMessageService().sendKey(online, "teleport-here-target",
                        Map.of("player", sender.name()));
                moved++;
            }
            if (moved == 0) {
                sender.replyKey("teleport-all-none");
            } else {
                sender.replyKey("teleport-all-started", Map.of(
                        "count", Integer.toString(moved),
                        "plural", moved == 1 ? "" : "s"));
            }
        }
    }

    /** {@code /top} — teleports the executor above the highest block in their current column. */
    private final class TopCommand extends MysticCommand {
        TopCommand() {
            super(TeleportationModule.this.core, "top", "Teleport to the highest block above you.");
            requirePermission(Permissions.TELEPORT_TOP);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            PlayerRef player = sender.player().orElseThrow();
            core.platform().topLocation(player).thenAccept(destination -> {
                if (destination.isEmpty()) {
                    sender.replyKey("teleport-top-unavailable");
                    return;
                }
                core.getTeleportService().teleport(player, TeleportRequest.builder()
                        .type("top")
                        .target(destination.get())
                        .warmupSeconds(0)
                        .cooldownSeconds(0)
                        .build());
            });
        }
    }

    private final class BackCommand extends MysticCommand {
        BackCommand() {
            super(TeleportationModule.this.core, "back", "Return to your previous location.");
            requirePermission(Permissions.TELEPORT_BACK);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            Optional<MysticLocation> back = core.getPlayerProfileService().getCached(sender.uuid())
                    .map(p -> p.getLastTeleportedLocation());
            if (back.isEmpty() || back.get() == null) {
                sender.replyKey("teleport-back-none");
                return;
            }
            core.getTeleportService().teleport(sender.player().orElseThrow(), TeleportRequest.builder()
                    .type("back")
                    .target(back.get())
                    .warmupSeconds(Math.max(0, config.backWarmupSeconds))
                    .cooldownKey("back")
                    .cooldownSeconds(Math.max(0, config.backCooldownSeconds))
                    .build());
        }
    }
}
