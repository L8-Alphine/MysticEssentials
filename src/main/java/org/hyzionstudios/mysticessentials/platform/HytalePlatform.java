package org.hyzionstudios.mysticessentials.platform;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.MysticessentialsPlugin;
import org.hyzionstudios.mysticessentials.api.model.MysticLocation;
import org.hyzionstudios.mysticessentials.api.service.TeleportService;
import org.hyzionstudios.mysticessentials.core.MysticCore;

import java.time.Instant;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.event.IBaseEvent;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.entity.damage.DamageDataComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
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
                    .getPlayerByUsername(username, com.hypixel.hytale.server.core.NameMatching.EXACT_IGNORE_CASE));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /** @return all online players, or an empty list if the universe is not ready. */
    public Collection<PlayerRef> onlinePlayers() {
        try {
            return Universe.get().getPlayers();
        } catch (Throwable t) {
            return List.of();
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
     * @return {@code true} if the player is online with a valid entity and the
     *         task was dispatched; {@code false} otherwise.
     */
    public boolean runOnEntityThread(PlayerRef player, EntityTask task) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            return false;
        }
        Store<EntityStore> store = ref.getStore();
        World currentWorld = ((EntityStore) store.getExternalData()).getWorld();
        currentWorld.execute(() -> {
            try {
                task.run(store, ref, currentWorld);
            } catch (Throwable t) {
                core.log(Level.SEVERE, "Entity task for " + player.getUsername() + " threw: " + t);
            }
        });
        return true;
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
                new org.joml.Vector3d(destination.getX(), destination.getY(), destination.getZ()), rotation);

        boolean dispatched = runOnEntityThread(player, (store, entity, currentWorld) -> {
            Teleport teleport = Teleport.createForPlayer(destWorld, transform);
            teleport.setHeadRotation(new Rotation3f(rotation));
            CompletableFuture<Void> applied = new CompletableFuture<>();
            teleport.setOnComplete(applied);
            store.putComponent(entity, Teleport.getComponentType(), teleport);
            applied.whenComplete((v, error) ->
                    outcome.complete(error == null ? TeleportService.Result.SUCCESS : TeleportService.Result.FAILED));
        });
        if (!dispatched) {
            outcome.complete(TeleportService.Result.FAILED);
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
            currentWorld.getChunkAsync(chunkIndex)
                    .thenAcceptAsync(chunk -> completeTopLocation(outcome, currentWorld, chunk, blockX, blockZ, yaw, pitch),
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
     * {@code World.getChunkAsync} + world-executor continuation, mirroring
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
            world.getChunkAsync(chunkIndex)
                    .thenAcceptAsync(chunk -> completeGroundSample(outcome, world, chunk, blockX, blockZ,
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

    /** Block id returned by {@link WorldChunk#getBlock} for empty space (air). */
    private static final int AIR_BLOCK_ID = 0;

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
            if (chunk.getBlock(localX, surfaceY, localZ) == AIR_BLOCK_ID) {
                outcome.complete(Optional.empty());
                return;
            }
            // Require clear air for the player's body.
            int headroom = Math.max(1, requiredHeadroom);
            for (int dy = 0; dy < headroom; dy++) {
                if (chunk.getBlock(localX, feetY + dy, localZ) != AIR_BLOCK_ID) {
                    outcome.complete(Optional.empty());
                    return;
                }
            }
            // Reject standing in or on fluids (deep water, lava) unless allowed.
            if (!allowLiquids
                    && (chunk.getFluidId(localX, surfaceY, localZ) != 0
                            || chunk.getFluidId(localX, feetY, localZ) != 0)) {
                outcome.complete(Optional.empty());
                return;
            }
            outcome.complete(Optional.of(new MysticLocation(world.getName(),
                    blockX + 0.5d, feetY, blockZ + 0.5d, 0.0f, 0.0f)));
        } catch (Throwable t) {
            outcome.complete(Optional.empty());
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
     * re-dispatched onto the entity thread. If the player logs out before removal
     * the component is dropped with their entity, so no cleanup leak occurs.</p>
     */
    public void applyArrivalProtection(PlayerRef player, int seconds) {
        if (seconds <= 0) {
            return;
        }
        boolean dispatched = runOnEntityThread(player, (store, entity, world) ->
                store.ensureComponent(entity,
                        com.hypixel.hytale.server.core.modules.entity.component.Invulnerable.getComponentType()));
        if (!dispatched) {
            return;
        }
        UUID uuid = player.getUuid();
        core.scheduler().runLater(() -> findPlayer(uuid).ifPresent(live ->
                runOnEntityThread(live, (store, entity, world) -> store.tryRemoveComponent(entity,
                        com.hypixel.hytale.server.core.modules.entity.component.Invulnerable.getComponentType()))),
                seconds, java.util.concurrent.TimeUnit.SECONDS);
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

    // ----- Registration ------------------------------------------------------

    /**
     * Registers a command through the plugin's command registry.
     *
     * @return the registration handle — its public {@code unregister()} fully
     *         removes the command again (verified 0.5.6: the handle's teardown
     *         runnable removes the name from {@code CommandManager}'s command
     *         map and every alias from its alias map). {@code null} if the
     *         engine rejected the registration. Callers that never unregister
     *         may ignore the return value.
     */
    public com.hypixel.hytale.server.core.command.system.CommandRegistration registerCommand(
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
            com.hypixel.hytale.server.core.command.system.CommandManager.get()
                    .handleCommand(com.hypixel.hytale.server.core.console.ConsoleSender.INSTANCE, command);
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
            com.hypixel.hytale.server.core.command.system.CommandManager.get()
                    .handleCommand(player, command);
            return true;
        } catch (Throwable t) {
            core.log(Level.WARNING, "Player command failed for " + player.getUsername()
                    + ": '" + command + "': " + t);
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
    public <E extends IBaseEvent<Void>> com.hypixel.hytale.event.EventRegistration<Void, E> onEvent(
            Class<? super E> eventType, Consumer<E> listener) {
        return plugin.getEventRegistry().register(eventType, listener);
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
    public <K, E extends com.hypixel.hytale.event.IAsyncEvent<K>> com.hypixel.hytale.event.EventRegistration<K, E> onAsyncEvent(
            Class<? super E> eventType,
            java.util.function.Function<java.util.concurrent.CompletableFuture<E>,
                    java.util.concurrent.CompletableFuture<E>> handler) {
        return plugin.getEventRegistry().registerAsyncGlobal(eventType, handler);
    }
}
