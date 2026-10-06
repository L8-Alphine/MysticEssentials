package org.hyzionstudios.mysticessentials.modules.teleportation;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.api.model.MysticLocation;
import org.hyzionstudios.mysticessentials.api.model.PlayerProfile;
import org.hyzionstudios.mysticessentials.api.model.TeleportRequest;
import org.hyzionstudios.mysticessentials.api.notification.Notification;
import org.hyzionstudios.mysticessentials.api.notification.NotificationAction;
import org.hyzionstudios.mysticessentials.api.notification.NotificationAudience;
import org.hyzionstudios.mysticessentials.api.notification.NotificationCategory;
import org.hyzionstudios.mysticessentials.api.notification.NotificationPriority;
import org.hyzionstudios.mysticessentials.api.rtp.RandomTeleportService;
import org.hyzionstudios.mysticessentials.api.service.TeleportService;
import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;
import org.hyzionstudios.mysticessentials.core.network.NetworkPlayerService.NetworkPlayer;
import org.hyzionstudios.mysticessentials.core.teleport.TeleportServiceImpl;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.modules.teleportation.rtp.RtpSubsystem;
import org.hyzionstudios.mysticessentials.platform.command.MysticArgTypes;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.SingleArgumentType;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerSetupConnectEvent;
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
 * <p><b>Cross-server (Redis).</b> A request to a player on another network
 * server is written to a Redis hash keyed by the target ({@code
 * teleport:tpa:pending:<target>}, one field per requester, TTL = expiry) and
 * announced over pub/sub. The target's server keeps a memory copy for the
 * notification and tab-completion, and <em>pulls</em> the hash whenever the
 * player looks at their requests (UI, {@code /tpaccept}, {@code /tpdeny}), so a
 * request is never lost to a missed pub/sub message or a server switch. On
 * accept, the mover is referred to the destination server through the engine's
 * {@code ClientReferral} (the client reconnects there itself) carrying a small
 * arrival payload that the destination reads on
 * {@code PlayerSetupConnectEvent.getReferralData()}; a copy is also kept in
 * Redis as a fallback. That server finishes the teleport once the player is
 * ready in a world, with no warmup or movement check.</p>
 *
 * <p>Loaded early (no hard dependencies) because Spawn, Homes, Warps, Player
 * Warps, and AFK Rewards consume the teleport service.</p>
 */
public final class TeleportationModule extends AbstractMysticModule {

    private static final String DATA_KEY = "teleportation";
    private static final String FAVORITES_KEY = "favorites";
    private static final String TPA_DISABLED_KEY = "tpaDisabled";
    private static final String REDIS_CHANNEL = "teleport:tpa";
    private static final String ARRIVAL_KEY_PREFIX = "teleport:tpa:arrival:";
    /** Redis hash per target: requester UUID -> stored request (see class note). */
    private static final String PENDING_KEY_PREFIX = "teleport:tpa:pending:";
    /** Marker inside the referral payload so another mod's referral data is never mistaken for ours. */
    private static final String REFERRAL_KIND = "mysticessentials:tpa-arrival";
    /** If {@code PlayerReadyEvent} never fires for an arriving player, poll instead after this long. */
    private static final long READY_FALLBACK_SECONDS = 8L;
    /** 500 ms polls while the arrived player's entity is not yet placed in a world. */
    private static final int ARRIVAL_ATTEMPTS = 40;

    /** target UUID -> (requester UUID -> pending request), insertion-ordered. */
    private final Map<UUID, LinkedHashMap<UUID, PendingRequest>> pending = new ConcurrentHashMap<>();
    private final Set<UUID> tpaDisabled = ConcurrentHashMap.newKeySet();
    /** requester UUID -> targets on other servers holding a Redis-stored request from them. */
    private final Map<UUID, Set<UUID>> outgoingRemote = new ConcurrentHashMap<>();
    /** moving player UUID -> accepted cross-server teleport waiting for that player to arrive here. */
    private final Map<UUID, Arrival> pendingArrivals = new ConcurrentHashMap<>();

    /**
     * Suggests the usernames of players with a request pending on the sender.
     * Held as one instance so it keeps a single client-side suggestion id.
     */
    private final SingleArgumentType<String> pendingRequesterArg = MysticArgTypes.dynamic(commandSender ->
            incomingRequests(commandSender.getUuid()).stream().map(PendingRequest::requesterName).toList());

    private TeleportationConfig config = new TeleportationConfig();
    private RtpSubsystem rtp;
    private boolean active;
    private final Consumer<String> redisHandler = this::handleRedisMessage;

    public TeleportationModule() {
        super("teleportation", "Teleportation", "1.0.0");
    }

    record PendingRequest(UUID requester, String requesterName, String requesterServerId,
            boolean requesterTeleports, Instant created) {
        boolean expired(Duration ttl) {
            return created.plus(ttl).isBefore(Instant.now());
        }

        /** @return whether the requester is on a different network server than {@code localServerId}. */
        boolean remote(String localServerId) {
            return requesterServerId != null && !requesterServerId.isBlank()
                    && !requesterServerId.equals(localServerId);
        }
    }

    /** An accepted cross-server teleport whose mover has been referred to this server. */
    private record Arrival(UUID target, String teleportType, long expiresAtMillis) {
        boolean expired() {
            return System.currentTimeMillis() > expiresAtMillis;
        }
    }

    private Duration requestTtl() {
        return Duration.ofSeconds(Math.max(1, config.requestExpirySeconds));
    }

    @Override
    public void onEnable() {
        loadConfig();
        active = true;
        core.redis().subscribe(REDIS_CHANNEL, redisHandler);
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

        // Random Teleport subsystem: registers /rtp and /rtpadmin through the
        // module's tracked registerCommand so they drop on module disable.
        rtp = new RtpSubsystem(core);
        rtp.enable(this::registerCommand);
        // The referral payload arrives with the connection itself, before the
        // player object exists; the Redis record is only consulted if it is absent.
        registerEvent(PlayerSetupConnectEvent.class, (PlayerSetupConnectEvent event) ->
                stashReferralArrival(event));
        registerEvent(PlayerConnectEvent.class, (PlayerConnectEvent event) -> {
            rtp.onPlayerConnect(event.getPlayerRef());
            stashCrossServerArrival(event.getPlayerRef());
        });
        // A player referred here for an accepted request is teleported once the
        // engine reports them ready in a world; at PlayerConnectEvent the entity is
        // not placed yet and a teleport would silently fail. Keyed event: global.
        registerGlobalEvent(PlayerReadyEvent.class, (PlayerReadyEvent event) ->
                core.platform().playerRefOf(event.getPlayerRef())
                        .ifPresent(ref -> completeArrival(ref.getUuid())));
    }

    @Override
    public void onReload() {
        loadConfig();
        if (rtp != null) {
            rtp.reload();
        }
    }

    @Override
    public void onDisable() {
        active = false;
        core.redis().unsubscribe(REDIS_CHANNEL, redisHandler);
        pending.clear();
        tpaDisabled.clear();
        outgoingRemote.clear();
        pendingArrivals.clear();
        if (rtp != null) {
            rtp.disable();
        }
    }

    /** @return the Random Teleport service, or {@code null} if the module is not enabled. */
    public RandomTeleportService getRandomTeleportService() {
        return rtp == null ? null : rtp.service();
    }

    private void loadConfig() {
        config = core.configManager().loadModuleConfig(id(), TeleportationConfig.class,
                new TeleportationConfig());
        if (core.getTeleportService() instanceof TeleportServiceImpl service) {
            service.configure(config);
        }
    }

    // ----- Request state (shared by commands and the UI) ----------------------

    /**
     * Pending, unexpired inbound requests for {@code target}, oldest first — the
     * memory copy only. Safe from any thread (tab-completion runs on Netty IO).
     */
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

    /**
     * {@link #incomingRequests} after pulling requests other servers stored for
     * {@code target} in Redis. Use wherever the player acts on their requests.
     */
    List<PendingRequest> syncIncomingRequests(UUID target) {
        pullRemoteRequests(target);
        return incomingRequests(target);
    }

    /**
     * Merges the Redis-stored requests for {@code target} into the memory copy.
     * Entries already known (delivered by pub/sub) are left untouched so their
     * order and timestamps hold; expired or malformed fields are dropped from
     * Redis. A remote clock ahead of ours is clamped to "now".
     */
    private void pullRemoteRequests(UUID target) {
        if (target == null || !crossServerEnabled()) {
            return;
        }
        String key = PENDING_KEY_PREFIX + target;
        Map<String, String> stored = core.redis().cacheHashGetAll(key);
        if (stored.isEmpty()) {
            return;
        }
        if (!acceptsTeleportRequests(target)) {
            core.redis().cacheHashRemove(key, stored.keySet().toArray(String[]::new));
            return;
        }
        Duration ttl = requestTtl();
        long now = System.currentTimeMillis();
        List<String> stale = new ArrayList<>();
        List<PendingRequest> pulled = new ArrayList<>();
        for (Map.Entry<String, String> entry : stored.entrySet()) {
            UUID requester = parseUuid(entry.getKey());
            PendingRequest request = null;
            if (requester != null && !requester.equals(target)) {
                try {
                    JsonObject object = Json.asObject(Json.parse(entry.getValue()));
                    long created = Math.min(longValue(object, "created", now), now);
                    request = new PendingRequest(requester, string(object, "requesterName"),
                            string(object, "requesterServerId"), bool(object, "requesterTeleports"),
                            Instant.ofEpochMilli(created));
                } catch (RuntimeException ignored) {
                    // Malformed field: dropped below.
                }
            }
            if (request == null || request.requesterName().isBlank()
                    || request.requesterServerId().isBlank() || request.expired(ttl)) {
                stale.add(entry.getKey());
            } else {
                pulled.add(request);
            }
        }
        if (!stale.isEmpty()) {
            core.redis().cacheHashRemove(key, stale.toArray(String[]::new));
        }
        if (pulled.isEmpty()) {
            return;
        }
        pulled.sort(Comparator.comparing(PendingRequest::created));
        LinkedHashMap<UUID, PendingRequest> inbound =
                pending.computeIfAbsent(target, uuid -> new LinkedHashMap<>());
        synchronized (inbound) {
            for (PendingRequest request : pulled) {
                inbound.putIfAbsent(request.requester(), request);
            }
        }
    }

    /** Sends a request; replaces any previous one from the same requester. */
    boolean sendRequest(PlayerRef requester, PlayerRef target, boolean requesterTeleports) {
        return sendLocalRequest(requester, target, core.networkPlayers().localServerId(), requesterTeleports);
    }

    /** Sends to a local or Redis-discovered player. */
    boolean sendRequest(PlayerRef requester, UUID targetId, String targetName, boolean requesterTeleports) {
        if (targetId == null) {
            return false;
        }
        PlayerRef local = core.platform().findPlayer(targetId).orElse(null);
        if (local != null) {
            return sendLocalRequest(requester, local, core.networkPlayers().localServerId(), requesterTeleports);
        }
        if (!crossServerEnabled()) {
            return false;
        }
        NetworkPlayer target = core.networkPlayers().find(targetId).orElse(null);
        if (target == null || target.vanished()) {
            return false;
        }
        long created = System.currentTimeMillis();
        // Durable copy first: the target's server pulls this whenever the player
        // looks at their requests, so the pub/sub announcement is only a poke.
        JsonObject stored = new JsonObject();
        stored.addProperty("requesterName", requester.getUsername());
        stored.addProperty("requesterServerId", core.networkPlayers().localServerId());
        stored.addProperty("requesterTeleports", requesterTeleports);
        stored.addProperty("created", created);
        core.redis().cacheHashSet(PENDING_KEY_PREFIX + target.uuid(), requester.getUuid().toString(),
                Json.toString(stored), requestTtl().toSeconds());
        outgoingRemote.computeIfAbsent(requester.getUuid(), uuid -> ConcurrentHashMap.newKeySet())
                .add(target.uuid());

        JsonObject message = baseMessage("request", target.serverId());
        message.addProperty("requester", requester.getUuid().toString());
        message.addProperty("requesterName", requester.getUsername());
        message.addProperty("requesterServerId", core.networkPlayers().localServerId());
        message.addProperty("target", target.uuid().toString());
        message.addProperty("targetName", targetName == null ? target.username() : targetName);
        message.addProperty("requesterTeleports", requesterTeleports);
        message.addProperty("created", created);
        core.redis().publish(REDIS_CHANNEL, Json.toString(message));
        return true;
    }

    private boolean sendLocalRequest(PlayerRef requester, PlayerRef target,
            String requesterServerId, boolean requesterTeleports) {
        if (!acceptsTeleportRequests(target.getUuid())) {
            core.getMessageService().sendKey(requester, "teleport-requests-disabled-target",
                    Map.of("player", target.getUsername()));
            return false;
        }
        addPending(target.getUuid(), new PendingRequest(requester.getUuid(),
                requester.getUsername(), requesterServerId, requesterTeleports, Instant.now()));
        notifyIncoming(target, requester.getUsername(), requesterServerId, requesterTeleports);
        return true;
    }

    private void addPending(UUID target, PendingRequest request) {
        LinkedHashMap<UUID, PendingRequest> inbound =
                pending.computeIfAbsent(target, uuid -> new LinkedHashMap<>());
        synchronized (inbound) {
            inbound.remove(request.requester());
            inbound.put(request.requester(), request);
        }
    }

    private void notifyIncoming(PlayerRef target, String requesterName, String requesterServerId,
            boolean requesterTeleports) {
        boolean remote = requesterServerId != null && !requesterServerId.isBlank()
                && !requesterServerId.equals(core.networkPlayers().localServerId());
        String key = (requesterTeleports
                ? "teleport-request-incoming-to-you"
                : "teleport-request-incoming-to-them") + (remote ? "-remote" : "");
        Map<String, String> params = remote
                ? Map.of("player", requesterName, "server", requesterServerId)
                : Map.of("player", requesterName);
        notifyTeleport(target, "Teleport Request", key, params, NotificationPriority.IMPORTANT,
                NotificationAction.command("/tpaccept"), true);
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
        if (requesterRef.isPresent()) {
            if (request.requesterTeleports()) {
                teleportToPlayer(requesterRef.get(), target.getUuid(), "tpa");
            } else {
                teleportToPlayer(target, request.requester(), "tpahere");
            }
            notifyTeleport(requesterRef.get(), "Teleport Accepted", "teleport-request-accepted-by",
                    Map.of("player", target.getUsername()), NotificationPriority.LOW,
                    NotificationAction.none(), false);
            return Optional.of(request);
        }
        if (!crossServerEnabled()) {
            core.getMessageService().sendKey(target, "teleport-target-offline");
            return Optional.empty();
        }
        NetworkPlayer requesterNetwork = core.networkPlayers().find(request.requester()).orElse(null);
        if (requesterNetwork == null) {
            core.getMessageService().sendKey(target, "teleport-target-offline");
            return Optional.empty();
        }
        String local = core.networkPlayers().localServerId();
        boolean requesterMoves = request.requesterTeleports();
        UUID moving = requesterMoves ? request.requester() : target.getUuid();
        UUID destinationPlayer = requesterMoves ? target.getUuid() : request.requester();
        String sourceServer = requesterMoves ? requesterNetwork.serverId() : local;
        String destinationServer = requesterMoves ? local : requesterNetwork.serverId();
        if (!core.networkPlayers().hasEndpoint(destinationServer)) {
            // The most common misconfiguration: storage.redis.advertisedHost/Port
            // unset on the destination, so clients cannot be referred there.
            core.getMessageService().sendKey(target, "teleport-cross-server-unavailable",
                    Map.of("server", destinationServer));
            core.log(Level.WARNING, "[teleportation] Cannot complete cross-server request from "
                    + request.requesterName() + ": server '" + destinationServer
                    + "' advertises no host/port (storage.redis.advertisedHost/advertisedPort).");
            return Optional.empty();
        }
        if (!queueArrivalAndTransfer(moving, destinationPlayer, sourceServer, destinationServer,
                requesterMoves ? "tpa" : "tpahere")) {
            core.getMessageService().sendKey(target, "teleport-target-offline");
            return Optional.empty();
        }
        publishRemoteNotice(requesterNetwork.serverId(), request.requester(), "accepted",
                target.getUsername());
        if (!requesterMoves) {
            core.getMessageService().sendKey(target, "teleport-cross-server-transferring",
                    Map.of("server", destinationServer));
        }
        return Optional.of(request);
    }

    /** Denies a request for {@code target}; null {@code requester} = the most recent. */
    Optional<PendingRequest> denyRequest(PlayerRef target, UUID requester) {
        PendingRequest request = takeRequest(target.getUuid(), requester);
        if (request != null) {
            core.platform().findPlayer(request.requester()).ifPresentOrElse(ref ->
                            notifyTeleport(ref, "Teleport Denied", "teleport-request-denied-by",
                                    Map.of("player", target.getUsername()), NotificationPriority.LOW,
                                    NotificationAction.none(), false),
                    () -> publishRemoteNotice(request.requesterServerId(), request.requester(),
                            "denied", target.getUsername()));
        }
        return Optional.ofNullable(request);
    }

    private boolean crossServerEnabled() {
        return active && config.crossServer != null && config.crossServer.enabled
                && core.redis().isEnabled() && core.networkPlayers().isNetworked();
    }

    private JsonObject baseMessage(String type, String targetServerId) {
        JsonObject message = new JsonObject();
        message.addProperty("type", type);
        message.addProperty("targetServerId", targetServerId == null ? "" : targetServerId);
        return message;
    }

    private boolean queueArrivalAndTransfer(UUID movingPlayer, UUID destinationPlayer,
            String sourceServerId, String destinationServerId, String teleportType) {
        if (!core.networkPlayers().hasEndpoint(destinationServerId)) {
            return false;
        }
        JsonObject arrival = arrivalRecord(destinationServerId, destinationPlayer, teleportType);
        // Fallback copy: the primary carrier is the referral payload below.
        core.redis().cacheSet(ARRIVAL_KEY_PREFIX + movingPlayer, Json.toString(arrival),
                arrivalTimeoutSeconds());

        if (sourceServerId.equals(core.networkPlayers().localServerId())) {
            PlayerRef local = core.platform().findPlayer(movingPlayer).orElse(null);
            if (local != null && core.networkPlayers().referToServer(local, destinationServerId,
                    referralPayload(arrival))) {
                return true;
            }
            core.redis().cacheDelete(ARRIVAL_KEY_PREFIX + movingPlayer);
            return false;
        }
        JsonObject transfer = baseMessage("transfer", sourceServerId);
        transfer.addProperty("movingPlayer", movingPlayer.toString());
        transfer.addProperty("destinationServerId", destinationServerId);
        transfer.addProperty("target", destinationPlayer.toString());
        transfer.addProperty("teleportType", teleportType);
        core.redis().publish(REDIS_CHANNEL, Json.toString(transfer));
        log("Asked '" + sourceServerId + "' to transfer " + movingPlayer + " here for " + teleportType
                + " (it will refer the player to " + destinationServerId + ").");
        return true;
    }

    private JsonObject arrivalRecord(String destinationServerId, UUID destinationPlayer, String teleportType) {
        JsonObject arrival = new JsonObject();
        arrival.addProperty("kind", REFERRAL_KIND);
        arrival.addProperty("destinationServerId", destinationServerId);
        arrival.addProperty("target", destinationPlayer.toString());
        arrival.addProperty("teleportType", teleportType);
        arrival.addProperty("issued", System.currentTimeMillis());
        return arrival;
    }

    /** The arrival record as referral bytes (well under the engine's 4 KiB limit). */
    private static byte[] referralPayload(JsonObject arrival) {
        return Json.toString(arrival).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Claims an arrival that travelled with the connection: the referring server
     * put it in the {@code ClientReferral} and the client presented it here.
     *
     * <p>The client is the one presenting these bytes, so they are only trusted
     * when a server wrote a matching arrival record to Redis first — otherwise
     * anyone could connect with a hand-made payload and be teleported to any
     * player without consent. Payloads from other mods, or for another server,
     * are ignored.</p>
     */
    private void stashReferralArrival(PlayerSetupConnectEvent event) {
        if (!active || event.getUuid() == null || !crossServerEnabled()) {
            return;
        }
        byte[] data;
        try {
            if (!event.isReferralConnection()) {
                return;
            }
            data = event.getReferralData();
        } catch (Throwable ignored) {
            return;
        }
        if (data == null || data.length == 0) {
            return;
        }
        try {
            JsonObject arrival = Json.asObject(Json.parse(new String(data, StandardCharsets.UTF_8)));
            if (!REFERRAL_KIND.equals(string(arrival, "kind"))
                    || !core.networkPlayers().localServerId().equals(string(arrival, "destinationServerId"))) {
                return;
            }
            String key = ARRIVAL_KEY_PREFIX + event.getUuid();
            JsonObject record = verifiedArrivalRecord(key, arrival);
            if (record == null) {
                core.log(Level.WARNING, "[teleportation] Ignored a referral payload for " + event.getUsername()
                        + " that no server authorised (no matching arrival record in Redis).");
                return;
            }
            core.redis().cacheDelete(key);
            if (stashArrival(event.getUuid(), record)) {
                log("Arrival for " + event.getUsername() + " received in the referral payload ("
                        + string(record, "teleportType") + ").");
            }
        } catch (RuntimeException ignored) {
            // Not our payload.
        }
    }

    /**
     * The Redis arrival record for {@code key} if it agrees with what the client
     * presented (same destination, target and teleport type); null otherwise.
     * Only servers write these records, so a match proves a server issued it.
     */
    private JsonObject verifiedArrivalRecord(String key, JsonObject presented) {
        String raw = core.redis().cacheGet(key);
        if (raw == null) {
            return null;
        }
        try {
            JsonObject record = Json.asObject(Json.parse(raw));
            boolean matches = string(record, "destinationServerId").equals(string(presented, "destinationServerId"))
                    && string(record, "target").equals(string(presented, "target"))
                    && string(record, "teleportType").equals(string(presented, "teleportType"));
            return matches ? record : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Parks an arrival record for {@code uuid}; false when it names another server or is malformed. */
    private boolean stashArrival(UUID uuid, JsonObject arrival) {
        if (!core.networkPlayers().localServerId().equals(string(arrival, "destinationServerId"))) {
            return false;
        }
        UUID target = parseUuid(string(arrival, "target"));
        if (target == null) {
            return false;
        }
        String teleportType = string(arrival, "teleportType");
        pendingArrivals.put(uuid, new Arrival(target, teleportType.isBlank() ? "tpa" : teleportType,
                System.currentTimeMillis() + arrivalTimeoutSeconds() * 1000L));
        // PlayerReadyEvent completes this; if it never fires, the poll takes over.
        core.scheduler().runLater(() -> completeArrival(uuid), READY_FALLBACK_SECONDS, TimeUnit.SECONDS);
        return true;
    }

    private int arrivalTimeoutSeconds() {
        return Math.max(5, config.crossServer == null ? 30 : config.crossServer.arrivalTimeoutSeconds);
    }

    /**
     * Fallback for a connection that carried no referral payload (a peer on an
     * older build, or a client that dropped it): claims the Redis arrival record
     * instead. A record naming a different destination is left alone (a stale
     * entry from a transfer that never happened here).
     */
    private void stashCrossServerArrival(PlayerRef player) {
        UUID uuid = player.getUuid();
        if (!crossServerEnabled() || pendingArrivals.containsKey(uuid)) {
            return;
        }
        String key = ARRIVAL_KEY_PREFIX + uuid;
        String raw = core.redis().cacheGet(key);
        if (raw == null) {
            return;
        }
        try {
            JsonObject arrival = Json.asObject(Json.parse(raw));
            if (!core.networkPlayers().localServerId().equals(string(arrival, "destinationServerId"))) {
                return;
            }
            core.redis().cacheDelete(key);
            if (stashArrival(uuid, arrival)) {
                log("Arrival for " + player.getUsername() + " taken from the Redis record ("
                        + string(arrival, "teleportType") + ").");
            }
        } catch (RuntimeException ignored) {
            core.redis().cacheDelete(key);
        }
    }

    /** Runs the parked arrival for {@code uuid} at most once (ready event or fallback). */
    private void completeArrival(UUID uuid) {
        Arrival arrival = pendingArrivals.remove(uuid);
        if (arrival == null || !active || arrival.expired()) {
            return;
        }
        int delay = Math.max(0, config.crossServer == null ? 1 : config.crossServer.arrivalDelaySeconds);
        core.scheduler().runLater(() -> attemptArrivalTeleport(uuid, arrival, ARRIVAL_ATTEMPTS),
                delay, TimeUnit.SECONDS);
    }

    /**
     * Finishes an accepted cross-server teleport for a player who has just
     * arrived. Consent was given on the other server, so there is no warmup and
     * no movement/damage cancellation — a freshly spawned player settles into
     * the world for a moment and a warmup would cancel on that. Retries briefly
     * while the entity is not yet placed in a world.
     */
    private void attemptArrivalTeleport(UUID uuid, Arrival arrival, int attemptsLeft) {
        if (!active) {
            return;
        }
        PlayerRef moving = core.platform().findPlayer(uuid).orElse(null);
        if (moving == null) {
            return; // left again before the teleport could run
        }
        if (!core.platform().isInWorld(moving)) {
            if (attemptsLeft > 0 && !arrival.expired()) {
                core.scheduler().runLater(() -> attemptArrivalTeleport(uuid, arrival, attemptsLeft - 1),
                        500L, TimeUnit.MILLISECONDS);
            } else {
                core.log(Level.WARNING, "[teleportation] Cross-server arrival for " + moving.getUsername()
                        + " gave up: the player never entered a world.");
            }
            return;
        }
        if (core.platform().findPlayer(arrival.target()).isEmpty()) {
            core.getMessageService().sendKey(moving, "teleport-target-offline");
            return;
        }
        boolean consentTeleport = "tpa".equals(arrival.teleportType()) || "tpahere".equals(arrival.teleportType());
        int cooldown = consentTeleport ? Math.max(0, config.tpaCooldownSeconds) : 0;
        TeleportRequest request = TeleportRequest.builder()
                .type(arrival.teleportType())
                .targetPlayer(arrival.target())
                .warmupSeconds(0)
                .cancelOnMove(false)
                .cancelOnDamage(false)
                .cooldownSeconds(0)
                .build();
        core.getTeleportService().teleport(moving, request).thenAccept(result -> {
            if (result == TeleportService.Result.SUCCESS) {
                // The usual TPA cooldown still applies afterwards; it was skipped
                // as a precondition so a stale local cooldown cannot strand the mover.
                if (cooldown > 0 && !moving.hasPermission(Permissions.TELEPORT_BYPASS_COOLDOWN)) {
                    core.cooldowns().set(uuid, "tpa", cooldown);
                }
            } else {
                core.log(Level.WARNING, "[teleportation] Cross-server arrival teleport for "
                        + moving.getUsername() + " failed: " + result);
            }
        });
    }

    /**
     * Admin force-teleport across the network: moves {@code moving} (on
     * {@code movingServer}) to {@code destinationPlayer} (on {@code destinationServer})
     * through the same arrival record {@code /tpaccept} uses. One of the two
     * servers is always this one.
     *
     * @return false when the destination advertises no address or the transfer
     *         could not be queued; the sender has been told why
     */
    private boolean forceTeleportAcrossNetwork(MysticCommandSender sender, UUID moving, String movingServer,
            UUID destinationPlayer, String destinationServer, String teleportType) {
        if (!core.networkPlayers().hasEndpoint(destinationServer)) {
            sender.replyKey("teleport-cross-server-unavailable", Map.of("server", destinationServer));
            core.log(Level.WARNING, "[teleportation] Cannot transfer to '" + destinationServer
                    + "': that server advertises no host/port (storage.redis.advertisedHost/advertisedPort).");
            return false;
        }
        if (!queueArrivalAndTransfer(moving, destinationPlayer, movingServer, destinationServer, teleportType)) {
            sender.replyKey("teleport-target-offline");
            return false;
        }
        return true;
    }

    /** A player online on another network server; empty when local, offline, or remotely vanished. */
    private Optional<NetworkPlayer> remoteNetworkPlayer(String name) {
        if (!crossServerEnabled()) {
            return Optional.empty();
        }
        return core.networkPlayers().findByName(name)
                .filter(player -> !player.local(core.networkPlayers().localServerId()))
                .filter(player -> !player.vanished());
    }

    private void publishRemoteNotice(String serverId, UUID player, String status, String actorName) {
        if (!crossServerEnabled() || serverId == null || serverId.isBlank()) {
            return;
        }
        JsonObject notice = baseMessage("notice", serverId);
        notice.addProperty("player", player.toString());
        notice.addProperty("status", status);
        notice.addProperty("actorName", actorName);
        core.redis().publish(REDIS_CHANNEL, Json.toString(notice));
    }

    private void handleRedisMessage(String raw) {
        if (!crossServerEnabled() || raw == null || raw.isBlank()) {
            return;
        }
        try {
            JsonObject message = Json.asObject(Json.parse(raw));
            String destination = string(message, "targetServerId");
            if (!destination.isBlank() && !destination.equals(core.networkPlayers().localServerId())) {
                return;
            }
            switch (string(message, "type")) {
                case "request" -> handleRemoteRequest(message);
                case "transfer" -> handleRemoteTransfer(message);
                case "notice" -> handleRemoteNotice(message);
                case "cancel" -> removeOutgoingRequests(parseUuid(string(message, "requester")));
                default -> {
                }
            }
        } catch (RuntimeException ignored) {
            // Redis is an untrusted transport; malformed module payloads are ignored.
        }
    }

    private void handleRemoteRequest(JsonObject message) {
        UUID targetId = parseUuid(string(message, "target"));
        UUID requesterId = parseUuid(string(message, "requester"));
        PlayerRef target = targetId == null ? null : core.platform().findPlayer(targetId).orElse(null);
        if (target == null || requesterId == null) {
            return;
        }
        String requesterName = string(message, "requesterName");
        String requesterServerId = string(message, "requesterServerId");
        if (!acceptsTeleportRequests(targetId)) {
            core.redis().cacheHashRemove(PENDING_KEY_PREFIX + targetId, requesterId.toString());
            publishRemoteNotice(requesterServerId, requesterId, "disabled", target.getUsername());
            return;
        }
        boolean requesterTeleports = bool(message, "requesterTeleports");
        // The announcement has just arrived, so it is fresh by definition; using
        // our own clock keeps the expiry right even if the origin's clock drifts.
        addPending(targetId, new PendingRequest(requesterId, requesterName,
                requesterServerId, requesterTeleports, Instant.now()));
        notifyIncoming(target, requesterName, requesterServerId, requesterTeleports);
    }

    private void handleRemoteTransfer(JsonObject message) {
        UUID movingId = parseUuid(string(message, "movingPlayer"));
        String destination = string(message, "destinationServerId");
        if (movingId == null || destination.isBlank()) {
            return;
        }
        UUID target = parseUuid(string(message, "target"));
        String teleportType = string(message, "teleportType");
        byte[] payload = target == null ? null
                : referralPayload(arrivalRecord(destination, target, teleportType.isBlank() ? "tpa" : teleportType));
        core.platform().findPlayer(movingId).ifPresent(player -> {
            core.getMessageService().sendKey(player, "teleport-cross-server-transferring",
                    Map.of("server", destination));
            if (!core.networkPlayers().referToServer(player, destination, payload)) {
                core.getMessageService().sendKey(player, "teleport-cross-server-unavailable",
                        Map.of("server", destination));
            }
        });
    }

    private void handleRemoteNotice(JsonObject message) {
        UUID playerId = parseUuid(string(message, "player"));
        PlayerRef player = playerId == null ? null : core.platform().findPlayer(playerId).orElse(null);
        if (player == null) {
            return;
        }
        String actor = string(message, "actorName");
        switch (string(message, "status")) {
            case "accepted" -> notifyTeleport(player, "Teleport Accepted", "teleport-request-accepted-by",
                    Map.of("player", actor), NotificationPriority.LOW, NotificationAction.none(), false);
            case "denied" -> notifyTeleport(player, "Teleport Denied", "teleport-request-denied-by",
                    Map.of("player", actor), NotificationPriority.LOW, NotificationAction.none(), false);
            case "disabled" -> core.getMessageService().sendKey(player, "teleport-requests-disabled-target",
                    Map.of("player", actor));
            default -> {
            }
        }
    }

    private boolean removeOutgoingRequests(UUID requester) {
        if (requester == null) {
            return false;
        }
        boolean removed = false;
        for (LinkedHashMap<UUID, PendingRequest> inbound : pending.values()) {
            synchronized (inbound) {
                removed |= inbound.remove(requester) != null;
            }
        }
        return removed;
    }

    private static String string(JsonObject object, String key) {
        try {
            return object.has(key) ? object.get(key).getAsString() : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static boolean bool(JsonObject object, String key) {
        try {
            return object.has(key) && object.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static long longValue(JsonObject object, String key, long fallback) {
        try {
            return object.has(key) ? object.get(key).getAsLong() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static UUID parseUuid(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void notifyTeleport(PlayerRef player, String title, String messageKey,
            Map<String, String> params, NotificationPriority priority, NotificationAction action,
            boolean history) {
        if (core.notifications() == null) {
            core.getMessageService().sendKey(player, messageKey, params);
            return;
        }
        String message = core.getMessageService().plainFromKey(messageKey, params);
        Notification.Builder builder = Notification.builder()
                .category(NotificationCategory.TELEPORT)
                .priority(priority)
                .title(title)
                .subtitle(message)
                .message(message)
                .action(action)
                .storeInHistory(history)
                .source("mysticessentials:teleportation");
        if (history) {
            builder.expiration(requestTtl());
        }
        core.notifications().send(builder.build(), NotificationAudience.player(player.getUuid()));
    }

    private PendingRequest takeRequest(UUID target, UUID requester) {
        pullRemoteRequests(target);
        LinkedHashMap<UUID, PendingRequest> inbound = pending.get(target);
        if (inbound == null) {
            return null;
        }
        PendingRequest taken;
        synchronized (inbound) {
            Duration ttl = requestTtl();
            inbound.values().removeIf(request -> request.expired(ttl));
            if (inbound.isEmpty()) {
                return null;
            }
            if (requester != null) {
                taken = inbound.remove(requester);
            } else {
                UUID last = null;
                for (UUID key : inbound.keySet()) {
                    last = key;
                }
                taken = inbound.remove(last);
            }
        }
        if (taken != null && taken.remote(core.networkPlayers().localServerId()) && crossServerEnabled()) {
            core.redis().cacheHashRemove(PENDING_KEY_PREFIX + target, taken.requester().toString());
        }
        return taken;
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

    /**
     * Pulls the requests other network servers stored in Redis (so a request
     * sent while this player was elsewhere, or whose announcement was missed,
     * still shows up) off the world thread, then opens the page, which only
     * reads the memory copy.
     */
    void openTpaUi(PlayerRef player) {
        core.scheduler().runLater(() -> {
            pullRemoteRequests(player.getUuid());
            core.platform().openPage(player, new TpaPages.TpaPage(core, this, player));
        }, 0, TimeUnit.MILLISECONDS);
    }

    private void requestTo(MysticCommandSender sender, String targetName, boolean requesterTeleports) {
        if (!sender.isPlayer()) {
            sender.replyKey("player-only");
            return;
        }
        NetworkPlayer target = core.networkPlayers().findByName(targetName).orElse(null);
        if (target == null || target.vanished()
                || (target.local(core.networkPlayers().localServerId())
                        && !core.vanish().canSee(sender.uuid(), target.uuid()))) {
            sender.replyKey("player-not-found");
            return;
        }
        if (sender.uuid().equals(target.uuid())) {
            sender.replyKey("teleport-request-self");
            return;
        }
        if (sendRequest(sender.player().orElseThrow(), target.uuid(), target.username(), requesterTeleports)) {
            sender.replyKey("teleport-request-sent", Map.of("player", target.username()));
        } else {
            sender.replyKey("teleport-target-offline");
        }
    }

    private UUID resolveRequesterByName(MysticCommandSender sender, String name) {
        for (PendingRequest request : syncIncomingRequests(sender.uuid())) {
            if (request.requesterName().equalsIgnoreCase(name)) {
                return request.requester();
            }
        }
        return null;
    }

    // ----- Commands ----------------------------------------------------------

    /**
     * {@code /tp} opens the UI; {@code /tp <player>} force-teleports the executor
     * to a player; {@code /tp world <player> <world>} sends a player to another
     * world. Subcommands are matched before usage variants, so the {@code world}
     * literal never collides with the {@code <player>} variant.
     */
    private final class TpCommand extends MysticCommand {
        TpCommand() {
            super(TeleportationModule.this.core, "tp", "Teleport to a player.");
            requirePermission(Permissions.TELEPORT_TP);
            addUsageVariant(new TpTargetVariant());
            addSubCommand(new TpWorldCommand());
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
                MysticArgTypes.NETWORK_PLAYER_NAME);

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
                // On another network server: go there, then finish the move on arrival.
                NetworkPlayer remote = remoteNetworkPlayer(sender.get(target)).orElse(null);
                if (remote == null) {
                    sender.replyKey("player-not-found");
                } else if (forceTeleportAcrossNetwork(sender, sender.uuid(),
                        core.networkPlayers().localServerId(), remote.uuid(), remote.serverId(), "tp")) {
                    sender.replyKey("teleport-cross-server-transferring", Map.of("server", remote.serverId()));
                }
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
                                    "reason", result.name().toLowerCase(Locale.ROOT).replace('_', ' ')));
                        }
                    });
        }
    }

    /**
     * {@code /tp world <player> <world>} — moves a player to another world,
     * landing them on that world's configured spawn point. Admin-style: no
     * warmup, no cooldown, and no consent from the target.
     */
    private final class TpWorldCommand extends MysticCommand {
        private final RequiredArg<String> target = withRequiredArg("player", "Player to move",
                MysticArgTypes.PLAYER_NAME);
        private final RequiredArg<String> world = withRequiredArg("world", "Destination world",
                MysticArgTypes.WORLD_NAME);

        TpWorldCommand() {
            super(TeleportationModule.this.core, "world", "Teleport a player to another world.");
            requirePermission(Permissions.TELEPORT_TP_WORLD);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            PlayerRef targetPlayer = core.platform().findPlayerByName(sender.get(target))
                    .filter(ref -> core.vanish().canSee(sender.uuid(), ref.getUuid()))
                    .orElse(null);
            if (targetPlayer == null) {
                sender.replyKey("player-not-found");
                return;
            }
            String worldName = sender.get(world);
            if (core.platform().world(worldName).isEmpty()) {
                sender.replyKey("teleport-world-unknown", Map.of("world", worldName));
                return;
            }
            if (worldName.equalsIgnoreCase(core.platform().worldNameOf(targetPlayer).orElse(""))) {
                sender.replyKey("teleport-world-already", Map.of(
                        "player", targetPlayer.getUsername(),
                        "world", worldName));
                return;
            }
            MysticLocation destination = core.platform().worldSpawn(worldName, targetPlayer.getUuid())
                    .orElse(null);
            if (destination == null) {
                sender.replyKey("teleport-world-no-spawn", Map.of("world", worldName));
                return;
            }
            core.getTeleportService().teleportNow(targetPlayer, destination).thenAccept(result -> {
                if (result == TeleportService.Result.SUCCESS) {
                    sender.replyKey("teleport-world-success", Map.of(
                            "player", targetPlayer.getUsername(),
                            "world", destination.getWorld()));
                    if (!targetPlayer.getUuid().equals(sender.uuid())) {
                        core.getMessageService().sendKey(targetPlayer, "teleport-world-target",
                                Map.of("world", destination.getWorld()));
                    }
                } else {
                    sender.replyKey("teleport-world-failed", Map.of(
                            "player", targetPlayer.getUsername(),
                            "world", destination.getWorld(),
                            "reason", result.name().toLowerCase(Locale.ROOT).replace('_', ' ')));
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
                MysticArgTypes.NETWORK_PLAYER_NAME);

        TpaTargetVariant() {
            super(TeleportationModule.this.core, "Request to teleport to a player.");
            requirePermission(Permissions.TELEPORT_TPA);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            requestTo(sender, sender.get(target), true);
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
                MysticArgTypes.NETWORK_PLAYER_NAME);

        TpaHereTargetVariant() {
            super(TeleportationModule.this.core, "Request that a player teleports to you.");
            requirePermission(Permissions.TELEPORT_TPA);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            requestTo(sender, sender.get(target), false);
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
                pendingRequesterArg);

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
                pendingRequesterArg);

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
            boolean removed = removeOutgoingRequests(sender.uuid());
            if (crossServerEnabled()) {
                Set<UUID> targets = outgoingRemote.remove(sender.uuid());
                if (targets != null) {
                    for (UUID target : targets) {
                        core.redis().cacheHashRemove(PENDING_KEY_PREFIX + target, sender.uuid().toString());
                    }
                }
                JsonObject cancel = baseMessage("cancel", "");
                cancel.addProperty("requester", sender.uuid().toString());
                core.redis().publish(REDIS_CHANNEL, Json.toString(cancel));
                // Pub/sub cancellation is asynchronous, so receipt cannot be
                // proven here; report the network cancellation as submitted.
                removed = true;
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
                MysticArgTypes.NETWORK_PLAYER_NAME);

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
                // On another network server: pull them here, then finish the move on arrival.
                NetworkPlayer remote = remoteNetworkPlayer(sender.get(target)).orElse(null);
                if (remote == null) {
                    sender.replyKey("player-not-found");
                } else if (forceTeleportAcrossNetwork(sender, remote.uuid(), remote.serverId(),
                        sender.uuid(), core.networkPlayers().localServerId(), "tphere")) {
                    sender.replyKey("teleport-here-transferring", Map.of(
                            "player", remote.username(), "server", remote.serverId()));
                }
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
                                    "reason", result.name().toLowerCase(Locale.ROOT).replace('_', ' ')));
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
