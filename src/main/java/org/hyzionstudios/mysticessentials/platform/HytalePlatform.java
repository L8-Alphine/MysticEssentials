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

    /** Registers a command through the plugin's command registry. */
    public void registerCommand(AbstractCommand command) {
        plugin.getCommandRegistry().registerCommand(command);
    }

    /** Registers a listener for a Hytale server event. */
    public <E extends IBaseEvent<Void>> void onEvent(Class<? super E> eventType, Consumer<E> listener) {
        plugin.getEventRegistry().register(eventType, listener);
    }

    /**
     * Registers a global async listener for a Hytale async event (e.g. chat). The
     * handler receives a future of the event and returns a (possibly transformed)
     * future, letting listeners mutate the event before it is applied.
     */
    public <K, E extends com.hypixel.hytale.event.IAsyncEvent<K>> void onAsyncEvent(
            Class<? super E> eventType,
            java.util.function.Function<java.util.concurrent.CompletableFuture<E>,
                    java.util.concurrent.CompletableFuture<E>> handler) {
        plugin.getEventRegistry().registerAsyncGlobal(eventType, handler);
    }
}
