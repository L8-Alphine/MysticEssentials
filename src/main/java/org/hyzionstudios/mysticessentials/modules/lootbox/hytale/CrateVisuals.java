package org.hyzionstudios.mysticessentials.modules.lootbox.hytale;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.event.EventBus;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.message.MysticText;
import org.hyzionstudios.mysticessentials.modules.lootbox.LootBoxModule;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.event.OpeningCommittedEvent;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.event.OpeningRequestedEvent;
import org.hyzionstudios.mysticessentials.modules.lootbox.config.LootBoxConfig;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.CrateInstance;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.storage.LootBoxStorage;
import org.joml.Vector3d;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Crate visuals (P2-I): idle and opening particles, an opening sound, and an
 * optional floating name.
 *
 * <p><b>Particles and sounds</b> go only to players near the crate who have not
 * turned crate effects off ({@code /lootbox crate effects off}), through
 * {@code ParticleUtil.spawnParticleEffect(..., playerRefs, ...)} and
 * {@code SoundUtil.playSoundEvent3d(..., shouldHear, ...)}. Idle particles are
 * re-sent every {@code crates.idleEffectIntervalMs} with that interval as their
 * {@code maxDuration}, so a looping system never piles up. The opening effect
 * plays when an opening from the crate commits: the crate id comes from
 * {@code OpeningRequestedEvent} and is matched to the player's next
 * {@code OpeningCommittedEvent}, so the opening saga is untouched.</p>
 *
 * <p><b>Floating names</b> (behind {@code crates.nameplates}, off by default)
 * use a holder entity modelled on the engine's warp marker
 * ({@code TeleportPlugin#createWarp}): a transform, a network id (so the
 * tracker sends it), {@code Intangible}, a {@code Nameplate} and a display
 * name, and no model. It is not saved with the chunk; the idle tick recreates
 * it from the crate index while players are near. <b>Unverified in game</b>:
 * the nameplate spike (S4) never ran, so whether the client draws a nameplate
 * on an entity with no model is unknown. Nameplates are global: every player
 * near the crate sees the same name, and there is no per-player switch.</p>
 *
 * <p>Glow has no verified engine API and is left out.</p>
 *
 * <p>Every world change happens on that world's thread through
 * {@link World#execute}.</p>
 */
public final class CrateVisuals {

    /** Vanilla model asset whose geometry is NPC/MISC/Empty.blockymodel. */
    private static final String HOLDER_MODEL_ID = "Invisible_Projectile";

    /** Particle systems (not spawners) checked in the 0.7.2 registry, offered in the crate editor. */
    public static final List<String> IDLE_PARTICLES = List.of("Block_Treasure_Sparks", "Dust_Sparkles",
            "Dust_Sparkles_Light", "Block_Gem_Sparks");
    public static final List<String> OPEN_PARTICLES = List.of("Block_Break_Treasure", "Block_Break_Crystal",
            "Block_Gem_Sparks");
    /** Sound events checked in the 0.7.2 registry. */
    public static final List<String> OPEN_SOUNDS = List.of("SFX_Chest_Legendary_Open", "SFX_Chest_Wooden_Open",
            "SFX_Coffer_Goblin_Reward", "SFX_Workbench_Upgrade_Complete_Default");

    /** How long a confirmed crate opening waits for its commit before the effect is dropped. */
    private static final long OPEN_MATCH_MS = 60_000L;
    private static final long MIN_INTERVAL_MS = 500L;

    private record PendingOpen(String crateId, long at) {
    }

    private record Holder(String worldKey, Ref<EntityStore> ref, String text) {
    }

    private final MysticCore core;
    private final LootBoxModule module;
    private final LootBoxStorage storage;
    private final Set<UUID> effectsOff = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PendingOpen> pending = new ConcurrentHashMap<>();
    private final Map<String, Holder> nameplates = new ConcurrentHashMap<>();
    /** When each idle stacked effect last played, by {@code crateId#index}. */
    private final Map<String, Long> lastIdle = new ConcurrentHashMap<>();
    private final List<EventBus.Subscription> subscriptions = new ArrayList<>();
    private ScheduledFuture<?> idleTask;
    private volatile boolean running;

    public CrateVisuals(MysticCore core, LootBoxModule module, LootBoxStorage storage) {
        this.core = core;
        this.module = module;
        this.storage = storage;
    }

    // ----- Lifecycle ----------------------------------------------------------

    public void start() {
        running = true;
        storage.loadCrateEffectsOff().thenAccept(ids -> {
            for (String id : ids) {
                try {
                    effectsOff.add(UUID.fromString(id));
                } catch (IllegalArgumentException ignored) {
                    // A hand-edited entry; skip it.
                }
            }
        }).exceptionally(t -> {
            core.log(Level.WARNING, "[lootbox] could not read the crate-effects opt-outs: " + t);
            return null;
        });
        subscriptions.add(core.getEventBus().subscribe(OpeningRequestedEvent.class, this::onRequested));
        subscriptions.add(core.getEventBus().subscribe(OpeningCommittedEvent.class, this::onCommitted));
        schedule();
    }

    /** Picks up a changed {@code crates.idleEffectIntervalMs} after a reload. */
    public void reschedule() {
        if (running) {
            schedule();
        }
    }

    private synchronized void schedule() {
        if (idleTask != null) {
            idleTask.cancel(false);
        }
        long interval = Math.max(MIN_INTERVAL_MS, module.config().crates.idleEffectIntervalMs);
        idleTask = core.scheduler().runRepeating(this::tick, interval, interval, TimeUnit.MILLISECONDS);
    }

    /** Stops the effects and removes every floating name (on its world's thread). */
    public void stop() {
        running = false;
        synchronized (this) {
            if (idleTask != null) {
                idleTask.cancel(false);
                idleTask = null;
            }
        }
        subscriptions.forEach(EventBus.Subscription::close);
        subscriptions.clear();
        pending.clear();
        lastIdle.clear();
        Map<String, List<Holder>> byWorld = new HashMap<>();
        nameplates.values().forEach(holder -> byWorld.computeIfAbsent(holder.worldKey(), k -> new ArrayList<>())
                .add(holder));
        nameplates.clear();
        byWorld.forEach((worldKey, holders) -> core.platform().world(worldKey).ifPresent(world -> world.execute(() -> {
            Store<EntityStore> store = world.getEntityStore().getStore();
            holders.forEach(holder -> remove(store, holder));
        })));
    }

    /** The visuals a new crate starts with: {@code crates.default*} from the module config. */
    public static CrateInstance.Visuals defaults(LootBoxConfig.Crates config) {
        CrateInstance.Visuals visuals = new CrateInstance.Visuals();
        visuals.idleParticle = config.defaultIdleParticle == null ? "" : config.defaultIdleParticle.trim();
        visuals.openParticle = config.defaultOpenParticle == null ? "" : config.defaultOpenParticle.trim();
        visuals.openSound = config.defaultOpenSound == null ? "" : config.defaultOpenSound.trim();
        return visuals;
    }

    // ----- Per-player switch --------------------------------------------------

    public boolean effectsOn(UUID player) {
        return !effectsOff.contains(player);
    }

    /** Turns crate particles and sounds on or off for {@code player}, and stores it. */
    public CompletableFuture<Void> setEffects(UUID player, boolean on) {
        if (on) {
            effectsOff.remove(player);
        } else {
            effectsOff.add(player);
        }
        return storage.setCrateEffectsOff(player, !on);
    }

    // ----- Asset checks -------------------------------------------------------

    /** Whether {@code id} is a loaded particle system. Reads the asset map only. */
    public static boolean particleExists(String id) {
        return id != null && !id.isBlank() && ParticleSystem.getAssetMap().getAsset(id) != null;
    }

    /** Whether {@code id} is a loaded sound event. Reads the asset map only. */
    public static boolean soundExists(String id) {
        return soundIndex(id) >= 0;
    }

    private static int soundIndex(String id) {
        if (id == null || id.isBlank()) {
            return -1;
        }
        int index = SoundEvent.getAssetMap().getIndexOrDefault(id, -1);
        return index == SoundEvent.EMPTY_ID ? -1 : index;
    }

    /**
     * Loaded particle system ids containing {@code query} (any case), sorted, at
     * most {@code limit}. Read from the live asset map, so the editor offers what
     * this server actually has (3070 systems on 0.7.2), the way vanilla's
     * {@code ParticleSpawnPage#buildParticleList} does.
     */
    public static List<String> particleIds(String query, int limit) {
        return matching(ParticleSystem.getAssetMap().getAssetMap().keySet(), query, limit);
    }

    /** Loaded sound event ids containing {@code query}, sorted, at most {@code limit}. */
    public static List<String> soundIds(String query, int limit) {
        return matching(SoundEvent.getAssetMap().getAssetMap().keySet(), query, limit);
    }

    private static List<String> matching(Set<String> ids, String query, int limit) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return ids.stream()
                .filter(id -> needle.isEmpty() || id.toLowerCase(Locale.ROOT).contains(needle))
                .sorted()
                .limit(Math.max(0, limit))
                .toList();
    }

    // ----- Idle tick ----------------------------------------------------------
    //
    // The whole of "which players are near which crate, and what should fire"
    // is computed on the scheduler thread. Only the particle spawns and the
    // nameplate upkeep reach a world thread, and only for crates a player is
    // actually standing near.
    //
    // That is possible because PlayerRef#getTransform and #getWorldUuid are
    // plain field reads, not entity-store lookups (checked against the 0.7.5
    // jar): a player's position can be read off-thread. It may be one tick
    // stale, which for a 24-block radius test is not a difference anyone sees.
    //
    // The old shape ran a spatial query per crate per tick ON the world thread,
    // so a server with a few hundred crates paid hundreds of spatial queries a
    // second whether or not anyone stood near one. That is what made the crate
    // particles cost a tick.

    /** A player the planner can place without touching a world thread. */
    private record Viewer(Ref<EntityStore> ref, double x, double y, double z) {
    }

    /** One particle for a world thread to spawn. */
    private record Spawn(String particleId, double x, double y, double z, float scale, float duration,
            List<Ref<EntityStore>> viewers) {
    }

    /** A crate whose floating name needs creating or retitling, text already built. */
    private record Plate(CrateInstance crate, String text) {
    }

    /** Everything one world's thread has to do this tick. */
    private record Plan(List<Spawn> spawns, List<Plate> plates, Set<String> keep, boolean prune) {
        boolean isEmpty() {
            return spawns.isEmpty() && plates.isEmpty() && !prune;
        }
    }

    private void tick() {
        if (!running || LootBoxModule.active() != module) {
            return;
        }
        Collection<CrateInstance> crates = module.crates().all();
        if (crates.isEmpty() && nameplates.isEmpty()) {
            return;
        }
        LootBoxConfig.Crates config = module.config().crates;

        Map<String, List<CrateInstance>> byWorld = new HashMap<>();
        for (CrateInstance crate : crates) {
            if (!crate.disabled) {
                byWorld.computeIfAbsent(worldKey(crate), k -> new ArrayList<>()).add(crate);
            }
        }
        // A world still holding one of our nameplates needs a visit even when
        // its last crate is gone, so the holder entity gets cleaned up.
        for (Holder holder : nameplates.values()) {
            byWorld.computeIfAbsent(holder.worldKey(), k -> new ArrayList<>());
        }
        if (byWorld.isEmpty()) {
            return;
        }

        pruneIdleTimers();
        Map<String, List<Viewer>> viewersByWorld = snapshotViewers();
        long now = System.currentTimeMillis();

        byWorld.forEach((worldKey, worldCrates) -> {
            World world = core.platform().world(worldKey).orElse(null);
            if (world == null) {
                nameplates.values().removeIf(holder -> holder.worldKey().equals(worldKey));
                return;
            }
            Plan plan = plan(config, worldKey, worldCrates,
                    viewersByWorld.getOrDefault(worldKey, List.of()), now);
            if (plan.isEmpty()) {
                // Nothing here this tick: do not wake the world thread at all.
                return;
            }
            world.execute(() -> {
                try {
                    apply(world, worldKey, plan);
                } catch (Throwable t) {
                    core.log(Level.WARNING, "[lootbox] crate visuals in " + world.getName() + " failed: " + t);
                }
            });
        });
    }

    /**
     * Every player who wants effects, by world, with their position copied out.
     *
     * <p>Read entirely off the world threads. The position may be a tick stale;
     * the only thing it decides is a radius test.</p>
     */
    private Map<String, List<Viewer>> snapshotViewers() {
        Map<String, List<Viewer>> byWorld = new HashMap<>();
        for (PlayerRef player : core.platform().onlinePlayers()) {
            if (player == null || !player.isValid() || !effectsOn(player.getUuid())) {
                continue;
            }
            UUID worldUuid = player.getWorldUuid();
            Ref<EntityStore> ref = player.getReference();
            if (worldUuid == null || ref == null || !ref.isValid()) {
                continue;
            }
            // Copy the coordinates out now: the Transform instance is reused and
            // rewritten in place by the world thread on every move.
            Vector3d at = player.getTransform().getPosition();
            byWorld.computeIfAbsent(worldUuid.toString(), k -> new ArrayList<>())
                    .add(new Viewer(ref, at.x, at.y, at.z));
        }
        return byWorld;
    }

    /**
     * Forgets idle timers for stacked slots that no longer exist.
     *
     * <p>Walks the timers, not the crates: only a slot that has actually fired
     * owns a timer, so this costs what is playing rather than what the server
     * owns. The old shape built a set of every crate-by-slot pair each tick.</p>
     */
    private void pruneIdleTimers() {
        if (lastIdle.isEmpty()) {
            return;
        }
        lastIdle.keySet().removeIf(slot -> {
            int hash = slot.lastIndexOf('#');
            if (hash < 0) {
                return true;
            }
            CrateInstance crate = module.crates().get(slot.substring(0, hash)).orElse(null);
            if (crate == null) {
                return true;
            }
            try {
                return Integer.parseInt(slot.substring(hash + 1)) >= crate.visuals.extras.size();
            } catch (NumberFormatException e) {
                return true;
            }
        });
    }

    /** What this world's thread should do. Pure computation: no store access. */
    private Plan plan(LootBoxConfig.Crates config, String worldKey, List<CrateInstance> crates,
            List<Viewer> viewers, long now) {
        List<Spawn> spawns = new ArrayList<>();
        List<Plate> plates = new ArrayList<>();
        Set<String> keep = new HashSet<>();
        float duration = Math.max(MIN_INTERVAL_MS, config.idleEffectIntervalMs) / 1000f;
        double radius = Math.max(1.0, config.effectRadius);
        double radiusSq = radius * radius;
        Budget budget = new Budget(config.particleBudgetPerWorld, config.particleBudgetPerClient);

        for (CrateInstance crate : crates) {
            boolean floating = config.nameplates && crate.visuals.nameplate;
            if (floating) {
                keep.add(crate.crateId);
            }
            if (viewers.isEmpty()) {
                continue;
            }
            List<Ref<EntityStore>> near = near(viewers, crate, radiusSq);
            if (near.isEmpty()) {
                continue;
            }
            if (floating) {
                // Built here so the world thread never does the registry lookup
                // and the markup strip.
                plates.add(new Plate(crate, nameplateText(crate)));
            }
            if (particleExists(crate.visuals.idleParticle)) {
                List<Ref<EntityStore>> to = budget.take(near);
                if (!to.isEmpty()) {
                    spawns.add(new Spawn(crate.visuals.idleParticle, crate.x + 0.5, crate.y + 0.5, crate.z + 0.5,
                            1f, duration, to));
                }
            }
            List<CrateInstance.Effect> extras = stack(crate);
            for (int i = 0; i < extras.size(); i++) {
                CrateInstance.Effect effect = extras.get(i);
                if (!CrateInstance.Effect.PHASE_IDLE.equals(effect.phase)
                        || !CrateInstance.Effect.KIND_PARTICLE.equals(effect.kind) || !particleExists(effect.id)) {
                    continue;
                }
                String slot = crate.crateId + "#" + i;
                // Repeats can't be faster than the idle tick, so the particle lives
                // for the effective gap rather than going dark between ticks.
                long every = Math.max(effect.intervalMs, (long) (duration * 1000f));
                Long last = lastIdle.get(slot);
                if (last != null && now - last < every) {
                    continue;
                }
                List<Ref<EntityStore>> to = budget.take(near);
                if (to.isEmpty()) {
                    continue;
                }
                lastIdle.put(slot, now);
                spawns.add(new Spawn(effect.id, crate.x + 0.5, crate.y + effect.height, crate.z + 0.5,
                        (float) effect.scale, every / 1000f, to));
            }
        }

        boolean prune = false;
        for (Map.Entry<String, Holder> entry : nameplates.entrySet()) {
            if (entry.getValue().worldKey().equals(worldKey) && !keep.contains(entry.getKey())) {
                prune = true;
                break;
            }
        }
        return new Plan(spawns, plates, keep, prune);
    }

    /** The viewers within {@code radiusSq} of a crate's centre, by squared distance. */
    private static List<Ref<EntityStore>> near(List<Viewer> viewers, CrateInstance crate, double radiusSq) {
        List<Ref<EntityStore>> near = null;
        double cx = crate.x + 0.5;
        double cy = crate.y + 0.5;
        double cz = crate.z + 0.5;
        for (Viewer viewer : viewers) {
            double dx = viewer.x() - cx;
            double dy = viewer.y() - cy;
            double dz = viewer.z() - cz;
            if (dx * dx + dy * dy + dz * dz > radiusSq) {
                continue;
            }
            if (near == null) {
                near = new ArrayList<>(4);
            }
            near.add(viewer.ref());
        }
        return near == null ? List.of() : near;
    }

    /** The world-thread half: spawn what was planned, keep the floating names in step. */
    private void apply(World world, String worldKey, Plan plan) {
        if (!running) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        for (Plate plate : plan.plates()) {
            ensureNameplate(store, worldKey, plate.crate(), plate.text(),
                    module.config().crates.nameplateHeight);
        }
        for (Spawn spawn : plan.spawns()) {
            List<Ref<EntityStore>> live = live(spawn.viewers());
            if (live.isEmpty()) {
                continue;
            }
            ParticleUtil.spawnParticleEffect(spawn.particleId(), spawn.x(), spawn.y(), spawn.z(), 0f, 0f, 0f,
                    spawn.scale(), null, null, live, store, spawn.duration());
        }
        if (!plan.prune()) {
            return;
        }
        for (Map.Entry<String, Holder> entry : List.copyOf(nameplates.entrySet())) {
            if (entry.getValue().worldKey().equals(worldKey) && !plan.keep().contains(entry.getKey())) {
                nameplates.remove(entry.getKey());
                remove(store, entry.getValue());
            }
        }
    }

    /** Drops refs that went away between planning and the world thread picking it up. */
    private static List<Ref<EntityStore>> live(List<Ref<EntityStore>> refs) {
        for (Ref<EntityStore> ref : refs) {
            if (ref == null || !ref.isValid()) {
                List<Ref<EntityStore>> alive = new ArrayList<>(refs.size());
                for (Ref<EntityStore> candidate : refs) {
                    if (candidate != null && candidate.isValid()) {
                        alive.add(candidate);
                    }
                }
                return alive;
            }
        }
        return refs;
    }

    // ----- Opening ------------------------------------------------------------

    private void onRequested(OpeningRequestedEvent event) {
        if (event.crateId() != null && !event.isCancelled()) {
            pending.put(event.player(), new PendingOpen(event.crateId(), System.currentTimeMillis()));
        }
    }

    private void onCommitted(OpeningCommittedEvent event) {
        PendingOpen open = pending.remove(event.player());
        if (open == null || System.currentTimeMillis() - open.at() > OPEN_MATCH_MS || !running) {
            return;
        }
        CrateInstance crate = module.crates().get(open.crateId()).orElse(null);
        if (crate == null || crate.disabled) {
            return;
        }
        core.platform().world(worldKey(crate)).ifPresent(world -> world.execute(() -> playOpening(world, crate)));
    }

    /** The opening particles and sound for everyone near the crate with effects on. Runs on the world thread. */
    private void playOpening(World world, CrateInstance crate) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        Vector3d at = center(crate);
        if (particleExists(crate.visuals.openParticle)) {
            List<Ref<EntityStore>> viewers = withEffects(store, nearby(store, at, module.config().crates.effectRadius));
            if (!viewers.isEmpty()) {
                ParticleUtil.spawnParticleEffect(crate.visuals.openParticle, at, viewers, store);
            }
        }
        int sound = soundIndex(crate.visuals.openSound);
        if (sound >= 0) {
            SoundUtil.playSoundEvent3d(sound, SoundCategory.SFX, at.x, at.y, at.z, ref -> hasEffects(store, ref), store);
        }
        List<Ref<EntityStore>> viewers = null;
        for (CrateInstance.Effect effect : stack(crate)) {
            if (!CrateInstance.Effect.PHASE_OPEN.equals(effect.phase)) {
                continue;
            }
            double y = crate.y + effect.height;
            if (CrateInstance.Effect.KIND_SOUND.equals(effect.kind)) {
                int index = soundIndex(effect.id);
                if (index >= 0) {
                    SoundUtil.playSoundEvent3d(index, SoundCategory.SFX, crate.x + 0.5, y, crate.z + 0.5,
                            ref -> hasEffects(store, ref), store);
                }
            } else if (particleExists(effect.id)) {
                if (viewers == null) {
                    viewers = withEffects(store, nearby(store, at, module.config().crates.effectRadius));
                }
                if (!viewers.isEmpty()) {
                    ParticleUtil.spawnParticleEffect(effect.id, crate.x + 0.5, y, crate.z + 0.5, 0f, 0f, 0f,
                            (float) effect.scale, null, null, viewers, store);
                }
            }
        }
    }

    /** The crate's stacked effects, cut to {@code crates.maxStackedEffects}. */
    private List<CrateInstance.Effect> stack(CrateInstance crate) {
        List<CrateInstance.Effect> extras = crate.visuals.extras;
        int max = Math.max(0, module.config().crates.maxStackedEffects);
        return extras.size() <= max ? extras : extras.subList(0, max);
    }

    /**
     * One world's idle particle allowance for one tick: a world-wide cap and a
     * per-player cap. A spawn goes to the viewers still under their cap; once the
     * world cap is spent, nothing more spawns this tick.
     */
    private static final class Budget {
        private int world;
        private final int perClient;
        private final Map<Ref<EntityStore>, Integer> spent = new HashMap<>();

        Budget(int world, int perClient) {
            this.world = Math.max(0, world);
            this.perClient = Math.max(0, perClient);
        }

        List<Ref<EntityStore>> take(List<Ref<EntityStore>> viewers) {
            if (world <= 0) {
                return List.of();
            }
            List<Ref<EntityStore>> allowed = new ArrayList<>(viewers.size());
            for (Ref<EntityStore> viewer : viewers) {
                if (spent.getOrDefault(viewer, 0) < perClient) {
                    allowed.add(viewer);
                }
            }
            if (!allowed.isEmpty()) {
                world--;
                allowed.forEach(viewer -> spent.merge(viewer, 1, Integer::sum));
            }
            return allowed;
        }
    }

    /**
     * Shows {@code crate}'s effects to {@code viewer} alone (the crate editor's
     * Preview Effects), ignoring their opt-out since they asked. Runs on the
     * viewer's world thread; the crate must be in that world.
     *
     * @return what was played, for the editor's notice
     */
    public String preview(Ref<EntityStore> viewer, Store<EntityStore> store, CrateInstance crate) {
        Vector3d at = center(crate);
        List<String> played = new ArrayList<>();
        List<Ref<EntityStore>> only = List.of(viewer);
        if (particleExists(crate.visuals.idleParticle)) {
            ParticleUtil.spawnParticleEffect(crate.visuals.idleParticle, at.x, at.y, at.z, 0f, 0f, 0f, 1f, null, null,
                    only, store, 3f);
            played.add("idle " + crate.visuals.idleParticle);
        }
        if (particleExists(crate.visuals.openParticle)) {
            ParticleUtil.spawnParticleEffect(crate.visuals.openParticle, at, only, store);
            played.add("opening " + crate.visuals.openParticle);
        }
        int sound = soundIndex(crate.visuals.openSound);
        if (sound >= 0) {
            SoundUtil.playSoundEvent3dToPlayer(viewer, sound, SoundCategory.SFX, at.x, at.y, at.z, store);
            played.add("sound " + crate.visuals.openSound);
        }
        for (CrateInstance.Effect effect : stack(crate)) {
            double y = crate.y + effect.height;
            if (CrateInstance.Effect.KIND_SOUND.equals(effect.kind)) {
                int index = soundIndex(effect.id);
                if (index >= 0) {
                    SoundUtil.playSoundEvent3dToPlayer(viewer, index, SoundCategory.SFX, crate.x + 0.5, y,
                            crate.z + 0.5, store);
                    played.add(effect.label());
                }
            } else if (particleExists(effect.id)) {
                ParticleUtil.spawnParticleEffect(effect.id, crate.x + 0.5, y, crate.z + 0.5, 0f, 0f, 0f,
                        (float) effect.scale, null, null, only, store, 3f);
                played.add(effect.label());
            }
        }
        return played.isEmpty() ? "nothing (no effects chosen, or their ids aren't loaded)" : String.join(", ", played);
    }

    // ----- Floating names -----------------------------------------------------

    /** The text a crate floats: its own name, else its pool's. */
    public String nameplateText(CrateInstance crate) {
        if (crate.name != null && !crate.name.isBlank()) {
            return MysticText.stripMarkup(crate.name);
        }
        LootBoxRevision live = module.registry().active(crate.lootboxId).orElse(null);
        return live == null ? crate.lootboxId : MysticText.stripMarkup(live.spec.displayName);
    }

    private void ensureNameplate(Store<EntityStore> store, String worldKey, CrateInstance crate, String text,
            double height) {
        Holder existing = nameplates.get(crate.crateId);
        if (existing != null && existing.ref().isValid() && existing.worldKey().equals(worldKey)) {
            if (!existing.text().equals(text)) {
                Nameplate nameplate = store.getComponent(existing.ref(), Nameplate.getComponentType());
                if (nameplate != null) {
                    nameplate.setText(text);
                }
                nameplates.put(crate.crateId, new Holder(worldKey, existing.ref(), text));
            }
            return;
        }
        var holder = EntityStore.REGISTRY.newHolder();
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(
                new Vector3d(crate.x + 0.5, crate.y + height, crate.z + 0.5), Rotation3f.IDENTITY));
        holder.addComponent(NetworkId.getComponentType(), new NetworkId(store.getExternalData().takeNextNetworkId()));
        holder.ensureComponent(Intangible.getComponentType());
        // Every vanilla entity that floats a name also carries a model (the warp
        // marker in TeleportPlugin#createWarp, spawn markers in
        // CompanionBlockSpawnerSupport#createMarkerEntity). The server already
        // replicates a model-less Nameplate (NameplateSystems.EntityTrackerUpdate),
        // so the missing model is the client-side suspect. Invisible_Projectile is
        // vanilla's empty model (NPC/MISC/Empty.blockymodel), so nothing is drawn.
        Model model = holderModel();
        if (model != null) {
            holder.addComponent(BoundingBox.getComponentType(), new BoundingBox(model.getBoundingBox()));
            holder.addComponent(ModelComponent.getComponentType(), new ModelComponent(model));
        }
        holder.addComponent(Nameplate.getComponentType(), new Nameplate(text));
        holder.addComponent(DisplayNameComponent.getComponentType(), new DisplayNameComponent(Message.raw(text)));
        holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
        Ref<EntityStore> ref = store.addEntity(holder, AddReason.SPAWN);
        if (ref != null) {
            nameplates.put(crate.crateId, new Holder(worldKey, ref, text));
        }
    }

    /** The empty vanilla model the nameplate holder wears; null if this build lacks it. */
    private static Model holderModel() {
        ModelAsset asset = ModelAsset.getAssetMap().getAsset(HOLDER_MODEL_ID);
        return asset == null ? null : Model.createUnitScaleModel(asset);
    }

    private static void remove(Store<EntityStore> store, Holder holder) {
        if (holder.ref().isValid()) {
            store.removeEntity(holder.ref(), RemoveReason.REMOVE);
        }
    }

    // ----- Helpers ------------------------------------------------------------

    private static String worldKey(CrateInstance crate) {
        return crate.worldId == null || crate.worldId.isBlank() ? crate.worldName : crate.worldId;
    }

    private static Vector3d center(CrateInstance crate) {
        return new Vector3d(crate.x + 0.5, crate.y + 0.5, crate.z + 0.5);
    }

    private static List<Ref<EntityStore>> nearby(Store<EntityStore> store, Vector3d at, double radius) {
        List<Ref<EntityStore>> found = new ArrayList<>();
        store.getResource(EntityModule.get().getPlayerSpatialResourceType()).getSpatialStructure()
                .collect(at, Math.max(1.0, radius), found);
        return found;
    }

    private List<Ref<EntityStore>> withEffects(Store<EntityStore> store, List<Ref<EntityStore>> players) {
        List<Ref<EntityStore>> viewers = new ArrayList<>(players.size());
        for (Ref<EntityStore> ref : players) {
            if (hasEffects(store, ref)) {
                viewers.add(ref);
            }
        }
        return viewers;
    }

    private boolean hasEffects(Store<EntityStore> store, Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) {
            return false;
        }
        PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
        return player != null && effectsOn(player.getUuid());
    }
}
