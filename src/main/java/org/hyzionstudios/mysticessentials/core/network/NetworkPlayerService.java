package org.hyzionstudios.mysticessentials.core.network;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.config.MainConfig;
import org.hyzionstudios.mysticessentials.core.util.Json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Redis-backed view of players connected anywhere in a MysticEssentials
 * network. Every server publishes one short-lived roster document rather than
 * one key per player, which makes an unclean server stop disappear naturally
 * and avoids one server deleting a player key after a cross-server handoff.
 *
 * <p>The roster also carries the address other servers use to refer players
 * here. {@code storage.redis.advertisedHost/advertisedPort} win when set;
 * otherwise the bound game port and a reachable interface address are detected
 * from the engine, which is right for one-machine and LAN setups and a VPS
 * with a public interface — behind NAT, Docker or a proxy the operator must
 * configure the public address.</p>
 */
public final class NetworkPlayerService {

    private static final String CHANNEL = "core:presence";
    private static final String SERVER_INDEX = "presence:servers";
    private static final String SERVER_KEY_PREFIX = "presence:server:";
    private static final int HEARTBEAT_SECONDS = 10;

    private final MysticCore core;
    private final Map<String, ServerSnapshot> remoteServers = new ConcurrentHashMap<>();
    private final java.util.function.Consumer<String> redisHandler = this::handleSnapshot;

    private ScheduledFuture<?> heartbeat;
    private volatile boolean running;
    /** Auto-detected endpoint, resolved lazily (the engine binds after plugins start). */
    private volatile String detectedHost;
    private volatile int detectedPort;
    private volatile boolean endpointAnnounced;

    public NetworkPlayerService(MysticCore core) {
        this.core = core;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        core.redis().subscribe(CHANNEL, redisHandler);
        publishNow(null, null);
        heartbeat = core.scheduler().runRepeating(() -> publishNow(null, null),
                HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    public void reload() {
        remoteServers.clear();
        detectedHost = null;
        detectedPort = 0;
        endpointAnnounced = false;
        publishNow(null, null);
    }

    public void stop() {
        running = false;
        if (heartbeat != null) {
            heartbeat.cancel(false);
            heartbeat = null;
        }
        core.redis().unsubscribe(CHANNEL, redisHandler);
        remoteServers.clear();
    }

    /** Publishes a fresh roster immediately after a player connects. */
    public void onJoin(PlayerRef joined) {
        publishNow(joined, null);
    }

    /** Publishes a fresh roster without the disconnecting player. */
    public void onQuit(PlayerRef leaving) {
        publishNow(null, leaving == null ? null : leaving.getUuid());
    }

    /** Refreshes network-visible local state such as AFK/vanish flags. */
    public void refreshLocalState() {
        publishNow(null, null);
    }

    public boolean isNetworked() {
        return core.redis() != null && core.redis().isEnabled();
    }

    public String localServerId() {
        String id = core.redis() == null ? null : core.redis().serverId();
        return id == null || id.isBlank() ? "local" : id;
    }

    /** Network roster, including local players, de-duplicated by UUID. */
    public List<NetworkPlayer> onlinePlayers() {
        Map<UUID, NetworkPlayer> players = new LinkedHashMap<>();
        for (PlayerRef local : core.platform().onlinePlayers()) {
            players.put(local.getUuid(), localPlayer(local));
        }
        long staleBefore = Instant.now().minusSeconds(ttlSeconds()).toEpochMilli();
        remoteServers.entrySet().removeIf(entry -> entry.getValue().seenAtMillis < staleBefore);
        for (ServerSnapshot snapshot : remoteServers.values()) {
            for (NetworkPlayer player : snapshot.players) {
                players.putIfAbsent(player.uuid(), player);
            }
        }
        List<NetworkPlayer> result = new ArrayList<>(players.values());
        result.sort(Comparator.comparing(NetworkPlayer::username, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public Optional<NetworkPlayer> find(UUID uuid) {
        if (uuid == null) {
            return Optional.empty();
        }
        Optional<PlayerRef> local = core.platform().findPlayer(uuid);
        if (local.isPresent()) {
            return Optional.of(localPlayer(local.get()));
        }
        return onlinePlayers().stream().filter(player -> player.uuid().equals(uuid)).findFirst();
    }

    public Optional<NetworkPlayer> findByName(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        String wanted = username.trim().toLowerCase(Locale.ROOT);
        return onlinePlayers().stream()
                .filter(player -> player.username().toLowerCase(Locale.ROOT).equals(wanted))
                .findFirst();
    }

    /** Refers a local player to a server advertised through the Redis roster. */
    public boolean referToServer(PlayerRef player, String destinationServerId) {
        return referToServer(player, destinationServerId, null);
    }

    /**
     * Refers a local player to a server advertised through the Redis roster,
     * handing {@code referralData} (at most 4 KiB) to the destination, which
     * receives it on {@code PlayerSetupConnectEvent.getReferralData()}. This is
     * the engine's transfer primitive: a {@code ClientReferral} packet makes the
     * client reconnect to that host/port itself.
     */
    public boolean referToServer(PlayerRef player, String destinationServerId, byte[] referralData) {
        if (player == null || destinationServerId == null
                || destinationServerId.equals(localServerId())) {
            return false;
        }
        ServerSnapshot destination = remoteServers.get(destinationServerId);
        if (hasProxy()) {
            // The proxy routes on the payload's destinationServerId, so the client
            // is always sent to the proxy; the backend's own address stays private.
            if (destination == null) {
                core.log(Level.WARNING, "Cannot transfer " + player.getUsername() + " to '"
                        + destinationServerId + "': that server is not on the network roster.");
                return false;
            }
            byte[] data = referralData != null ? referralData : routePayload(destinationServerId);
            String proxyHost = clean(core.config().storage.redis.proxyHost);
            int proxyPort = core.config().storage.redis.proxyPort;
            core.log(Level.INFO, "Referring " + player.getUsername() + " to '" + destinationServerId
                    + "' via proxy " + proxyHost + ":" + proxyPort + " with " + data.length + " bytes of referral data");
            try {
                player.referToServer(proxyHost, proxyPort, data);
                return true;
            } catch (Throwable t) {
                core.log(Level.WARNING, "Referring " + player.getUsername() + " via the proxy failed: " + t);
                return false;
            }
        }
        if (destination == null || destination.host == null || destination.host.isBlank()
                || destination.port <= 0) {
            core.log(Level.WARNING, "Cannot transfer " + player.getUsername() + " to '"
                    + destinationServerId + "': " + (destination == null
                            ? "that server is not on the network roster (not seen for "
                                    + ttlSeconds() + "s, or a different networkId)."
                            : "that server advertises no host/port (storage.redis.advertisedHost/advertisedPort)."));
            return false;
        }
        core.log(Level.INFO, "Referring " + player.getUsername() + " to '" + destinationServerId + "' at "
                + destination.host + ":" + destination.port
                + (referralData == null ? "" : " with " + referralData.length + " bytes of referral data"));
        try {
            if (referralData == null) {
                player.referToServer(destination.host, destination.port);
            } else {
                player.referToServer(destination.host, destination.port, referralData);
            }
            return true;
        } catch (Throwable t) {
            core.log(Level.WARNING, "Referring " + player.getUsername() + " to '" + destinationServerId
                    + "' failed: " + t);
            return false;
        }
    }

    /** Whether a network proxy (MysticGate) fronts the servers — see {@code storage.redis.proxyHost}. */
    public boolean hasProxy() {
        MainConfig.Redis config = core.config().storage.redis;
        return !clean(config.proxyHost).isEmpty() && config.proxyPort > 0;
    }

    /**
     * Minimal routing payload for a transfer that carries nothing else: tells
     * the proxy which backend to connect the client to. Ignored by every
     * Mystic Essentials arrival handler (their {@code kind} differs).
     */
    private static byte[] routePayload(String destinationServerId) {
        JsonObject route = new JsonObject();
        route.addProperty("kind", "mysticessentials:route");
        route.addProperty("destinationServerId", destinationServerId);
        return Json.toString(route).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Every other server currently on the roster, for diagnostics. */
    public List<RemoteServer> remoteServers() {
        List<RemoteServer> result = new ArrayList<>();
        for (Map.Entry<String, ServerSnapshot> entry : remoteServers.entrySet()) {
            ServerSnapshot snapshot = entry.getValue();
            result.add(new RemoteServer(entry.getKey(), snapshot.host, snapshot.port,
                    snapshot.seenAtMillis, snapshot.players.size()));
        }
        result.sort(Comparator.comparing(RemoteServer::serverId, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** Whether the advertised host came from config ({@code false} = auto-detected or unknown). */
    public boolean isAdvertisedHostConfigured() {
        return !clean(core.config().storage.redis.advertisedHost).isEmpty();
    }

    /** Whether the advertised port came from config ({@code false} = auto-detected or unknown). */
    public boolean isAdvertisedPortConfigured() {
        return core.config().storage.redis.advertisedPort > 0;
    }

    public boolean hasEndpoint(String serverId) {
        if (serverId == null) {
            return false;
        }
        if (hasProxy()) {
            // The proxy reaches backends on its own; a server only has to be on the roster.
            return serverId.equals(localServerId()) || remoteServers.containsKey(serverId);
        }
        if (serverId.equals(localServerId())) {
            return !advertisedHost().isEmpty() && advertisedPort() > 0;
        }
        ServerSnapshot snapshot = remoteServers.get(serverId);
        return snapshot != null && snapshot.host != null && !snapshot.host.isBlank()
                && snapshot.port > 0;
    }

    private NetworkPlayer localPlayer(PlayerRef player) {
        return new NetworkPlayer(player.getUuid(), player.getUsername(), localServerId(),
                advertisedHost(), advertisedPort(), core.vanish().isVanished(player.getUuid()),
                core.getAfkService() != null && core.getAfkService().isAfk(player.getUuid()));
    }

    /** The host other servers refer players to: configured, else auto-detected (may be empty early on). */
    public String advertisedHost() {
        String configured = clean(core.config().storage.redis.advertisedHost);
        if (!configured.isEmpty()) {
            return configured;
        }
        detectEndpoint();
        return detectedHost == null ? "" : detectedHost;
    }

    /** The port paired with {@link #advertisedHost()}: configured, else the bound game port (0 until bound). */
    public int advertisedPort() {
        int configured = core.config().storage.redis.advertisedPort;
        if (configured > 0) {
            return configured;
        }
        detectEndpoint();
        return detectedPort;
    }

    /**
     * Fills whichever half of the endpoint is not configured from the engine.
     * Cheap (walks the engine's listener list), so it simply runs again until
     * both halves are known — the bind completes shortly after plugin start.
     * Logs the result once so operators can see what other servers will use.
     */
    private void detectEndpoint() {
        MainConfig.Redis config = core.config().storage.redis;
        String configuredHost = clean(config.advertisedHost);
        boolean detectHost = configuredHost.isEmpty();
        boolean detectPort = config.advertisedPort <= 0;
        if (detectPort && detectedPort <= 0) {
            detectedPort = core.platform().boundPort().orElse(0);
        }
        if (detectHost && detectedHost == null) {
            detectedHost = core.platform().reachableHost().orElse(null);
        }
        String host = detectHost ? (detectedHost == null ? "" : detectedHost) : configuredHost;
        int port = detectPort ? detectedPort : config.advertisedPort;
        if (!endpointAnnounced && !host.isEmpty() && port > 0) {
            endpointAnnounced = true;
            core.log(Level.INFO, "Advertising '" + localServerId() + "' to the network at " + host + ":" + port
                    + " (host " + (detectHost ? "auto-detected" : "configured")
                    + ", port " + (detectPort ? "auto-detected" : "configured")
                    + "). Other servers refer players here with that address; set "
                    + "storage.redis.advertisedHost/advertisedPort if players cannot reach it (NAT, Docker, proxy).");
        }
    }

    private void publishNow(PlayerRef joined, UUID excluded) {
        if (!running || !isNetworked()) {
            return;
        }
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("serverId", localServerId());
        snapshot.addProperty("host", advertisedHost());
        snapshot.addProperty("port", Math.max(0, advertisedPort()));
        snapshot.addProperty("seenAt", System.currentTimeMillis());

        Map<UUID, PlayerRef> locals = new LinkedHashMap<>();
        for (PlayerRef player : core.platform().onlinePlayers()) {
            if (excluded == null || !excluded.equals(player.getUuid())) {
                locals.put(player.getUuid(), player);
            }
        }
        if (joined != null && (excluded == null || !excluded.equals(joined.getUuid()))) {
            locals.put(joined.getUuid(), joined);
        }
        JsonArray players = new JsonArray();
        for (PlayerRef player : locals.values()) {
            JsonObject item = new JsonObject();
            item.addProperty("uuid", player.getUuid().toString());
            item.addProperty("username", player.getUsername());
            item.addProperty("vanished", core.vanish().isVanished(player.getUuid()));
            item.addProperty("afk", core.getAfkService() != null
                    && core.getAfkService().isAfk(player.getUuid()));
            players.add(item);
        }
        snapshot.add("players", players);

        String raw = Json.toString(snapshot);
        int ttl = ttlSeconds();
        core.redis().cacheSet(SERVER_KEY_PREFIX + localServerId(), raw, ttl);
        core.redis().cacheSetAdd(SERVER_INDEX, ttl * 4, localServerId());
        core.redis().publish(CHANNEL, raw);
        refreshFromRedis();
    }

    private void refreshFromRedis() {
        Collection<String> ids = core.redis().cacheSetMembers(SERVER_INDEX);
        for (String id : ids) {
            if (id == null || id.equals(localServerId())) {
                continue;
            }
            String raw = core.redis().cacheGet(SERVER_KEY_PREFIX + id);
            if (raw == null) {
                remoteServers.remove(id);
                core.redis().cacheSetRemove(SERVER_INDEX, id);
            } else {
                handleSnapshot(raw);
            }
        }
    }

    private void handleSnapshot(String raw) {
        if (!running || raw == null || raw.isBlank()) {
            return;
        }
        try {
            JsonObject object = Json.asObject(Json.parse(raw));
            String serverId = string(object, "serverId");
            if (serverId.isBlank() || serverId.equals(localServerId())) {
                return;
            }
            String host = string(object, "host");
            int port = integer(object, "port", 0);
            long seenAt = longValue(object, "seenAt", System.currentTimeMillis());
            List<NetworkPlayer> players = new ArrayList<>();
            JsonArray array = object.has("players") && object.get("players").isJsonArray()
                    ? object.getAsJsonArray("players") : new JsonArray();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                UUID uuid = uuid(string(item, "uuid"));
                String username = string(item, "username");
                if (uuid != null && !username.isBlank()) {
                    players.add(new NetworkPlayer(uuid, username, serverId, host, port,
                            bool(item, "vanished"), bool(item, "afk")));
                }
            }
            remoteServers.put(serverId, new ServerSnapshot(host, port, seenAt, List.copyOf(players)));
        } catch (RuntimeException e) {
            core.log(Level.WARNING, "Ignored malformed Redis presence payload: " + e.getMessage());
        }
    }

    private int ttlSeconds() {
        return Math.max(HEARTBEAT_SECONDS * 3, core.config().storage.redis.presenceTtlSeconds);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String string(JsonObject object, String key) {
        try {
            return object.has(key) ? clean(object.get(key).getAsString()) : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static int integer(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static long longValue(JsonObject object, String key, long fallback) {
        try {
            return object.has(key) ? object.get(key).getAsLong() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject object, String key) {
        try {
            return object.has(key) && object.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static UUID uuid(String value) {
        try {
            return value.isBlank() ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public record NetworkPlayer(UUID uuid, String username, String serverId,
            String host, int port, boolean vanished, boolean afk) {
        public boolean local(String localServerId) {
            return serverId != null && serverId.equals(localServerId);
        }
    }

    private record ServerSnapshot(String host, int port, long seenAtMillis,
            List<NetworkPlayer> players) {
    }

    /** Diagnostic view of another server on the roster. */
    public record RemoteServer(String serverId, String host, int port, long seenAtMillis, int playerCount) {
        public boolean hasEndpoint() {
            return host != null && !host.isBlank() && port > 0;
        }
    }
}
