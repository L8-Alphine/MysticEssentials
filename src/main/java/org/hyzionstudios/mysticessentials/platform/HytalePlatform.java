package org.hyzionstudios.mysticessentials.platform;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.MysticessentialsPlugin;
import org.hyzionstudios.mysticessentials.api.model.MysticLocation;
import org.hyzionstudios.mysticessentials.api.service.TeleportService;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.joml.Vector3d;

import java.time.Instant;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.ISystem;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.event.IAsyncEvent;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.event.IBaseEvent;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.io.ServerListener;
import com.hypixel.hytale.protocol.packets.connection.PongType;
import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.command.system.CommandRegistration;
import com.hypixel.hytale.server.core.console.ConsoleSender;
import com.hypixel.hytale.server.core.entity.damage.DamageDataComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.io.ServerManager;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.spawn.GlobalSpawnProvider;

/**
 * Single facade over the verified Hytale server API. Every call into Hytale for
 * player lookup, world access, command registration, and event registration
 * goes through here, so the discovered-API surface is centralized and easy to
 * audit or adjust when the server version changes.
 */
public final class HytalePlatform {

    private final MysticCore core;
    private final MysticessentialsPlugin plugin;
    /** Players currently holding an arrival-protection grant this plugin added. */
    private final Set<UUID> arrivalProtected = ConcurrentHashMap.newKeySet();

    public HytalePlatform(MysticCore core, MysticessentialsPlugin plugin) {
        this.core = core;
        this.plugin = plugin;
    }

    // ----- Players -----------------------------------------------------------

    /** @return the online player with this UUID, if any. */
    public Optional<PlayerRef> findPlayer(UUID uuid) {
        if (uuid == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(Universe.get().getPlayer(uuid));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /** @return the online player with this username (case-insensitive), if any. */
    public Optional<PlayerRef> findPlayerByName(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(Universe.get()
                    .getPlayerByUsername(username, NameMatching.EXACT_IGNORE_CASE));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /** Captures a location and tags it with this Redis network server id. */
    public MysticLocation capture(PlayerRef player) {
        MysticLocation location = Conversions.capture(player);
        if (core.networkPlayers() != null) {
            location.setServerId(core.networkPlayers().localServerId());
        }
        return location;
    }

    /** @return all online players, or an empty list if the universe is not ready. */
    public Collection<PlayerRef> onlinePlayers() {
        try {
            return Universe.get().getPlayers();
        } catch (Throwable t) {
            return List.of();
        }
    }

    /**
     * Resolves the {@link PlayerRef} behind an entity reference — what keyed
     * player events such as {@code PlayerReadyEvent} hand out. The ref is carried
     * as an ordinary component, so it is read as one.
     *
     * @return empty when the entity is gone or is not a player
     */
    public Optional<PlayerRef> playerRefOf(Ref<EntityStore> ref) {
        try {
            if (ref == null || !ref.isValid()) {
                return Optional.empty();
            }
            return Optional.ofNullable(ref.getStore().getComponent(ref,
                    Universe.get().getPlayerRefComponentType()));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /** @return whether the player's entity is currently placed in a world (safe to teleport). */
    public boolean isInWorld(PlayerRef player) {
        try {
            Ref<EntityStore> ref = player == null ? null : player.getReference();
            return ref != null && ref.isValid();
        } catch (Throwable t) {
            return false;
        }
    }

    // ----- Network endpoint ----------------------------------------------------

    /**
     * The game port this server is listening on, taken from the engine's live
     * listeners. Empty until the engine has bound (it binds after plugins start,
     * so callers should retry rather than cache an empty answer).
     */
    public OptionalInt boundPort() {
        try {
            for (ServerListener listener : ServerManager.get().getListeners()) {
                if (listener.localAddress() instanceof InetSocketAddress bound && bound.getPort() > 0) {
                    return OptionalInt.of(bound.getPort());
                }
            }
        } catch (Throwable ignored) {
            // Not bound yet, or the server manager is unavailable.
        }
        return OptionalInt.empty();
    }

    /**
     * Best-effort address other machines can reach this server on: the interface
     * it is bound to, or — when bound to every interface — the first IPv4
     * non-loopback, non-link-local address of an interface that is up (IPv6 only
     * as a last resort). This is right for servers on one machine or one LAN and
     * for a VPS with a public interface; behind NAT, Docker or a proxy the
     * operator must configure the public address instead.
     */
    public Optional<String> reachableHost() {
        try {
            for (ServerListener listener : ServerManager.get().getListeners()) {
                if (listener.localAddress() instanceof InetSocketAddress bound
                        && bound.getAddress() != null
                        && !bound.getAddress().isAnyLocalAddress()
                        && !bound.getAddress().isLoopbackAddress()) {
                    return Optional.of(bound.getAddress().getHostAddress());
                }
            }
            InetAddress fallback = null;
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address.isLoopbackAddress() || address.isLinkLocalAddress()
                            || address.isMulticastAddress() || address.isAnyLocalAddress()) {
                        continue;
                    }
                    if (address instanceof Inet4Address) {
                        return Optional.of(address.getHostAddress());
                    }
                    if (fallback == null) {
                        fallback = address;
                    }
                }
            }
            return Optional.ofNullable(fallback).map(InetAddress::getHostAddress);
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    // ----- Worlds ------------------------------------------------------------

    public Optional<World> world(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            World byName = Universe.get().getWorld(name);
            if (byName != null) {
                return Optional.of(byName);
            }
            // Stored locations may carry a UUID string when the world had no
            // resolvable name at capture time — try the UUID lookup too.
            try {
                UUID uuid = UUID.fromString(name);
                return Optional.ofNullable(Universe.get().getWorld(uuid));
            } catch (IllegalArgumentException notAUuid) {
                return Optional.empty();
            }
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /** @return the name of the player's current world, if resolvable. */
    public Optional<String> worldNameOf(PlayerRef player) {
        if (player == null) {
            return Optional.empty();
        }
        try {
            World world = Universe.get().getWorld(player.getWorldUuid());
            return world == null ? Optional.empty() : Optional.ofNullable(world.getName());
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /**
     * The spawn point a world would place {@code player} at, taken from the
     * world's configured spawn provider (the same source the engine uses on
     * world join).
     *
     * @return empty if the world is unknown or has no spawn provider.
     */
    public Optional<MysticLocation> worldSpawn(String worldName, UUID player) {
        World world = world(worldName).orElse(null);
        if (world == null) {
            return Optional.empty();
        }
        try {
            var config = world.getWorldConfig();
            var provider = config == null ? null : config.getSpawnProvider();
            Transform spawn = provider == null ? null : provider.getSpawnPoint(world, player);
            if (spawn == null) {
                return Optional.empty();
            }
            Vector3d position = spawn.getPosition();
            Rotation3f rotation = spawn.getRotation();
            return Optional.of(new MysticLocation(world.getName(),
                    position.x, position.y, position.z,
                    rotation == null ? 0.0f : rotation.yaw(),
                    rotation == null ? 0.0f : rotation.pitch()));
        } catch (Throwable t) {
            core.log(Level.WARNING, "Failed to resolve spawn point for world '" + worldName + "': " + t);
            return Optional.empty();
        }
    }

    /**
     * @return {@code true} if the player's current world is temporary — flagged
     *         delete-on-restart or delete-on-remove — and therefore unsafe to
     *         anchor persistent locations (spawns, homes, warps) in.
     */
    public boolean isInTemporaryWorld(PlayerRef player) {
        try {
            World world = Universe.get().getWorld(player.getWorldUuid());
            if (world == null) {
                return false;
            }
            var config = world.getWorldConfig();
            return config != null && (config.isDeleteOnUniverseStart() || config.isDeleteOnRemove());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Runs {@code task} on the named world's thread using the verified
     * {@link World#execute(Runnable)} API.
     *
     * @return {@code true} if the world was found and the task was dispatched.
     */
    public boolean runOnWorld(String worldName, Runnable task) {
        Optional<World> world = world(worldName);
        if (world.isEmpty()) {
            core.log(Level.WARNING, "Cannot run task: unknown world '" + worldName + "'");
            return false;
        }
        world.get().execute(task);
        return true;
    }

    /** Applies a static Hytale world spawn provider for the world named in {@code location}. */
    public boolean syncWorldSpawnProvider(MysticLocation location) {
        if (location == null || location.getWorld() == null || location.getWorld().isBlank()) {
            return false;
        }
        World world = world(location.getWorld()).orElse(null);
        if (world == null) {
            return false;
        }
        world.execute(() -> {
            try {
                var config = world.getWorldConfig();
                config.setSpawnProvider(new GlobalSpawnProvider(Conversions.toTransform(location)));
                config.markChanged();
            } catch (Throwable t) {
                core.log(Level.WARNING, "Failed to sync world spawn provider for '"
                        + location.getWorld() + "': " + t);
            }
        });
        return true;
    }

    // ----- Entity/ECS access (thread-safe) -----------------------------------

    /** A unit of work executed on a player's world thread with the ECS store and entity ref resolved. */
    @FunctionalInterface
    public interface EntityTask {
        void run(Store<EntityStore> store, Ref<EntityStore> entity, World currentWorld);
    }

    /**
     * Runs {@code task} on the player's <b>current</b> world thread with the ECS
     * store and entity reference resolved, mirroring how Hytale's own
     * {@code AbstractPlayerCommand} dispatches player work. This is the only safe
     * way to read or mutate a player's components: the server is multi-threaded
     * with one thread per world, so entity access must happen on that world's
     * thread.
     *
     * <p>A connected player has no entity yet while joining ({@code PlayerConnectEvent}
     * fires before the engine adds the player to a world) and briefly while moving
     * between worlds. Work for such a player is held and dispatched as soon as the
     * entity exists (for up to {@value #ENTITY_WAIT_MILLIS} ms) instead of being
     * dropped.</p>
     *
     * @return {@code true} if the task was dispatched, or held for a connected
     *         player whose entity is not in a world yet; {@code false} when the
     *         player is no longer connected.
     */
    public boolean runOnEntityThread(PlayerRef player, EntityTask task) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            if (!isConnected(player)) {
                return false;
            }
            awaitEntity(player, task, System.currentTimeMillis() + ENTITY_WAIT_MILLIS);
            return true;
        }
        dispatch(player, ref, task);
        return true;
    }

    /** How long work for a connected player without an entity is held. */
    private static final long ENTITY_WAIT_MILLIS = 30_000L;
    /** How often a held task re-checks for the player's entity. */
    private static final long ENTITY_POLL_MILLIS = 250L;

    private void dispatch(PlayerRef player, Ref<EntityStore> ref, EntityTask task) {
        Store<EntityStore> store = ref.getStore();
        World currentWorld = ((EntityStore) store.getExternalData()).getWorld();
        currentWorld.execute(() -> {
            if (!ref.isValid()) {
                // The player left this world between dispatch and execution:
                // follow them to their current entity, or drop the work if they left.
                runOnEntityThread(player, task);
                return;
            }
            try {
                task.run(store, ref, currentWorld);
            } catch (Throwable t) {
                core.log(Level.SEVERE, "Entity task for " + player.getUsername() + " threw: " + t);
            }
        });
    }

    private void awaitEntity(PlayerRef player, EntityTask task, long deadline) {
        try {
            core.scheduler().runLater(() -> {
                Ref<EntityStore> ref = player.getReference();
                if (ref != null && ref.isValid()) {
                    dispatch(player, ref, task);
                } else if (isConnected(player) && System.currentTimeMillis() < deadline) {
                    awaitEntity(player, task, deadline);
                } else {
                    core.log(Level.FINE, "Dropped entity task for " + player.getUsername()
                            + ": the player never entered a world.");
                }
            }, ENTITY_POLL_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            // The scheduler is gone: the plugin is shutting down.
            core.log(Level.FINE, "Dropped entity task for " + player.getUsername() + ": " + e);
        }
    }

    /** @return whether this exact connection is still registered with the universe. */
    private static boolean isConnected(PlayerRef player) {
        try {
            return Universe.get().getPlayer(player.getUuid()) == player;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Teleports a player to {@code destination} using the verified ECS teleport
     * component. Runs on the player's current world thread and attaches the
     * {@link Teleport} component directly, matching Hytale's built-in teleport
     * commands.
     *
     * @return a future completing with {@link TeleportService.Result#SUCCESS} once
     *         the move is applied, or a failure result if the destination world is
     *         unknown / the player is not in a valid world.
     */
    public CompletableFuture<TeleportService.Result> teleportEntity(PlayerRef player, MysticLocation destination) {
        CompletableFuture<TeleportService.Result> outcome = new CompletableFuture<>();
        World destWorld = world(destination.getWorld()).orElse(null);
        if (destWorld == null) {
            outcome.complete(TeleportService.Result.INVALID_DESTINATION);
            return outcome;
        }
        Rotation3f rotation = new Rotation3f();
        rotation.setPitch(destination.getPitch());
        rotation.setYaw(destination.getYaw());
        rotation.setRoll(0.0f);
        Transform transform = new Transform(
                new Vector3d(destination.getX(), destination.getY(), destination.getZ()), rotation);

        Runnable timeout = () -> {
            if (outcome.complete(TeleportService.Result.FAILED)) {
                core.log(Level.WARNING, "Teleport timed out for " + player.getUsername()
                        + " to " + destination.getWorld() + " at "
                        + (int) destination.getX() + ", " + (int) destination.getY() + ", "
                        + (int) destination.getZ());
            }
        };
        // Work for a player with no entity yet (joining, changing worlds) is held
        // before it runs, so the move is timed from when it is queued; a move
        // given up on while held is never performed afterwards.
        AtomicBoolean started = new AtomicBoolean();
        boolean dispatched = runOnEntityThread(player, (store, entity, currentWorld) -> {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            try {
                // Teleport is a one-shot component. Hytale's own teleport command
                // adds it; setting/replacing an existing component is ignored by
                // TeleportSystems.PlayerMoveSystem and would leave our future hung.
                if (store.getComponent(entity, Teleport.getComponentType()) != null) {
                    core.log(Level.WARNING, "Teleport refused for " + player.getUsername()
                            + ": another Teleport component is already pending.");
                    outcome.complete(TeleportService.Result.FAILED);
                    return;
                }
                Teleport teleport = Teleport.createForPlayer(destWorld, transform);
                teleport.setHeadRotation(new Rotation3f(rotation));
                CompletableFuture<Void> applied = new CompletableFuture<>();
                teleport.setOnComplete(applied);
                store.addComponent(entity, Teleport.getComponentType(), teleport);
                applied.whenComplete((v, error) -> outcome.complete(
                        error == null ? TeleportService.Result.SUCCESS : TeleportService.Result.FAILED));
                core.scheduler().runLater(timeout, 10, TimeUnit.SECONDS);
            } catch (Throwable error) {
                core.log(Level.SEVERE, "Could not queue teleport for " + player.getUsername() + ": " + error);
                outcome.complete(TeleportService.Result.FAILED);
            }
        });
        if (!dispatched) {
            outcome.complete(TeleportService.Result.FAILED);
        } else {
            core.scheduler().runLater(() -> {
                if (started.compareAndSet(false, true)) {
                    timeout.run();
                }
            }, ENTITY_WAIT_MILLIS + 10_000L, TimeUnit.MILLISECONDS);
        }
        return outcome;
    }

    /**
     * Resolves a WorldEdit-style /top destination above the highest block in the
     * player's current X/Z column.
     */
    public CompletableFuture<Optional<MysticLocation>> topLocation(PlayerRef player) {
        CompletableFuture<Optional<MysticLocation>> outcome = new CompletableFuture<>();
        Transform transform = player.getTransform();
        if (transform == null) {
            outcome.complete(Optional.empty());
            return outcome;
        }
        int blockX = (int) Math.floor(transform.getPosition().x);
        int blockZ = (int) Math.floor(transform.getPosition().z);
        float yaw = player.getHeadRotation() == null ? transform.getRotation().yaw() : player.getHeadRotation().yaw();
        float pitch = player.getHeadRotation() == null ? transform.getRotation().pitch() : player.getHeadRotation().pitch();

        boolean dispatched = runOnEntityThread(player, (store, entity, currentWorld) -> {
            long chunkIndex = ChunkUtil.indexChunkFromBlock(blockX, blockZ);
            currentWorld.getChunkStore().getChunkReferenceAsync(chunkIndex)
                    .thenAcceptAsync(chunkRef -> completeTopLocation(outcome, currentWorld,
                                    worldChunk(currentWorld, chunkRef), blockX, blockZ, yaw, pitch),
                            currentWorld)
                    .exceptionally(error -> {
                        core.log(Level.WARNING, "Failed to resolve /top destination for "
                                + player.getUsername() + ": " + error);
                        outcome.complete(Optional.empty());
                        return null;
                    });
        });
        if (!dispatched) {
            outcome.complete(Optional.empty());
        }
        return outcome;
    }

    private void completeTopLocation(CompletableFuture<Optional<MysticLocation>> outcome, World world,
            WorldChunk chunk, int blockX, int blockZ, float yaw, float pitch) {
        if (chunk == null) {
            outcome.complete(Optional.empty());
            return;
        }
        int localX = ChunkUtil.localCoordinate(blockX);
        int localZ = ChunkUtil.localCoordinate(blockZ);
        int topY = chunk.getHeight(localX, localZ);
        if (topY < ChunkUtil.MIN_Y) {
            outcome.complete(Optional.empty());
            return;
        }
        outcome.complete(Optional.of(new MysticLocation(world.getName(),
                blockX + 0.5d, topY + 1.0d, blockZ + 0.5d, yaw, pitch)));
    }

    // ----- Random-teleport ground sampling -----------------------------------

    /**
     * Loads (or generates) the chunk containing {@code (blockX, blockZ)} in
     * {@code worldName} and evaluates the column for a safe standing position:
     * the surface must be solid ground, not void, within {@code [minY, maxY]},
     * with {@code requiredHeadroom} air blocks above it, and (unless
     * {@code allowLiquids}) no fluid at the feet or surface.
     *
     * <p>This is the low-level block-safety probe behind the Random Teleport
     * destination search. All chunk/block API access stays here in the platform
     * layer; higher-level rules (distance from spawn, region/claim exclusion,
     * biome filters) live in the RTP service on top of this result.</p>
     *
     * <p>Runs the block reads on the target world's thread via the verified
     * {@code ChunkStore.getChunkReferenceAsync} + world-executor continuation, mirroring
     * {@link #topLocation}. The result completes with a centred
     * {@link MysticLocation} (feet at {@code surface + 1}, looking straight
     * ahead) or empty when the column is unsafe or the chunk cannot load.</p>
     */
    public CompletableFuture<Optional<MysticLocation>> sampleGround(String worldName, int blockX, int blockZ,
            int requiredHeadroom, boolean allowLiquids, int minY, int maxY) {
        CompletableFuture<Optional<MysticLocation>> outcome = new CompletableFuture<>();
        World world = world(worldName).orElse(null);
        if (world == null) {
            outcome.complete(Optional.empty());
            return outcome;
        }
        try {
            long chunkIndex = ChunkUtil.indexChunkFromBlock(blockX, blockZ);
            world.getChunkStore().getChunkReferenceAsync(chunkIndex)
                    .thenAcceptAsync(chunkRef -> completeGroundSample(outcome, world,
                            worldChunk(world, chunkRef), blockX, blockZ,
                            requiredHeadroom, allowLiquids, minY, maxY), world)
                    .exceptionally(error -> {
                        outcome.complete(Optional.empty());
                        return null;
                    });
        } catch (Throwable t) {
            outcome.complete(Optional.empty());
        }
        return outcome;
    }

    /** Block id used by the Update 6 asset map for empty space (air). */
    private static final int AIR_BLOCK_ID = BlockType.EMPTY_ID;

    private void completeGroundSample(CompletableFuture<Optional<MysticLocation>> outcome, World world,
            WorldChunk chunk, int blockX, int blockZ, int requiredHeadroom, boolean allowLiquids,
            int minY, int maxY) {
        try {
            if (chunk == null) {
                outcome.complete(Optional.empty());
                return;
            }
            int localX = ChunkUtil.localCoordinate(blockX);
            int localZ = ChunkUtil.localCoordinate(blockZ);
            int surfaceY = chunk.getHeight(localX, localZ);
            if (surfaceY < ChunkUtil.MIN_Y) {
                // No terrain in this column (void).
                outcome.complete(Optional.empty());
                return;
            }
            int feetY = surfaceY + 1;
            if (feetY < minY || feetY > maxY) {
                outcome.complete(Optional.empty());
                return;
            }
            // The surface block must be solid ground to stand on.
            if (blockId(chunk, localX, surfaceY, localZ) == AIR_BLOCK_ID) {
                outcome.complete(Optional.empty());
                return;
            }
            // Require clear air for the player's body.
            int headroom = Math.max(1, requiredHeadroom);
            for (int dy = 0; dy < headroom; dy++) {
                if (blockId(chunk, localX, feetY + dy, localZ) != AIR_BLOCK_ID) {
                    outcome.complete(Optional.empty());
                    return;
                }
            }
            // Reject standing in or on fluids (deep water, lava) unless allowed.
            if (!allowLiquids
                    && (RtpFluidSafety.isPresent(fluidId(world, blockX, surfaceY, blockZ))
                            || RtpFluidSafety.isPresent(fluidId(world, blockX, feetY, blockZ)))) {
                outcome.complete(Optional.empty());
                return;
            }
            outcome.complete(Optional.of(new MysticLocation(world.getName(),
                    blockX + 0.5d, feetY, blockZ + 0.5d, 0.0f, 0.0f)));
        } catch (Throwable t) {
            outcome.complete(Optional.empty());
        }
    }

    // ----- Column scan for a safe standing spot ------------------------------

    /**
     * Scans the block column at {@code (blockX, blockZ)} and completes with the
     * spot a player can stand on that sits closest to {@code preferredFeetY}: a
     * non-air floor block that is not in {@code blockedBlockIds}, with
     * {@code requiredHeadroom} air blocks above it for the player's body, and no
     * fluid from {@code blockedFluidIds} in the floor or body blocks.
     *
     * <p>Unlike {@link #sampleGround} this trusts no heightmap — it reads the
     * blocks themselves, so built platforms, overhangs, and terrain the
     * heightmap disagrees with all resolve to a real surface. Picking the
     * candidate nearest {@code preferredFeetY} (rather than the topmost one)
     * keeps enclosed areas working: in a roofed room the room's floor wins over
     * the roof above it, while on open uneven ground the surface is still the
     * only candidate near the reference height. Only candidates within
     * {@code maxVerticalDistance} blocks of {@code preferredFeetY} are
     * considered, so a cave far below never counts as the same place.</p>
     *
     * <p>Runs the block reads on the target world's thread, like the other chunk
     * probes here. Completes empty when the chunk cannot load or the column has
     * no spot passing the rules.</p>
     */
    public CompletableFuture<Optional<MysticLocation>> findStandingSpot(String worldName, int blockX, int blockZ,
            int requiredHeadroom, Set<Integer> blockedBlockIds, Set<Integer> blockedFluidIds,
            int preferredFeetY, int maxVerticalDistance) {
        CompletableFuture<Optional<MysticLocation>> outcome = new CompletableFuture<>();
        World world = world(worldName).orElse(null);
        if (world == null) {
            outcome.complete(Optional.empty());
            return outcome;
        }
        try {
            long chunkIndex = ChunkUtil.indexChunkFromBlock(blockX, blockZ);
            world.getChunkStore().getChunkReferenceAsync(chunkIndex)
                    .thenAcceptAsync(chunkRef -> completeStandingSpot(outcome, world,
                            worldChunk(world, chunkRef), blockX, blockZ,
                            requiredHeadroom, blockedBlockIds, blockedFluidIds, preferredFeetY,
                            maxVerticalDistance), world)
                    .exceptionally(error -> {
                        outcome.complete(Optional.empty());
                        return null;
                    });
        } catch (Throwable t) {
            outcome.complete(Optional.empty());
        }
        return outcome;
    }

    private void completeStandingSpot(CompletableFuture<Optional<MysticLocation>> outcome, World world,
            WorldChunk chunk, int blockX, int blockZ, int requiredHeadroom, Set<Integer> blockedBlockIds,
            Set<Integer> blockedFluidIds, int preferredFeetY, int maxVerticalDistance) {
        try {
            if (chunk == null) {
                outcome.complete(Optional.empty());
                return;
            }
            int localX = ChunkUtil.localCoordinate(blockX);
            int localZ = ChunkUtil.localCoordinate(blockZ);
            int headroom = Math.max(1, requiredHeadroom);
            int range = Math.max(0, maxVerticalDistance);
            int top = Math.min(ChunkUtil.HEIGHT - headroom, preferredFeetY + range);
            int bottom = Math.max(ChunkUtil.MIN_Y + 1, preferredFeetY - range);
            int bestFeetY = Integer.MIN_VALUE;
            int bestDistance = Integer.MAX_VALUE;
            for (int feetY = top; feetY >= bottom; feetY--) {
                // Scanning downwards: once the gap to the preferred height is
                // wider than the best candidate so far, nothing lower can beat it.
                if (feetY < preferredFeetY && preferredFeetY - feetY > bestDistance) {
                    break;
                }
                int floorY = feetY - 1;
                int floor = blockId(chunk, localX, floorY, localZ);
                if (floor == AIR_BLOCK_ID || contains(blockedBlockIds, floor)
                        || contains(blockedFluidIds, fluidId(world, blockX, floorY, blockZ))) {
                    continue;
                }
                boolean clear = true;
                for (int dy = 0; dy < headroom && clear; dy++) {
                    clear = blockId(chunk, localX, feetY + dy, localZ) == AIR_BLOCK_ID
                            && !contains(blockedFluidIds, fluidId(world, blockX, feetY + dy, blockZ));
                }
                if (!clear) {
                    continue;
                }
                int distance = Math.abs(feetY - preferredFeetY);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestFeetY = feetY;
                }
            }
            outcome.complete(bestFeetY == Integer.MIN_VALUE
                    ? Optional.empty()
                    : Optional.of(new MysticLocation(world.getName(),
                            blockX + 0.5d, bestFeetY, blockZ + 0.5d, 0.0f, 0.0f)));
        } catch (Throwable t) {
            outcome.complete(Optional.empty());
        }
    }

    private static boolean contains(Set<Integer> ids, int id) {
        return ids != null && !ids.isEmpty() && ids.contains(id);
    }

    /** Reads the numeric block id used by RTP safety configuration. */
    private static int blockId(WorldChunk chunk, int x, int y, int z) {
        var reference = chunk.getReference();
        if (reference == null) {
            return BlockType.UNKNOWN_ID;
        }
        BlockChunk blocks = reference.getStore().getComponent(reference, BlockChunk.getComponentType());
        return blocks == null ? BlockType.UNKNOWN_ID : blocks.getBlock(x, y, z);
    }

    /** Resolves a loaded chunk column through Update 6's component-backed chunk store. */
    private static WorldChunk worldChunk(World world, Ref<ChunkStore> chunkRef) {
        if (chunkRef == null || !chunkRef.isValid()) {
            return null;
        }
        return world.getChunkStore().getStore().getComponent(chunkRef, WorldChunk.getComponentType());
    }

    /** Reads a fluid from its chunk-section component; missing sections use the engine sentinel. */
    private static int fluidId(World world, int blockX, int blockY, int blockZ) {
        ChunkStore chunks = world.getChunkStore();
        Ref<ChunkStore> sectionRef = chunks.getChunkSectionReferenceAtBlock(blockX, blockY, blockZ);
        if (sectionRef == null || !sectionRef.isValid()) {
            return Integer.MIN_VALUE;
        }
        FluidSection fluids = chunks.getStore().getComponent(sectionRef, FluidSection.getComponentType());
        return fluids == null
                ? Integer.MIN_VALUE
                : fluids.getFluidId(ChunkUtil.localCoordinate(blockX),
                        ChunkUtil.localCoordinate(blockY), ChunkUtil.localCoordinate(blockZ));
    }

    /**
     * Resolves a block-type asset id (the file name, e.g. {@code Fluid_Lava}) to
     * its numeric block id.
     *
     * @return the id, or {@code -1} when no such block type is registered.
     */
    public int blockTypeId(String assetId) {
        if (assetId == null || assetId.isBlank()) {
            return -1;
        }
        try {
            return BlockType.getAssetMap().getIndexOrDefault(assetId, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Resolves a fluid asset id (e.g. {@code Lava}, {@code Water_Source}) to its
     * numeric fluid id, as returned by {@code WorldChunk.getFluidId}.
     *
     * @return the id, or {@code -1} when no such fluid is registered.
     */
    public int fluidTypeId(String assetId) {
        if (assetId == null || assetId.isBlank()) {
            return -1;
        }
        try {
            return Fluid.getAssetMap().getIndexOrDefault(assetId, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Opens a custom UI page for the player, on their world thread. Resolves the
     * {@link Player} entity from the store and drives
     * {@code PageManager.openCustomPage}.
     *
     * @return {@code true} if the open was dispatched.
     */
    public boolean openPage(PlayerRef player, CustomUIPage page) {
        boolean dispatched = runOnEntityThread(player, (store, entity, world) -> {
            Player playerEntity = store.getComponent(entity, Player.getComponentType());
            if (playerEntity == null) {
                core.log(Level.WARNING, "openPage: no Player component for " + player.getUsername());
                return;
            }
            try {
                playerEntity.getPageManager().openCustomPage(entity, store, page);
            } catch (Throwable t) {
                core.log(Level.SEVERE, "openPage: openCustomPage failed for " + player.getUsername() + ": " + t);
            }
        });
        if (!dispatched) {
            core.log(Level.WARNING, "openPage: could not dispatch page for " + player.getUsername()
                    + " (offline or invalid entity ref).");
        }
        return dispatched;
    }

    /** Shows or replaces a custom HUD for the player, on their world thread. */
    public boolean showHud(PlayerRef player, CustomUIHud hud) {
        boolean dispatched = runOnEntityThread(player, (store, entity, world) -> {
            Player playerEntity = store.getComponent(entity, Player.getComponentType());
            if (playerEntity == null) {
                core.log(Level.WARNING, "showHud: no Player component for " + player.getUsername());
                return;
            }
            try {
                playerEntity.getHudManager().addCustomHud(player, hud);
            } catch (Throwable t) {
                core.log(Level.SEVERE, "showHud: addCustomHud failed for " + player.getUsername() + ": " + t);
            }
        });
        if (!dispatched) {
            core.log(Level.WARNING, "showHud: could not dispatch HUD for " + player.getUsername()
                    + " (offline or invalid entity ref).");
        }
        return dispatched;
    }

    /** Removes a custom HUD for the player, on their world thread. */
    public boolean removeHud(PlayerRef player, String key) {
        boolean dispatched = runOnEntityThread(player, (store, entity, world) -> {
            Player playerEntity = store.getComponent(entity, Player.getComponentType());
            if (playerEntity == null) {
                return;
            }
            try {
                playerEntity.getHudManager().removeCustomHud(player, key);
            } catch (Throwable t) {
                core.log(Level.SEVERE, "removeHud: removeCustomHud failed for " + player.getUsername() + ": " + t);
            }
        });
        if (!dispatched) {
            core.log(Level.WARNING, "removeHud: could not dispatch HUD removal for " + player.getUsername()
                    + " (offline or invalid entity ref).");
        }
        return dispatched;
    }

    /**
     * Grants the player temporary damage immunity for {@code seconds}, then
     * removes it. Backed by the verified {@code Invulnerable} marker component
     * (the same one behind the builtin {@code /entity invulnerable}). Used for
     * Random Teleport arrival protection — a single invulnerability window covers
     * both the "invulnerability" and "prevent fall damage" arrival settings.
     *
     * <p>The grant runs on the entity thread; removal is scheduled off-thread and
     * re-dispatched onto the entity thread. Creative players and
     * {@code /entity invulnerable} already carry the marker: it is only removed
     * again when this grant added it, and never from a player now in Creative.</p>
     */
    public void applyArrivalProtection(PlayerRef player, int seconds) {
        if (seconds <= 0) {
            return;
        }
        UUID uuid = player.getUuid();
        runOnEntityThread(player, (store, entity, world) -> {
            if (store.getComponent(entity, Invulnerable.getComponentType()) != null) {
                return;
            }
            store.ensureComponent(entity, Invulnerable.getComponentType());
            arrivalProtected.add(uuid);
            core.scheduler().runLater(() -> findPlayer(uuid).ifPresent(live ->
                    runOnEntityThread(live, (liveStore, liveEntity, liveWorld) -> {
                        if (arrivalProtected.remove(uuid)) {
                            removeArrivalProtection(liveStore, liveEntity);
                        }
                    })),
                    seconds, TimeUnit.SECONDS);
        });
    }

    /**
     * Ends a pending arrival-protection grant of a disconnecting player while the
     * entity still exists. {@code Invulnerable} is saved with the player, so a
     * grant still active at logout would otherwise be loaded back on the next
     * join with nothing left to remove it: permanent god mode.
     */
    public void endArrivalProtection(PlayerRef player) {
        if (!arrivalProtected.remove(player.getUuid())) {
            return;
        }
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        if (world.isInThread()) {
            removeArrivalProtection(store, ref);
        } else {
            // Queued before the engine's own removal task (Universe#removePlayer
            // dispatches the disconnect event first), so it runs while the entity
            // is still in the store.
            world.execute(() -> {
                if (ref.isValid()) {
                    removeArrivalProtection(store, ref);
                }
            });
        }
    }

    /** Ends every pending grant (plugin shutdown: the timers die with the scheduler). */
    public void endAllArrivalProtection() {
        for (UUID uuid : List.copyOf(arrivalProtected)) {
            findPlayer(uuid).ifPresentOrElse(this::endArrivalProtection, () -> arrivalProtected.remove(uuid));
        }
    }

    private static void removeArrivalProtection(Store<EntityStore> store, Ref<EntityStore> entity) {
        Player playerEntity = store.getComponent(entity, Player.getComponentType());
        if (playerEntity == null || playerEntity.getGameMode() != GameMode.Creative) {
            store.tryRemoveComponent(entity, Invulnerable.getComponentType());
        }
    }

    /**
     * Reads the player's last-damage timestamp from the ECS on their world thread.
     * Completes with {@code null} if the player is offline or has no damage data.
     * Used by teleport warmups to detect damage without depending on a data-driven
     * health stat id.
     */
    public CompletableFuture<Instant> lastDamageTime(PlayerRef player) {
        CompletableFuture<Instant> future = new CompletableFuture<>();
        boolean dispatched = runOnEntityThread(player, (store, entity, world) -> {
            DamageDataComponent damage = store.getComponent(entity, DamageDataComponent.getComponentType());
            future.complete(damage == null ? null : damage.getLastDamageTime());
        });
        if (!dispatched) {
            future.complete(null);
        }
        return future;
    }

    // ----- Raw protocol ------------------------------------------------------

    /**
     * Writes a to-client packet straight to a player's connection. Packets are
     * queued on the netty channel in call order, so a caller may rely on two
     * consecutive writes arriving in sequence.
     *
     * @return {@code true} if the packet was handed to the player's packet handler.
     */
    public boolean sendPacket(PlayerRef player, ToClientPacket packet) {
        if (player == null || packet == null) {
            return false;
        }
        try {
            var handler = player.getPacketHandler();
            if (handler == null) {
                return false;
            }
            handler.write(packet);
            return true;
        } catch (Throwable t) {
            core.log(Level.WARNING, "Failed to send " + packet.getClass().getSimpleName()
                    + " to " + player.getUsername() + ": " + t);
            return false;
        }
    }

    /**
     * The round-trip time the engine reports for a player, in milliseconds —
     * the same average the built-in Server Players list shows.
     */
    public int pingMillis(PlayerRef player) {
        try {
            var info = player.getPacketHandler()
                    .getPingInfo(PongType.Direct);
            double average = info.getPingMetricSet().getAverage(0);
            return (int) PacketHandler.PingInfo.TIME_UNIT
                    .toMillis((long) Math.ceil(average));
        } catch (Throwable t) {
            return 0;
        }
    }

    // ----- Registration ------------------------------------------------------

    /**
     * Registers a command through the plugin's command registry.
     *
     * @return the registration handle — its public {@code unregister()} fully
     *         removes the command again (verified 0.6.2: the handle's teardown
     *         runnable removes the name from {@code CommandManager}'s command
     *         map and every alias from its alias map). {@code null} if the
     *         engine rejected the registration. Callers that never unregister
     *         may ignore the return value.
     */
    public CommandRegistration registerCommand(
            AbstractCommand command) {
        return plugin.getCommandRegistry().registerCommand(command);
    }

    /**
     * Executes a command as the console (full permissions). {@code command}
     * must not include a leading slash.
     *
     * @return {@code true} if the command was handed to the command manager.
     */
    public boolean dispatchConsoleCommand(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        try {
            CommandManager.get()
                    .handleCommand(ConsoleSender.INSTANCE, command);
            return true;
        } catch (Throwable t) {
            core.log(Level.WARNING, "Console command failed: '" + command + "': " + t);
            return false;
        }
    }

    /**
     * Executes a command <b>as the player</b> (their permissions apply).
     * {@code command} must not include a leading slash. Uses the verified
     * {@code CommandManager.handleCommand(CommandSender, String)} —
     * {@code PlayerRef} implements {@code CommandSender}.
     *
     * @return {@code true} if the command was handed to the command manager.
     */
    public boolean dispatchPlayerCommand(PlayerRef player, String command) {
        if (player == null || command == null || command.isBlank()) {
            return false;
        }
        try {
            CommandManager.get()
                    .handleCommand(player, command);
            return true;
        } catch (Throwable t) {
            core.log(Level.WARNING, "Player command failed for " + player.getUsername()
                    + ": '" + command + "': " + t);
            return false;
        }
    }

    /**
     * Registers an ECS system against the entity store — the only way to observe
     * the engine's {@code EcsEvent}s (e.g. {@code CraftRecipeEvent.Pre}), which
     * are invoked on entities and never reach the plugin event registry.
     *
     * <p>Systems are keyed by class: registering the same system class twice
     * throws, and the registry drops every system this plugin registered when
     * the plugin shuts down. There is no per-system unregister handle exposed
     * to plugins, so a module that can be hot-disabled should register once and
     * gate its own behaviour rather than expect to remove the system.</p>
     *
     * @return {@code true} when the system was accepted
     */
    public boolean registerEntitySystem(ISystem<EntityStore> system) {
        try {
            plugin.getEntityStoreRegistry().registerSystem(system);
            return true;
        } catch (Throwable t) {
            core.log(Level.WARNING, "Could not register ECS system "
                    + system.getClass().getSimpleName() + ": " + t);
            return false;
        }
    }

    /**
     * Registers a listener for a Hytale server event.
     *
     * @return the registration handle — its public {@code unregister()} removes
     *         the listener again. Callers that never unregister may ignore the
     *         return value.
     */
    public <E extends IBaseEvent<Void>> EventRegistration<Void, E> onEvent(
            Class<? super E> eventType, Consumer<E> listener) {
        return plugin.getEventRegistry().register(eventType, listener);
    }

    /**
     * Registers a listener for a Hytale server event at an explicit priority.
     * {@link EventPriority#LAST} is how Mystic runs
     * after the engine's own core modules have handled the same event.
     *
     * @return the registration handle — its public {@code unregister()} removes
     *         the listener again.
     */
    public <E extends IBaseEvent<Void>> EventRegistration<Void, E> onEvent(
            EventPriority priority, Class<? super E> eventType, Consumer<E> listener) {
        return plugin.getEventRegistry().register(priority, eventType, listener);
    }

    /**
     * Registers a listener for a <em>keyed</em> Hytale server event across every
     * key.
     *
     * <p>{@link #onEvent} only accepts the {@code IBaseEvent<Void>} events that
     * carry no key. An event with a key — {@code PlayerReadyEvent} is one — is
     * dispatched per key, so a plain registration never sees it; a global
     * registration does, and is how the engine's own modules subscribe.</p>
     *
     * @return the registration handle — its public {@code unregister()} removes
     *         the listener again.
     */
    public <K, E extends IBaseEvent<K>> EventRegistration<K, E> onGlobalEvent(
            EventPriority priority, Class<? super E> eventType, Consumer<E> listener) {
        return plugin.getEventRegistry().registerGlobal(priority, eventType, listener);
    }

    /**
     * Registers a global async listener for a Hytale async event (e.g. chat). The
     * handler receives a future of the event and returns a (possibly transformed)
     * future, letting listeners mutate the event before it is applied.
     *
     * @return the registration handle — its public {@code unregister()} removes
     *         the listener again. Callers that never unregister may ignore the
     *         return value.
     */
    public <K, E extends IAsyncEvent<K>> EventRegistration<K, E> onAsyncEvent(
            Class<? super E> eventType,
            Function<CompletableFuture<E>, CompletableFuture<E>> handler) {
        return plugin.getEventRegistry().registerAsyncGlobal(eventType, handler);
    }
}
