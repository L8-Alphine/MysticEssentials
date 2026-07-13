package org.hyzionstudios.mysticessentials.modules.tutorial.session;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.tutorial.TutorialEvents;
import org.hyzionstudios.mysticessentials.modules.tutorial.TutorialModule;
import org.hyzionstudios.mysticessentials.modules.tutorial.TutorialStartResult;
import org.hyzionstudios.mysticessentials.modules.tutorial.TutorialStopResult;
import org.hyzionstudios.mysticessentials.modules.tutorial.config.TutorialDefinition;
import org.hyzionstudios.mysticessentials.modules.tutorial.config.TutorialModuleConfig;
import org.hyzionstudios.mysticessentials.modules.tutorial.player.PlayerStateSnapshot;
import org.hyzionstudios.mysticessentials.modules.tutorial.player.TutorialPlayerData;
import org.hyzionstudios.mysticessentials.modules.tutorial.scene.TutorialSceneProvider;
import org.hyzionstudios.mysticessentials.modules.tutorial.scene.TutorialSceneRequest;
import org.hyzionstudios.mysticessentials.modules.tutorial.scene.TutorialSceneResult;
import org.hyzionstudios.mysticessentials.modules.tutorial.util.TutorialPlaceholders;
import org.hyzionstudios.mysticessentials.platform.Conversions;

import com.hypixel.hytale.protocol.packets.interface_.HudComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.CameraManager;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Owns every running {@link TutorialSession}: validation, player-state
 * capture/apply/restore, the scene phase, the failsafe timeout, and the
 * completion flow. All entity mutation runs on the player's world thread via
 * {@code HytalePlatform.runOnEntityThread}; the freeze uses the same verified
 * {@code MovementManager} settings surface as the Flight module, damage
 * blocking uses the builtin {@code Invulnerable} component pattern
 * ({@code ensureComponent}/{@code tryRemoveComponent}), and HUD hiding uses
 * {@code HudManager.setVisibleHudComponents}.
 *
 * <p>The one invariant everything here defends: <b>a player is never left
 * frozen</b>. Every session carries a failsafe timeout, every exit path funnels
 * through {@link #finish}, restores are idempotent
 * ({@link PlayerStateSnapshot#beginRestore()}), and an unclean exit leaves a
 * recovery marker in the player's data that is repaired on next join.</p>
 */
public final class TutorialSessionManager {

    private final MysticCore core;
    private final TutorialModule module;
    private final Map<UUID, TutorialSession> sessions = new ConcurrentHashMap<>();

    public TutorialSessionManager(MysticCore core, TutorialModule module) {
        this.core = core;
        this.module = module;
    }

    // ----- Queries -------------------------------------------------------------

    public Optional<TutorialSession> session(UUID playerId) {
        return Optional.ofNullable(sessions.get(playerId));
    }

    public boolean isInTutorial(UUID playerId) {
        return sessions.containsKey(playerId);
    }

    /** Used by the chat listener: block chat while a session says so. */
    public boolean isChatBlocked(UUID playerId) {
        TutorialSession session = sessions.get(playerId);
        return session != null && session.isActive() && session.playerState.disableChat();
    }

    public int activeCount() {
        return sessions.size();
    }

    // ----- Start ---------------------------------------------------------------

    public CompletableFuture<TutorialStartResult> start(PlayerRef player, String tutorialId,
            TutorialPlayOptions options) {
        TutorialModuleConfig config = module.config();
        if (!config.enabled) {
            return CompletableFuture.completedFuture(TutorialStartResult.MODULE_DISABLED);
        }
        TutorialDefinition definition = module.loader().tutorial(tutorialId).orElse(null);
        if (definition == null) {
            return CompletableFuture.completedFuture(TutorialStartResult.UNKNOWN_TUTORIAL);
        }
        if (!definition.enabled && !options.force()) {
            return CompletableFuture.completedFuture(TutorialStartResult.TUTORIAL_DISABLED);
        }
        TutorialSession existing = sessions.get(player.getUuid());
        if (existing != null) {
            if (!options.force()) {
                return CompletableFuture.completedFuture(TutorialStartResult.ALREADY_IN_TUTORIAL);
            }
            stop(player.getUuid(), TutorialStopReason.ADMIN);
        }
        return module.storage().load(player.getUuid(), player.getUsername())
                .thenApply(data -> beginValidated(player, definition, options, data))
                .exceptionally(t -> {
                    module.logger().error("Failed to start '" + tutorialId + "' for "
                            + player.getUsername() + ": " + t);
                    return TutorialStartResult.ERROR;
                });
    }

    private TutorialStartResult beginValidated(PlayerRef player, TutorialDefinition definition,
            TutorialPlayOptions options, TutorialPlayerData data) {
        boolean completedBefore = data.hasCompleted(definition.id);
        if (completedBefore && !definition.replay.allowReplay
                && !(options.force() && definition.replay.adminCanForceReplay)) {
            return TutorialStartResult.ALREADY_COMPLETED;
        }
        if (!options.force() && !requirementsMet(player, definition, data)) {
            return TutorialStartResult.REQUIREMENTS_NOT_MET;
        }

        TutorialSession session = new TutorialSession(player, definition, options,
                resolveState(definition));
        if (sessions.putIfAbsent(player.getUuid(), session) != null) {
            return TutorialStartResult.ALREADY_IN_TUTORIAL;
        }

        synchronized (data) {
            data.activeTutorialId = definition.id;
            data.record(definition.id).timesPlayed++;
            data.addHistory(definition.id, "start", System.currentTimeMillis());
        }
        module.storage().markDirty(player.getUuid());
        module.logger().start(player.getUsername(), definition.id, options.source().name());
        core.getEventBus().publish(new TutorialEvents.Start(
                player.getUuid(), player.getUsername(), definition.id));

        scheduleFailsafe(session);
        if (!captureAndApply(session)) {
            finish(session, TutorialStopReason.FAILED, "player entity unavailable");
            return TutorialStartResult.PLAYER_OFFLINE;
        }

        int delayTicks = Math.max(0, definition.machinima.startDelayTicks);
        session.setStartTask(core.scheduler().runLater(() -> beginScenePhase(session),
                delayTicks * 50L, TimeUnit.MILLISECONDS));
        return TutorialStartResult.STARTED;
    }

    private boolean requirementsMet(PlayerRef player, TutorialDefinition definition,
            TutorialPlayerData data) {
        TutorialDefinition.Requirements req = definition.requirements;
        if (req == null) {
            return true;
        }
        if (req.permissions != null) {
            for (String permission : req.permissions) {
                if (permission != null && !permission.isBlank() && !player.hasPermission(permission)) {
                    return false;
                }
            }
        }
        if (req.worlds != null && !req.worlds.isEmpty()) {
            String world = Conversions.resolveWorldName(player.getWorldUuid());
            boolean match = world != null && req.worlds.stream()
                    .anyMatch(w -> w != null && w.equalsIgnoreCase(world));
            if (!match) {
                return false;
            }
        }
        if (req.mustHaveCompleted != null) {
            for (String required : req.mustHaveCompleted) {
                if (required != null && !required.isBlank() && !data.hasCompleted(required)) {
                    return false;
                }
            }
        }
        if (req.mustNotHaveCompleted != null) {
            for (String excluded : req.mustNotHaveCompleted) {
                if (excluded != null && !excluded.isBlank() && data.hasCompleted(excluded)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Merges the tutorial's playerState overrides onto the module defaults. */
    private TutorialSession.ResolvedPlayerState resolveState(TutorialDefinition definition) {
        TutorialModuleConfig.Defaults defaults = module.config().defaults;
        TutorialDefinition.PlayerState override = definition.playerState;
        return new TutorialSession.ResolvedPlayerState(
                pick(override == null ? null : override.freezePlayer, defaults.freezePlayer),
                pick(override == null ? null : override.disableMovement, defaults.disableMovement),
                pick(override == null ? null : override.disableInteraction, defaults.disableInteraction),
                pick(override == null ? null : override.disableDamage, defaults.disableDamage),
                pick(override == null ? null : override.disableChat, defaults.disableChat),
                pick(override == null ? null : override.hideHud, defaults.hideHud),
                pick(override == null ? null : override.hideOtherPlayers, defaults.hideOtherPlayers),
                pick(override == null ? null : override.restoreLocationAfterTutorial,
                        defaults.restoreLocationAfterTutorial));
    }

    private static boolean pick(Boolean override, boolean fallback) {
        return override != null ? override : fallback;
    }

    // ----- Failsafe --------------------------------------------------------------

    /**
     * Every session gets a timeout, no exceptions. The tutorial's own failsafe
     * seconds apply when set; the module-level {@code maxDurationSeconds} caps
     * it whenever the module failsafe is enabled.
     */
    private void scheduleFailsafe(TutorialSession session) {
        TutorialModuleConfig.Failsafe moduleFailsafe = module.config().failsafe;
        TutorialDefinition.Failsafe defFailsafe = session.definition.failsafe;
        int seconds = defFailsafe != null && defFailsafe.enabled && defFailsafe.timeoutSeconds > 0
                ? defFailsafe.timeoutSeconds
                : moduleFailsafe.maxDurationSeconds;
        if (moduleFailsafe.enabled && moduleFailsafe.maxDurationSeconds > 0) {
            seconds = Math.min(seconds, moduleFailsafe.maxDurationSeconds);
        }
        seconds = Math.max(5, seconds);
        session.setFailsafeTask(core.scheduler().runLater(() -> {
            if (session.isActive() && sessions.get(session.playerId) == session) {
                finish(session, TutorialStopReason.TIMEOUT, "failsafe timeout");
            }
        }, seconds, TimeUnit.SECONDS));
    }

    // ----- Scene phase -------------------------------------------------------------

    private void beginScenePhase(TutorialSession session) {
        if (!session.isActive() || sessions.get(session.playerId) != session) {
            return;
        }
        session.setState(TutorialSessionState.PLAYING_SCENE);
        TutorialDefinition.Machinima machinima = session.definition.machinima;
        if (machinima == null || !machinima.enabled || machinima.sceneId == null
                || machinima.sceneId.isBlank()) {
            module.logger().debug("No scene for '" + session.definition.id + "'; completing directly.");
            complete(session);
            return;
        }
        TutorialSceneProvider provider = module.resolveSceneProvider();
        if (provider == null) {
            finish(session, TutorialStopReason.FAILED, "no scene provider available");
            return;
        }
        session.setSceneProvider(provider);
        TutorialSceneRequest request = buildSceneRequest(session, machinima);
        module.logger().debug("Playing " + request + " via '" + provider.id() + "'");
        provider.playScene(request).whenComplete((result, error) -> {
            if (error != null) {
                handleSceneResult(session, TutorialSceneResult.of(
                        org.hyzionstudios.mysticessentials.modules.tutorial.scene.TutorialSceneResultType.FAILED,
                        String.valueOf(error)));
            } else {
                handleSceneResult(session, result);
            }
        });
    }

    /**
     * Builds the scene request, resolving {@code placement = "relocate"} into a
     * concrete world target: an explicit {@code coords} anchor, or the player's
     * captured start location (falls back to fixed playback when neither is
     * available).
     */
    private TutorialSceneRequest buildSceneRequest(TutorialSession session,
            TutorialDefinition.Machinima machinima) {
        boolean relocate = "relocate".equalsIgnoreCase(machinima.placement);
        double x = 0;
        double y = 0;
        double z = 0;
        if (relocate) {
            TutorialDefinition.Machinima.Anchor anchor = machinima.anchor;
            if (anchor != null && "coords".equalsIgnoreCase(anchor.type)) {
                x = anchor.x;
                y = anchor.y;
                z = anchor.z;
            } else {
                PlayerStateSnapshot snapshot = session.snapshot();
                if (snapshot != null && snapshot.location != null) {
                    x = snapshot.location.getX();
                    y = snapshot.location.getY();
                    z = snapshot.location.getZ();
                } else {
                    module.logger().debug("Relocate requested for '" + session.definition.id
                            + "' but no player location was captured; playing at authored coords.");
                    relocate = false;
                }
            }
        }
        return new TutorialSceneRequest(session.player, session.definition.id,
                machinima.sceneId, machinima.pathId, machinima.waitForCompletion,
                machinima.timeoutSeconds, relocate, x, y, z);
    }

    private void handleSceneResult(TutorialSession session, TutorialSceneResult result) {
        if (!session.isActive() || sessions.get(session.playerId) != session) {
            return; // The session already ended (stop/skip/timeout raced the scene).
        }
        module.logger().debug("Scene result for '" + session.definition.id + "': " + result);
        switch (result.type) {
            case COMPLETED -> complete(session);
            case STOPPED -> {
                // Stop flows through finish() already; nothing to do here.
            }
            case TIMED_OUT -> finish(session, TutorialStopReason.TIMEOUT, "scene timeout");
            case FAILED, UNAVAILABLE -> finish(session, TutorialStopReason.FAILED,
                    "scene " + result);
        }
    }

    // ----- Completion / stop -------------------------------------------------------

    private void complete(TutorialSession session) {
        if (sessions.get(session.playerId) != session) {
            return;
        }
        finish(session, TutorialStopReason.COMPLETED, "");
    }

    public TutorialStopResult stop(UUID playerId, TutorialStopReason reason) {
        TutorialSession session = sessions.get(playerId);
        if (session == null) {
            return TutorialStopResult.NOT_IN_TUTORIAL;
        }
        finish(session, reason, "");
        return TutorialStopResult.STOPPED;
    }

    /** Ends every running session (module disable / server shutdown). */
    public void stopAll(TutorialStopReason reason) {
        for (TutorialSession session : List.copyOf(sessions.values())) {
            finish(session, reason, "");
        }
    }

    /**
     * The single exit path for every session. Removes the session, cancels
     * timers, stops the scene, restores the player, updates persisted data,
     * publishes the matching event, and logs. Safe to race: only the caller
     * that actually removes the session proceeds.
     */
    private void finish(TutorialSession session, TutorialStopReason reason, String detail) {
        if (!sessions.remove(session.playerId, session)) {
            return;
        }
        session.setState(reason.finalState());
        session.cancelTasks();

        TutorialSceneProvider provider = session.sceneProvider();
        if (provider != null) {
            try {
                provider.stopScene(session.playerId);
            } catch (Throwable t) {
                module.logger().error("stopScene failed for " + session.playerName + ": " + t);
            }
        }

        // On disconnect the entity is already gone, so no restore can run; the
        // recovery marker must survive so the next join repairs the player.
        boolean restoreDispatched = reason != TutorialStopReason.DISCONNECT && restore(session);

        updateDataOnFinish(session, reason, restoreDispatched);
        publishFinishEvent(session, reason, detail);
        logFinish(session, reason, detail);

        if (reason == TutorialStopReason.COMPLETED) {
            runCompletionActions(session);
        } else if ((reason == TutorialStopReason.FAILED || reason == TutorialStopReason.TIMEOUT)
                && module.config().failsafe.showFallbackPageOnError) {
            module.pageService().openPage(session.player, module.config().failsafe.fallbackPageId,
                    session.definition.id);
        }
    }

    private void updateDataOnFinish(TutorialSession session, TutorialStopReason reason,
            boolean restoreDispatched) {
        TutorialPlayerData data = module.storage().cached(session.playerId).orElse(null);
        if (data == null) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (data) {
            if (reason == TutorialStopReason.COMPLETED && session.definition.completion.markCompleted) {
                boolean completedBefore = data.hasCompleted(session.definition.id);
                if (!completedBefore || session.definition.replay.countReplayAsCompletion) {
                    data.record(session.definition.id).markCompleted(now);
                } else {
                    data.record(session.definition.id).lastCompletedAt = now;
                }
            }
            if (reason == TutorialStopReason.SKIPPED) {
                data.record(session.definition.id).timesSkipped++;
            }
            if (module.config().storage.saveCompletionHistory) {
                data.addHistory(session.definition.id, reason.historyAction(), now);
            }
            // Keep the recovery marker when the player state could not be
            // restored (disconnect / invalid entity): the next join repairs it.
            if (restoreDispatched) {
                data.activeTutorialId = null;
            }
        }
        module.storage().markDirty(session.playerId);
    }

    private void publishFinishEvent(TutorialSession session, TutorialStopReason reason, String detail) {
        var bus = core.getEventBus();
        switch (reason) {
            case COMPLETED -> bus.publish(new TutorialEvents.Complete(session.playerId,
                    session.playerName, session.definition.id,
                    session.definition.completion.markCompleted));
            case SKIPPED -> bus.publish(new TutorialEvents.Skip(session.playerId,
                    session.playerName, session.definition.id));
            case FAILED -> bus.publish(new TutorialEvents.Fail(session.playerId,
                    session.playerName, session.definition.id, detail));
            case TIMEOUT -> bus.publish(new TutorialEvents.Timeout(session.playerId,
                    session.playerName, session.definition.id));
            default -> bus.publish(new TutorialEvents.Cancel(session.playerId,
                    session.playerName, session.definition.id, reason));
        }
    }

    private void logFinish(TutorialSession session, TutorialStopReason reason, String detail) {
        switch (reason) {
            case COMPLETED -> module.logger().complete(session.playerName, session.definition.id,
                    session.definition.completion.markCompleted);
            case TIMEOUT -> module.logger().timeout(session.playerName, session.definition.id);
            case FAILED -> module.logger().error(session.playerName + " failed '"
                    + session.definition.id + "': " + detail);
            default -> module.logger().cancel(session.playerName, session.definition.id,
                    reason.name().toLowerCase(Locale.ROOT));
        }
    }

    private void runCompletionActions(TutorialSession session) {
        TutorialDefinition.Completion completion = session.definition.completion;
        if (completion.showPage && module.config().ui.enabled) {
            String pageId = completion.pageId == null || completion.pageId.isBlank()
                    ? module.config().ui.defaultCompletionPage
                    : completion.pageId;
            module.pageService().openPage(session.player, pageId, session.definition.id);
        }
        if (completion.runCommands && completion.commands != null) {
            for (String command : completion.commands) {
                if (command == null || command.isBlank()) {
                    continue;
                }
                String resolved = TutorialPlaceholders.apply(command, session.player,
                        session.definition.id, module.loader());
                core.platform().dispatchConsoleCommand(
                        resolved.startsWith("/") ? resolved.substring(1) : resolved);
            }
        }
    }

    // ----- Player state capture / apply / restore --------------------------------------

    /**
     * Captures the pre-tutorial state and applies the tutorial flags in one
     * world-thread task.
     *
     * @return {@code false} if the player entity was unavailable and nothing
     *         was dispatched.
     */
    private boolean captureAndApply(TutorialSession session) {
        PlayerStateSnapshot snapshot = new PlayerStateSnapshot(session.playerId);
        session.setSnapshot(snapshot);
        TutorialSession.ResolvedPlayerState state = session.playerState;
        return core.platform().runOnEntityThread(session.player, (store, ref, world) -> {
            snapshot.location = Conversions.capture(session.player);

            if (state.freezePlayer() || state.disableMovement()) {
                MovementManager movement = store.getComponent(ref, MovementManager.getComponentType());
                if (movement != null) {
                    var settings = movement.getSettings();
                    snapshot.baseSpeed = settings.baseSpeed;
                    snapshot.acceleration = settings.acceleration;
                    snapshot.jumpForce = settings.jumpForce;
                    snapshot.swimJumpForce = settings.swimJumpForce;
                    snapshot.climbSpeed = settings.climbSpeed;
                    snapshot.climbSpeedLateral = settings.climbSpeedLateral;
                    snapshot.horizontalFlySpeed = settings.horizontalFlySpeed;
                    snapshot.verticalFlySpeed = settings.verticalFlySpeed;
                    snapshot.canFly = settings.canFly;
                    snapshot.movementCaptured = true;

                    settings.baseSpeed = 0f;
                    settings.acceleration = 0f;
                    settings.jumpForce = 0f;
                    settings.swimJumpForce = 0f;
                    settings.climbSpeed = 0f;
                    settings.climbSpeedLateral = 0f;
                    settings.horizontalFlySpeed = 0f;
                    settings.verticalFlySpeed = 0f;
                    settings.canFly = false;
                    movement.update(session.player.getPacketHandler());
                }
            }

            if (state.disableDamage()) {
                snapshot.wasInvulnerable =
                        store.getComponent(ref, Invulnerable.getComponentType()) != null;
                if (!snapshot.wasInvulnerable) {
                    store.ensureComponent(ref, Invulnerable.getComponentType());
                }
                snapshot.invulnerableApplied = true;
            }

            if (state.hideHud()) {
                Player entity = store.getComponent(ref, Player.getComponentType());
                if (entity != null) {
                    var hud = entity.getHudManager();
                    List<String> visible = new ArrayList<>();
                    for (HudComponent component : hud.getVisibleHudComponents()) {
                        visible.add(component.name());
                    }
                    snapshot.rememberHud(visible);
                    hud.setVisibleHudComponents(session.player, Set.of());
                }
            }

            // disableInteraction / hideOtherPlayers: no per-player interaction
            // block or entity-visibility API exists in the verified 0.5.6
            // surface. The frozen movement + camera scene covers the practical
            // cases. TODO: wire real APIs when a later server version adds them.
            if (state.disableInteraction() || state.hideOtherPlayers()) {
                module.logger().debug("disableInteraction/hideOtherPlayers requested for '"
                        + session.definition.id + "' — not supported by Hytale 0.5.6, skipped.");
            }
        });
    }

    /**
     * Restores everything the snapshot captured, exactly once.
     *
     * @return {@code true} if the restore was dispatched to the world thread.
     */
    private boolean restore(TutorialSession session) {
        PlayerStateSnapshot snapshot = session.snapshot();
        if (snapshot == null || !snapshot.beginRestore()) {
            return true; // Nothing was applied, or someone already restored.
        }
        TutorialSession.ResolvedPlayerState state = session.playerState;
        boolean dispatched = core.platform().runOnEntityThread(session.player, (store, ref, world) -> {
            if (snapshot.movementCaptured) {
                MovementManager movement = store.getComponent(ref, MovementManager.getComponentType());
                if (movement != null) {
                    var settings = movement.getSettings();
                    settings.baseSpeed = snapshot.baseSpeed;
                    settings.acceleration = snapshot.acceleration;
                    settings.jumpForce = snapshot.jumpForce;
                    settings.swimJumpForce = snapshot.swimJumpForce;
                    settings.climbSpeed = snapshot.climbSpeed;
                    settings.climbSpeedLateral = snapshot.climbSpeedLateral;
                    settings.horizontalFlySpeed = snapshot.horizontalFlySpeed;
                    settings.verticalFlySpeed = snapshot.verticalFlySpeed;
                    settings.canFly = snapshot.canFly;
                    movement.update(session.player.getPacketHandler());
                }
            }

            if (snapshot.invulnerableApplied && !snapshot.wasInvulnerable) {
                try {
                    store.tryRemoveComponent(ref, Invulnerable.getComponentType());
                } catch (Throwable t) {
                    module.logger().error("Invulnerable removal failed for "
                            + session.playerName + ": " + t);
                }
            }

            if (snapshot.visibleHudComponents != null) {
                Player entity = store.getComponent(ref, Player.getComponentType());
                if (entity != null) {
                    try {
                        Set<HudComponent> components = EnumSet.noneOf(HudComponent.class);
                        for (String name : snapshot.visibleHudComponents) {
                            components.add(HudComponent.valueOf(name));
                        }
                        entity.getHudManager().setVisibleHudComponents(session.player, components);
                    } catch (Throwable t) {
                        // Unknown component name after a version bump: fall back
                        // to the server-default HUD rather than leaving it hidden.
                        entity.getHudManager().resetHud(session.player);
                    }
                }
            }

            // Always release the camera — cheap, idempotent, and covers a scene
            // that locked it without ever reporting back.
            try {
                CameraManager camera = store.getComponent(ref, CameraManager.getComponentType());
                if (camera != null) {
                    camera.resetCamera(session.player);
                }
            } catch (Throwable t) {
                module.logger().error("Camera reset failed for " + session.playerName + ": " + t);
            }
        });
        if (dispatched && state.restoreLocationAfterTutorial() && snapshot.location != null) {
            core.platform().teleportEntity(session.player, snapshot.location);
        }
        if (!dispatched) {
            module.logger().error("Could not restore state for " + session.playerName
                    + " (entity unavailable); recovery will run on next join.");
        }
        return dispatched;
    }

    /**
     * Next-join repair for unclean exits (crash or disconnect mid-tutorial):
     * removes a lingering Invulnerable component, resets HUD and camera, and
     * clears the recovery marker. Movement settings are rebuilt from defaults
     * by the server on join, so they need no repair.
     */
    public void recoverOnJoin(PlayerRef player, TutorialPlayerData data) {
        String tutorialId;
        synchronized (data) {
            tutorialId = data.activeTutorialId;
            if (tutorialId == null) {
                return;
            }
            data.activeTutorialId = null;
        }
        module.storage().markDirty(player.getUuid());
        module.logger().error("Repairing unclean tutorial exit ('" + tutorialId + "') for "
                + player.getUsername());
        core.platform().runOnEntityThread(player, (store, ref, world) -> {
            try {
                store.tryRemoveComponent(ref, Invulnerable.getComponentType());
            } catch (Throwable ignored) {
                // Component was not present; nothing to repair.
            }
            Player entity = store.getComponent(ref, Player.getComponentType());
            if (entity != null) {
                try {
                    entity.getHudManager().resetHud(player);
                } catch (Throwable ignored) {
                }
            }
            try {
                CameraManager camera = store.getComponent(ref, CameraManager.getComponentType());
                if (camera != null) {
                    camera.resetCamera(player);
                }
            } catch (Throwable ignored) {
            }
        });
    }
}
