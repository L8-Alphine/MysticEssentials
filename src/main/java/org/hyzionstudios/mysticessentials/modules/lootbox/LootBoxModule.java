package org.hyzionstudios.mysticessentials.modules.lootbox;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.ConditionProvider;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.GrantSource;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.LootBoxApi;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.RewardProvider;
import org.hyzionstudios.mysticessentials.modules.lootbox.command.LootBoxCommand;
import org.hyzionstudios.mysticessentials.modules.lootbox.config.LootBoxConfig;
import org.hyzionstudios.mysticessentials.modules.lootbox.hytale.BlockTargets;
import org.hyzionstudios.mysticessentials.modules.lootbox.hytale.CrateSystems;
import org.hyzionstudios.mysticessentials.modules.lootbox.hytale.CrateVisuals;
import org.hyzionstudios.mysticessentials.modules.lootbox.hytale.GeneratedAssetService;
import org.hyzionstudios.mysticessentials.modules.lootbox.hytale.NativeKeyItems;
import org.hyzionstudios.mysticessentials.modules.lootbox.integration.LootBoxIntegrations;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.CrateInstance;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PlayerLootState;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.SecureDraw;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.AuditService;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.CrateService;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.IntegrationRegistry;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.KeyService;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.LootBoxRegistry;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.NetworkAuthority;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.StatsService;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.event.OpeningCommittedEvent;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.OpeningService;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.PendingRewardService;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.PlayerLanes;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.RewardService;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.TemplateLibrary;
import org.hyzionstudios.mysticessentials.modules.lootbox.storage.LootBoxStorage;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.LootBoxUi;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.EditorSessions;

import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * LootBox: chance-based reward pools with exact, always-visible odds, signed
 * physical and virtual keys, block crates, and a crash-safe opening saga. See
 * {@code docs/lootbox/implementation-plan.md}. This is Phase 1 (Core Engine).
 *
 * <p>Ships <b>disabled</b>: enable it in both the main config's modules map and
 * {@code modules/lootbox/config.json}. A free module: no license feature.</p>
 *
 * <p>The crate systems and the {@code MysticCrate} interaction outlive a disable
 * (the engine cannot unregister them); they look the module up through
 * {@link #active()} and do nothing while it is off.</p>
 */
public final class LootBoxModule extends AbstractMysticModule implements LootBoxApi {

    private static volatile LootBoxModule active;
    /** ECS systems are keyed by class and registered once for the plugin's lifetime. */
    private static volatile boolean systemsRegistered;

    private static final long LOAD_TIMEOUT_SECONDS = 15L;

    private LootBoxConfig config = new LootBoxConfig();
    private LootBoxStorage storage;
    private AuditService audit;
    /** Saved key looks and reward sets (P2-H); created on first use, see {@link #templates()}. */
    private TemplateLibrary templates;
    private LootBoxRegistry registry;
    private KeyService keys;
    private RewardService rewards;
    private PendingRewardService pending;
    private OpeningService opening;
    private CrateService crates;
    /** The generated pack: crate block variants (P2-J) and native key items (P3-WP8). */
    private GeneratedAssetService generated;
    private CrateVisuals visuals;
    private LootBoxUi ui;
    /** Open in-game editors, one per player; the working copies never outlive a disconnect or a disable. */
    private final EditorSessions editors = new EditorSessions();
    /**
     * Reward and condition providers (P3-WP1). Kept for the plugin's lifetime:
     * other plugins register once when they start, whether or not LootBox is on.
     */
    private final IntegrationRegistry integrations = new IntegrationRegistry();
    /** The Mystic sibling bridges (P3-WP7); created on first enable, attached while enabled. */
    private LootBoxIntegrations bridges;
    private ScheduledFuture<?> sweepTask;
    private ScheduledFuture<?> scheduleTask;
    /** How often scheduled revisions and time-limited odds are checked. */
    private static final long SCHEDULE_CHECK_SECONDS = 30L;
    private ScheduledFuture<?> recoveryTask;
    /** Observed-odds statistics and the retention sweep (P4-WP4/WP5). */
    private StatsService stats;
    private ScheduledFuture<?> anomalyTask;
    private org.hyzionstudios.mysticessentials.api.event.EventBus.Subscription statsSubscription;
    /** Network authority (P4); its blocking Redis calls run on {@link #networkThreads}. */
    private NetworkAuthority network;
    private ExecutorService networkThreads;
    private ScheduledExecutorService networkRenewals;
    /** The Redis channel pool and key changes travel on (P4-WP3). */
    static final String CHANGES_CHANNEL = "lootbox:changes";
    private java.util.function.Consumer<String> changesHandler;

    public LootBoxModule() {
        super("lootbox", "LootBox", "1.0.0");
    }

    /** The enabled module, or {@code null}. Used by the crate systems and interaction. */
    public static LootBoxModule active() {
        return active;
    }

    @Override
    public void onEnable() {
        config = core.configManager().loadModuleConfig(id(), LootBoxConfig.class, new LootBoxConfig());
        if (!config.enabled) {
            log("LootBox is disabled in modules/lootbox/config.json; not registering.");
            return;
        }
        storage = new LootBoxStorage(core);
        audit = new AuditService(core, storage, config);
        registry = new LootBoxRegistry(core, storage, audit, integrations, config);
        PlayerLanes lanes = new PlayerLanes();
        SecureDraw draw = new SecureDraw();
        keys = new KeyService(core, storage, registry, audit, lanes, draw, config);
        try {
            keys.loadSecret();
        } catch (IOException e) {
            core.log(Level.SEVERE, "[lootbox] cannot read or create the key-signing secret; not enabling: " + e);
            return;
        }
        audit.pseudonymSalt(keys.secret());
        rewards = new RewardService(core, storage, audit, integrations, config);
        pending = new PendingRewardService(core, storage, audit, lanes);
        opening = new OpeningService(core, storage, registry, keys, rewards, audit, lanes, draw, integrations, config);
        startNetwork();
        stats = new StatsService(storage, registry, audit, () -> this.config,
                flag -> core.getEventBus().publish(flag),
                (message, error) -> core.log(Level.WARNING,
                        "[lootbox] " + message + (error == null ? "" : ": " + error)));
        statsSubscription = core.getEventBus().subscribe(OpeningCommittedEvent.class,
                event -> stats.onCommitted(event, System.currentTimeMillis()));
        generated = new GeneratedAssetService(core);
        crates = new CrateService(core, storage, audit, generated, () -> this.config.crates.generatedVariants);
        visuals = new CrateVisuals(core, this, storage);
        ui = new LootBoxUi(core, this);
        if (bridges == null) {
            bridges = new LootBoxIntegrations(core, integrations, () -> this.config);
        }

        registry.writeExamplesIfMissing();
        registry.reloadDrafts();
        boolean cratesLoaded = false;
        try {
            CompletableFuture.allOf(registry.loadPublished(), crates.load()).get(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            cratesLoaded = true;
        } catch (TimeoutException e) {
            core.log(Level.WARNING, "[lootbox] published pools or crates are still loading after "
                    + LOAD_TIMEOUT_SECONDS + "s; they will appear when storage answers.");
        } catch (Exception e) {
            core.log(Level.SEVERE, "[lootbox] failed to load published pools or crates: " + e);
        }

        // Generated look-alikes must resolve before any crate is used; unused ones are only
        // cleaned up when the whole crate index is known.
        crates.startVariants(cratesLoaded);
        syncNativeKeyItems();
        bridges.enable(this, storage, audit, this::publishedKeyIds);
        keys.paidGate(bridges::paidRefusal);
        registerCommand(new LootBoxCommand(core, this, config.command.name, config.command.aliases));
        if (!systemsRegistered) {
            boolean all = true;
            for (var system : CrateSystems.all()) {
                all &= core.platform().registerEntitySystem(system);
            }
            systemsRegistered = true;
            if (!all) {
                log("Some crate systems could not be registered; crates may not open or be protected.");
            }
        }
        registerEvent(PlayerConnectEvent.class, (PlayerConnectEvent event) -> {
            PlayerRef player = event.getPlayerRef();
            if (player != null) {
                opening.recover(player);
                bridges.playerJoined(player.getUuid());
            }
        });
        registerEvent(PlayerDisconnectEvent.class, (PlayerDisconnectEvent event) -> {
            PlayerRef player = event.getPlayerRef();
            if (player != null) {
                editors.end(player.getUuid());
                bridges.playerLeft(player.getUuid());
            }
        });
        // Openings left mid-saga by a crash or disconnect are finished on join; this
        // also retries stuck deliveries for players who stay online.
        recoveryTask = core.scheduler().runRepeating(() -> {
            for (PlayerRef player : core.platform().onlinePlayers()) {
                opening.recover(player);
            }
        }, 5, 5, TimeUnit.MINUTES);
        sweepTask = core.scheduler().runRepeating(() -> {
            audit.sweep();
            stats.sweep(System.currentTimeMillis());
        }, 1, 24, TimeUnit.HOURS);
        anomalyTask = core.scheduler().runRepeating(() -> stats.checkAnomalies(System.currentTimeMillis()),
                1, 1, TimeUnit.HOURS);
        // Scheduled revisions start, time-limited odds end, scheduled rollbacks run (P3-WP5).
        // Also attaches integrations that started after this plugin, and new keys' placeholders.
        scheduleTask = core.scheduler().runRepeating(() -> {
            registry.runSchedule(System.currentTimeMillis());
            bridges.tick();
            bridges.keysChanged(publishedKeyIds());
            syncNativeKeyItems();
        }, SCHEDULE_CHECK_SECONDS, SCHEDULE_CHECK_SECONDS, TimeUnit.SECONDS);

        active = this;
        visuals.start();
        boolean shippedCrate = BlockTargets.usableBlockLoaded(CrateService.SHIPPED_PREFIX);
        log("LootBox enabled: " + registry.publishedPools().size() + " published pool(s), " + registry.keys().size()
                + " key(s), " + crates.list().size() + " crate(s), policy " + opening.policyProfile()
                + ", storage " + core.getStorageService().activeProvider() + ", shipped crate block "
                + (shippedCrate ? "loaded" : "MISSING") + ".");
        if (!shippedCrate) {
            log("The " + CrateService.SHIPPED_PREFIX + " block is not loaded or has no Use interaction. Check that "
                    + "the Mystic Essentials asset pack is enabled; chests, doors and benches can still be crates.");
        }
    }

    @Override
    public void onReload() {
        config = core.configManager().loadModuleConfig(id(), LootBoxConfig.class, new LootBoxConfig());
        if (active == null) {
            return;
        }
        audit.updateConfig(config);
        registry.updateConfig(config);
        keys.updateConfig(config);
        rewards.updateConfig(config);
        opening.updateConfig(config);
        visuals.reschedule();
        registry.reloadDrafts();
        log("LootBox drafts and settings reloaded.");
    }

    @Override
    public void onDisable() {
        active = null;
        editors.clear();
        if (visuals != null) {
            visuals.stop();
        }
        if (recoveryTask != null) {
            recoveryTask.cancel(false);
            recoveryTask = null;
        }
        if (sweepTask != null) {
            sweepTask.cancel(false);
            sweepTask = null;
        }
        if (scheduleTask != null) {
            scheduleTask.cancel(false);
            scheduleTask = null;
        }
        if (bridges != null) {
            bridges.disable();
        }
        NativeKeyItems.clear();
        stopNetwork();
        if (anomalyTask != null) {
            anomalyTask.cancel(false);
            anomalyTask = null;
        }
        if (statsSubscription != null) {
            statsSubscription.close();
            statsSubscription = null;
        }
    }

    // ----- Network (P4) -------------------------------------------------------

    /**
     * The network authority NETWORK pools open under, and revision and key
     * propagation over Redis. Built always (it answers "unavailable" while
     * {@code network.enabled} is off); propagation only runs when it is on.
     */
    private void startNetwork() {
        networkThreads = Executors.newCachedThreadPool(daemon("MysticEssentials-LootBox-Net"));
        networkRenewals = Executors.newSingleThreadScheduledExecutor(daemon("MysticEssentials-LootBox-Lease"));
        NetworkAuthority.Lock lock = new NetworkAuthority.Lock() {
            @Override
            public boolean available() {
                return core.redis() != null && core.redis().isEnabled();
            }

            @Override
            public boolean acquire(String key, String payload, long ttlSeconds) {
                return available() && core.redis().lockAcquire(key, payload, ttlSeconds);
            }

            @Override
            public boolean renew(String key, String payload, long ttlSeconds) {
                return available() && core.redis().lockRenew(key, payload, ttlSeconds);
            }

            @Override
            public boolean release(String key, String payload) {
                return available() && core.redis().lockRelease(key, payload);
            }
        };
        network = new NetworkAuthority(storage, lock, networkThreads, networkRenewals, () -> {
            LootBoxConfig.Network n = config.network == null ? new LootBoxConfig.Network() : config.network;
            long ttl = Math.max(5L, n.lockTtlSeconds);
            return new NetworkAuthority.Timing(ttl, Math.max(1L, Math.min(n.renewSeconds, ttl / 2)),
                    Math.max(0L, n.acquireTimeoutMs), Math.max(5L, n.retryMs));
        }, () -> config.network != null && config.network.enabled,
                () -> core.redis() == null ? null : core.redis().networkId(),
                () -> core.redis() == null || core.redis().serverId() == null ? "local" : core.redis().serverId(),
                System::currentTimeMillis);
        opening.network(network);
        if (config.network == null || !config.network.enabled) {
            return;
        }
        String fingerprint = fingerprint(keys.secret());
        storage.claimSecretFingerprint(fingerprint).whenComplete((recorded, error) -> {
            boolean agreed = error == null && fingerprint.equals(recorded);
            network.secretAgreed(agreed);
            if (!agreed) {
                core.log(Level.SEVERE, "[lootbox] network: this server's secret.key differs from the network's"
                        + (error == null ? "" : " (or the shared store did not answer: " + error + ")")
                        + ". Copy secret.key from the LootBox data folder of the first server to every server; "
                        + "NETWORK pools stay closed here until then.");
            }
        });
        if (core.redis() != null) {
            changesHandler = this::onRemoteChange;
            core.redis().subscribe(CHANGES_CHANNEL, changesHandler);
            registry.onPoolChange(id -> core.redis().publish(CHANGES_CHANNEL, "pool:" + id));
            registry.onKeyChange(id -> core.redis().publish(CHANGES_CHANNEL, "key:" + id));
        }
        String problem = network.unavailable();
        log("LootBox network mode: " + (problem == null ? "ready" : "NETWORK pools closed (" + problem + ")") + ".");
    }

    /** Another server changed a pool or published a key: re-read it (never re-announced, so no echo loop). */
    private void onRemoteChange(String payload) {
        if (registry == null || payload == null) {
            return;
        }
        if (payload.startsWith("pool:")) {
            registry.refreshPool(payload.substring(5));
        } else if (payload.startsWith("key:")) {
            registry.refreshKey(payload.substring(4)).thenRun(this::syncNativeKeyItems);
        }
    }

    private void stopNetwork() {
        if (changesHandler != null && core.redis() != null) {
            core.redis().unsubscribe(CHANGES_CHANNEL, changesHandler);
        }
        changesHandler = null;
        if (registry != null) {
            registry.onPoolChange(null);
            registry.onKeyChange(null);
        }
        if (networkRenewals != null) {
            networkRenewals.shutdownNow();
            networkRenewals = null;
        }
        if (networkThreads != null) {
            networkThreads.shutdown();
            networkThreads = null;
        }
        network = null;
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** SHA-256 of the key-signing secret, hex; the only form of it that leaves this server. */
    static String fingerprint(byte[] secret) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(secret == null ? new byte[0] : secret));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ----- Crate callbacks (from the crate systems and interaction) -----------

    /** A player used a block bound to a pool. Runs on the world thread; opening the page is deferred. */
    public void onCrateUse(PlayerRef player, CrateInstance crate, String blockKey) {
        if (crate.disabled) {
            core.getMessageService().sendKey(player, "lootbox-crate-inactive");
            return;
        }
        if (crate.blockKey != null && !crate.blockKey.equals(blockKey)) {
            crates.disable(crate, "block is now " + blockKey, "server");
            core.getMessageService().sendKey(player, "lootbox-crate-inactive");
            return;
        }
        if (!player.hasPermission(Permissions.LOOTBOX_USE)) {
            core.getMessageService().sendKey(player, "no-permission");
            return;
        }
        ui.openPreview(player, crate.lootboxId, crate.crateId, crate.directConfirm());
    }

    public void onCrateProtected(PlayerRef player) {
        if (player != null) {
            core.getMessageService().sendKey(player, "lootbox-crate-protected");
        }
    }

    /** The environment destroyed a crate's block (not cancellable): disable it and tell staff. */
    public void onCrateDestroyed(CrateInstance crate, String reason) {
        crates.disable(crate, reason, "environment");
        for (PlayerRef staff : core.platform().onlinePlayers()) {
            if (staff.hasPermission(Permissions.LOOTBOX_CRATES_MANAGE)) {
                core.getMessageService().sendKey(staff, "lootbox-crate-broken-alert",
                        Map.of("crate", crate.crateId, "position", crate.position()));
            }
        }
    }

    public void onUnboundCrateUse(PlayerRef player) {
        core.getMessageService().sendKey(player, player.hasPermission(Permissions.LOOTBOX_CRATES_PLACE)
                ? "lootbox-crate-unbound-admin" : "lootbox-crate-unbound");
    }

    // ----- Accessors ----------------------------------------------------------

    public LootBoxConfig config() {
        return config;
    }

    public AuditService audit() {
        return audit;
    }

    /**
     * The Template Library's files in {@code templates/}. One store for the
     * plugin's lifetime, so its saves stay serialised across a disable; its audit
     * entries go to whichever audit service is current.
     */
    public synchronized TemplateLibrary templates() {
        if (templates == null) {
            templates = new TemplateLibrary(core.paths().moduleConfigDir(id()).resolve(TemplateLibrary.FOLDER),
                    (actor, action, target, detail) -> {
                        if (audit != null) {
                            audit.record(actor, action, target, detail);
                        }
                    }, ForkJoinPool.commonPool());
        }
        return templates;
    }

    public LootBoxRegistry registry() {
        return registry;
    }

    public KeyService keys() {
        return keys;
    }

    public OpeningService opening() {
        return opening;
    }

    public PendingRewardService pending() {
        return pending;
    }

    public CrateService crates() {
        return crates;
    }

    public CrateVisuals visuals() {
        return visuals;
    }

    public LootBoxUi ui() {
        return ui;
    }

    public EditorSessions editors() {
        return editors;
    }

    /** Statistics (P4-WP4), or {@code null} while disabled. */
    public StatsService stats() {
        return stats;
    }

    /** The network authority (P4), or {@code null} while disabled. */
    public NetworkAuthority network() {
        return network;
    }

    /** The Mystic sibling bridges, or {@code null} before the first enable. */
    public LootBoxIntegrations bridges() {
        return bridges;
    }

    /** Native key items (P3-WP8) for keys that ask for them, while {@code keys.nativeKeyItems} is on. */
    private void syncNativeKeyItems() {
        try {
            NativeKeyItems.sync(registry.keys(), generated, config.keys.nativeKeyItems);
        } catch (RuntimeException e) {
            core.log(Level.WARNING, "[lootbox] native key items could not be prepared: " + e);
        }
    }

    private java.util.List<String> publishedKeyIds() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        if (registry != null) {
            for (var key : registry.keys()) {
                if (key.spec != null && key.spec.id != null) {
                    ids.add(key.spec.id);
                }
            }
        }
        return ids;
    }

    // ----- LootBoxApi ---------------------------------------------------------

    @Override
    public Optional<LootBoxRevision> activeRevision(String lootboxId) {
        return registry == null ? Optional.empty() : registry.active(lootboxId);
    }

    @Override
    public CompletableFuture<KeyService.GrantResult> grantKey(UUID player, String keyId, int count, boolean virtual,
            GrantSource source, String actor) {
        if (keys == null) {
            return CompletableFuture.completedFuture(new KeyService.GrantResult(false, 0, 0, "LootBox is disabled"));
        }
        return keys.grant(player, keyId, count, virtual, source == null ? GrantSource.API : source,
                actor == null ? "api" : actor);
    }

    @Override
    public CompletableFuture<java.util.List<PlayerLootState.Receipt>> receipts(UUID player) {
        return opening == null ? CompletableFuture.completedFuture(java.util.List.of()) : opening.history(player);
    }

    @Override
    public IntegrationRegistry integrations() {
        return integrations;
    }

    @Override
    public void registerRewardProvider(RewardProvider provider) {
        integrations.register(provider);
    }

    @Override
    public boolean unregisterRewardProvider(RewardProvider provider) {
        return integrations.unregister(provider);
    }

    @Override
    public void registerConditionProvider(ConditionProvider provider) {
        integrations.register(provider);
    }

    @Override
    public boolean unregisterConditionProvider(ConditionProvider provider) {
        return integrations.unregister(provider);
    }

    @Override
    public void openPreview(PlayerRef player, String lootboxId) {
        if (ui != null) {
            ui.openPreview(player, lootboxId, null);
        }
    }

    @Override
    public void openCatalog(PlayerRef player) {
        if (ui != null) {
            ui.openCatalog(player);
        }
    }
}
