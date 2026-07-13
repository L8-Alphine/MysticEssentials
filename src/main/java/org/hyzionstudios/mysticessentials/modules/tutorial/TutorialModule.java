package org.hyzionstudios.mysticessentials.modules.tutorial;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.modules.tutorial.command.TutorialCommand;
import org.hyzionstudios.mysticessentials.modules.tutorial.config.TutorialConfigLoader;
import org.hyzionstudios.mysticessentials.modules.tutorial.config.TutorialDefinition;
import org.hyzionstudios.mysticessentials.modules.tutorial.config.TutorialModuleConfig;
import org.hyzionstudios.mysticessentials.modules.tutorial.scene.CameraSceneProvider;
import org.hyzionstudios.mysticessentials.modules.tutorial.scene.DebugSceneProvider;
import org.hyzionstudios.mysticessentials.modules.tutorial.scene.MachinimaSceneAssets;
import org.hyzionstudios.mysticessentials.modules.tutorial.scene.MachinimaSceneProvider;
import org.hyzionstudios.mysticessentials.modules.tutorial.scene.NoOpSceneProvider;
import org.hyzionstudios.mysticessentials.modules.tutorial.scene.TutorialSceneProvider;
import org.hyzionstudios.mysticessentials.modules.tutorial.session.TutorialPlayOptions;
import org.hyzionstudios.mysticessentials.modules.tutorial.session.TutorialSession;
import org.hyzionstudios.mysticessentials.modules.tutorial.session.TutorialSessionManager;
import org.hyzionstudios.mysticessentials.modules.tutorial.session.TutorialStopReason;
import org.hyzionstudios.mysticessentials.modules.tutorial.storage.JsonTutorialStorage;
import org.hyzionstudios.mysticessentials.modules.tutorial.ui.TutorialButtonActionHandler;
import org.hyzionstudios.mysticessentials.modules.tutorial.ui.TutorialPageService;
import org.hyzionstudios.mysticessentials.modules.tutorial.util.TutorialLogger;

import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Tutorial module: cinematic (machinima) and UI-page tutorials with a
 * first-join flow, JSON definitions, per-player completion data, and a
 * failsafe that guarantees no player is ever left frozen. Ships disabled by
 * default — both in the main config's modules map and via {@code enabled} in
 * its own {@code config.json} — and exposes {@link TutorialService} to other
 * modules through the standard module system.
 */
public final class TutorialModule extends AbstractMysticModule implements TutorialService {

    private TutorialModuleConfig config = new TutorialModuleConfig();
    private TutorialConfigLoader loader;
    private TutorialLogger logger;
    private JsonTutorialStorage storage;
    private TutorialSessionManager sessions;
    private TutorialPageService pageService;
    private TutorialButtonActionHandler actionHandler;
    private TutorialServiceImpl service;

    private MachinimaSceneAssets sceneAssets;
    private NoOpSceneProvider noOpProvider;
    private DebugSceneProvider debugProvider;
    private MachinimaSceneProvider machinimaProvider;
    private CameraSceneProvider cameraProvider;

    /** Guards listeners after onDisable (Hytale has no listener unregistration). */
    private volatile boolean active;

    public TutorialModule() {
        super("tutorial", "Tutorial", "1.0.0");
    }

    // ----- Lifecycle -------------------------------------------------------------

    @Override
    public void onEnable() {
        config = core.configManager().loadModuleConfig(id(), TutorialModuleConfig.class,
                new TutorialModuleConfig());
        logger = new TutorialLogger(core, id(), () -> config);
        loader = new TutorialConfigLoader(core, id());
        loader.load();

        storage = new JsonTutorialStorage(core, id());
        storage.start(config.storage.autosaveSeconds);

        sceneAssets = new MachinimaSceneAssets(core, id());
        noOpProvider = new NoOpSceneProvider();
        debugProvider = new DebugSceneProvider(core);
        machinimaProvider = new MachinimaSceneProvider(core, sceneAssets);
        cameraProvider = new CameraSceneProvider(core, sceneAssets, () -> config.cameraPlayback);

        sessions = new TutorialSessionManager(core, this);
        pageService = new TutorialPageService(core, this);
        actionHandler = new TutorialButtonActionHandler(core, this);
        service = new TutorialServiceImpl(this);

        registerCommand(new TutorialCommand(core, this));
        registerListeners();
        active = true;

        if (!config.enabled) {
            log("Loaded but switched off (set \"enabled\": true in modules/tutorial/config.json"
                    + " and run /tutorial reload).");
        }
    }

    @Override
    public void onReload() {
        config = core.configManager().loadModuleConfig(id(), TutorialModuleConfig.class,
                new TutorialModuleConfig());
        loader.load();
        // Re-read scene assets from disk on reload so edited/added scene files
        // take effect without a restart.
        if (sceneAssets != null) {
            sceneAssets.clearCache();
        }
    }

    @Override
    public void onDisable() {
        active = false;
        if (sessions != null) {
            sessions.stopAll(TutorialStopReason.MODULE_DISABLED);
        }
        if (storage != null) {
            storage.shutdown();
        }
    }

    private void registerListeners() {
        registerEvent(PlayerConnectEvent.class,
                (PlayerConnectEvent event) -> onJoin(event.getPlayerRef()));
        registerEvent(PlayerDisconnectEvent.class,
                (PlayerDisconnectEvent event) -> onQuit(event.getPlayerRef()));
        // Chat block while a session says so (async cancellable chat event).
        registerAsyncEvent(PlayerChatEvent.class,
                (CompletableFuture<PlayerChatEvent> future) -> future.thenApply(event -> {
                    if (active && config.enabled && event.getSender() != null
                            && sessions.isChatBlocked(event.getSender().getUuid())) {
                        event.setCancelled(true);
                        core.getMessageService().sendKey(event.getSender(), "tutorial-chat-blocked");
                    }
                    return event;
                }));
    }

    // ----- Join / quit flow ---------------------------------------------------------

    private void onJoin(PlayerRef player) {
        if (!active || !config.enabled) {
            return;
        }
        storage.load(player.getUuid(), player.getUsername()).thenAccept(data -> {
            // Repair an unclean exit first (lingering invulnerability/HUD/camera).
            sessions.recoverOnJoin(player, data);
            scheduleFirstJoin(player, data.hasCompleted(config.firstJoin.tutorialId));
        });
    }

    private void scheduleFirstJoin(PlayerRef player, boolean alreadyCompleted) {
        TutorialModuleConfig.FirstJoin firstJoin = config.firstJoin;
        if (!firstJoin.enabled) {
            return;
        }
        if (firstJoin.respectBypassPermission && firstJoin.bypassPermission != null
                && !firstJoin.bypassPermission.isBlank()
                && player.hasPermission(firstJoin.bypassPermission)) {
            return;
        }
        if (firstJoin.runOnlyOnce && alreadyCompleted) {
            return;
        }
        long delayMillis = Math.max(0, firstJoin.delayTicksAfterJoin) * 50L;
        core.scheduler().runLater(() -> {
            if (!active || !config.enabled) {
                return;
            }
            PlayerRef online = core.platform().findPlayer(player.getUuid()).orElse(null);
            if (online == null || sessions.isInTutorial(online.getUuid())) {
                return;
            }
            sessions.start(online, firstJoin.tutorialId,
                            TutorialPlayOptions.of(TutorialPlayOptions.Source.FIRST_JOIN))
                    .thenAccept(result -> {
                        if (!result.started()) {
                            logger.debug("First-join tutorial for " + online.getUsername()
                                    + " not started: " + result);
                        }
                    });
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void onQuit(PlayerRef player) {
        if (sessions != null) {
            // Ends the session and keeps the recovery marker (restore cannot run
            // on a gone entity); the data is flushed by the unload below.
            sessions.stop(player.getUuid(), TutorialStopReason.DISCONNECT);
        }
        if (storage != null) {
            storage.unload(player.getUuid());
        }
    }

    // ----- Module internals exposed to the tutorial packages -------------------------

    public TutorialModuleConfig config() {
        return config;
    }

    public TutorialConfigLoader loader() {
        return loader;
    }

    public TutorialLogger logger() {
        return logger;
    }

    public JsonTutorialStorage storage() {
        return storage;
    }

    public TutorialSessionManager sessions() {
        return sessions;
    }

    public TutorialPageService pageService() {
        return pageService;
    }

    public TutorialButtonActionHandler actionHandler() {
        return actionHandler;
    }

    /** Shared scene-asset store (import/list/load), used by both scene providers. */
    public MachinimaSceneAssets sceneAssets() {
        return sceneAssets;
    }

    /** The machinima scene provider (kept for a future client that receives the packet). */
    public MachinimaSceneProvider machinimaProvider() {
        return machinimaProvider;
    }

    /** Persists a runtime debug toggle ({@code /tutorial debug on|off}). */
    public void setDebug(boolean debug) {
        config.debug = debug;
        try {
            Json.writeFile(core.paths().moduleConfigFile(id()), config);
        } catch (Exception e) {
            core.log(Level.WARNING, "[" + id() + "] Could not persist debug flag: " + e.getMessage());
        }
    }

    /**
     * Resolves the scene provider for the configured type, honouring
     * {@code fallbackToNoOp}. Returns {@code null} only when the configured
     * provider is unavailable and fallback is switched off.
     */
    public TutorialSceneProvider resolveSceneProvider() {
        String type = config.sceneProvider.type == null ? "machinima"
                : config.sceneProvider.type.toLowerCase(Locale.ROOT);
        TutorialSceneProvider chosen = switch (type) {
            case "camera" -> cameraProvider;
            case "machinima" -> machinimaProvider;
            case "debug" -> debugProvider;
            case "noop" -> noOpProvider;
            default -> null;
        };
        if (chosen == null) {
            if (config.sceneProvider.logMissingSceneProvider) {
                logger.error("Unknown sceneProvider.type '" + type + "'"
                        + (config.sceneProvider.fallbackToNoOp ? "; using noop." : "."));
            }
            return config.sceneProvider.fallbackToNoOp ? noOpProvider : null;
        }
        if (!chosen.isAvailable()) {
            if (config.sceneProvider.logMissingSceneProvider) {
                logger.error("Scene provider '" + chosen.id() + "' is unavailable"
                        + (config.sceneProvider.fallbackToNoOp ? "; using noop." : "."));
            }
            return config.sceneProvider.fallbackToNoOp ? noOpProvider : null;
        }
        return chosen;
    }

    // ----- TutorialService (delegated) -------------------------------------------------

    @Override
    public CompletableFuture<TutorialStartResult> playTutorial(PlayerRef player, String tutorialId,
            TutorialPlayOptions options) {
        return service.playTutorial(player, tutorialId, options);
    }

    @Override
    public CompletableFuture<TutorialStopResult> stopTutorial(PlayerRef player, TutorialStopReason reason) {
        return service.stopTutorial(player, reason);
    }

    @Override
    public boolean isInTutorial(PlayerRef player) {
        return service.isInTutorial(player);
    }

    @Override
    public Optional<TutorialSession> getActiveSession(PlayerRef player) {
        return service.getActiveSession(player);
    }

    @Override
    public boolean hasCompleted(PlayerRef player, String tutorialId) {
        return service.hasCompleted(player, tutorialId);
    }

    @Override
    public void markCompleted(PlayerRef player, String tutorialId) {
        service.markCompleted(player, tutorialId);
    }

    @Override
    public void resetCompletion(PlayerRef player, String tutorialId) {
        service.resetCompletion(player, tutorialId);
    }

    @Override
    public Collection<TutorialDefinition> getTutorials() {
        return service.getTutorials();
    }

    @Override
    public Optional<TutorialDefinition> getTutorial(String tutorialId) {
        return service.getTutorial(tutorialId);
    }

    @Override
    public CompletableFuture<Void> openPage(PlayerRef player, String pageId) {
        return service.openPage(player, pageId);
    }
}
