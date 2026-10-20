package org.hyzionstudios.mysticessentials.modules.lootbox;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.GrantSource;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.LootBoxApi;
import org.hyzionstudios.mysticessentials.modules.lootbox.command.LootBoxCommand;
import org.hyzionstudios.mysticessentials.modules.lootbox.config.LootBoxConfig;
import org.hyzionstudios.mysticessentials.modules.lootbox.hytale.BlockTargets;
import org.hyzionstudios.mysticessentials.modules.lootbox.hytale.CrateSystems;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.CrateInstance;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PlayerLootState;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.SecureDraw;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.AuditService;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.CrateService;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.KeyService;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.LootBoxRegistry;
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
    private LootBoxUi ui;
    /** Open in-game editors, one per player; the working copies never outlive a disconnect or a disable. */
    private final EditorSessions editors = new EditorSessions();
    private ScheduledFuture<?> sweepTask;
    private ScheduledFuture<?> recoveryTask;

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
        registry = new LootBoxRegistry(core, storage, audit, config);
        PlayerLanes lanes = new PlayerLanes();
        SecureDraw draw = new SecureDraw();
        keys = new KeyService(core, storage, registry, audit, lanes, draw, config);
        try {
            keys.loadSecret();
        } catch (IOException e) {
            core.log(Level.SEVERE, "[lootbox] cannot read or create the key-signing secret; not enabling: " + e);
            return;
        }
        rewards = new RewardService(core, storage, audit, config);
        pending = new PendingRewardService(core, storage, audit, lanes);
        opening = new OpeningService(core, storage, registry, keys, rewards, audit, lanes, draw, config);
        crates = new CrateService(core, storage, audit);
        ui = new LootBoxUi(core, this);

        registry.writeExamplesIfMissing();
        registry.reloadDrafts();
        try {
            CompletableFuture.allOf(registry.loadPublished(), crates.load()).get(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            core.log(Level.WARNING, "[lootbox] published pools or crates are still loading after "
                    + LOAD_TIMEOUT_SECONDS + "s; they will appear when storage answers.");
        } catch (Exception e) {
            core.log(Level.SEVERE, "[lootbox] failed to load published pools or crates: " + e);
        }

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
            }
        });
        registerEvent(PlayerDisconnectEvent.class, (PlayerDisconnectEvent event) -> {
            PlayerRef player = event.getPlayerRef();
            if (player != null) {
                editors.end(player.getUuid());
            }
        });
        // Openings left mid-saga by a crash or disconnect are finished on join; this
        // also retries stuck deliveries for players who stay online.
        recoveryTask = core.scheduler().runRepeating(() -> {
            for (PlayerRef player : core.platform().onlinePlayers()) {
                opening.recover(player);
            }
        }, 5, 5, TimeUnit.MINUTES);
        sweepTask = core.scheduler().runRepeating(audit::sweep, 1, 24, TimeUnit.HOURS);

        active = this;
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
        registry.reloadDrafts();
        log("LootBox drafts and settings reloaded.");
    }

    @Override
    public void onDisable() {
        active = null;
        editors.clear();
        if (recoveryTask != null) {
            recoveryTask.cancel(false);
            recoveryTask = null;
        }
        if (sweepTask != null) {
            sweepTask.cancel(false);
            sweepTask = null;
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
        ui.openPreview(player, crate.lootboxId, crate.crateId);
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

    public LootBoxUi ui() {
        return ui;
    }

    public EditorSessions editors() {
        return editors;
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
