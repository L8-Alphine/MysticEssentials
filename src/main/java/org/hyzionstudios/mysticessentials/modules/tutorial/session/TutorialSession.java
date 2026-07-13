package org.hyzionstudios.mysticessentials.modules.tutorial.session;

import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import org.hyzionstudios.mysticessentials.modules.tutorial.config.TutorialDefinition;
import org.hyzionstudios.mysticessentials.modules.tutorial.player.PlayerStateSnapshot;
import org.hyzionstudios.mysticessentials.modules.tutorial.scene.TutorialSceneProvider;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * One player's running tutorial. Mutable state is guarded by the session
 * manager (state transitions happen through it, never directly).
 */
public final class TutorialSession {

    public final UUID playerId;
    public final String playerName;
    public final PlayerRef player;
    public final TutorialDefinition definition;
    public final TutorialPlayOptions options;
    public final long startedAt = System.currentTimeMillis();

    /** Effective (definition merged with module defaults) player-state flags. */
    public final ResolvedPlayerState playerState;

    private volatile TutorialSessionState state = TutorialSessionState.STARTING;
    private volatile PlayerStateSnapshot snapshot;
    private volatile TutorialSceneProvider sceneProvider;
    private volatile ScheduledFuture<?> failsafeTask;
    private volatile ScheduledFuture<?> startTask;

    public TutorialSession(PlayerRef player, TutorialDefinition definition,
            TutorialPlayOptions options, ResolvedPlayerState playerState) {
        this.playerId = player.getUuid();
        this.playerName = player.getUsername();
        this.player = player;
        this.definition = definition;
        this.options = options;
        this.playerState = playerState;
    }

    public TutorialSessionState state() {
        return state;
    }

    public void setState(TutorialSessionState state) {
        this.state = state;
    }

    public boolean isActive() {
        return state.isActive();
    }

    public PlayerStateSnapshot snapshot() {
        return snapshot;
    }

    public void setSnapshot(PlayerStateSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public TutorialSceneProvider sceneProvider() {
        return sceneProvider;
    }

    public void setSceneProvider(TutorialSceneProvider sceneProvider) {
        this.sceneProvider = sceneProvider;
    }

    public void setFailsafeTask(ScheduledFuture<?> task) {
        this.failsafeTask = task;
    }

    public void setStartTask(ScheduledFuture<?> task) {
        this.startTask = task;
    }

    /** Cancels the pending timers (start delay + failsafe). */
    public void cancelTasks() {
        ScheduledFuture<?> failsafe = this.failsafeTask;
        if (failsafe != null) {
            failsafe.cancel(false);
        }
        ScheduledFuture<?> start = this.startTask;
        if (start != null) {
            start.cancel(false);
        }
    }

    /** Player-state flags after merging the tutorial's overrides onto the module defaults. */
    public record ResolvedPlayerState(
            boolean freezePlayer,
            boolean disableMovement,
            boolean disableInteraction,
            boolean disableDamage,
            boolean disableChat,
            boolean hideHud,
            boolean hideOtherPlayers,
            boolean restoreLocationAfterTutorial) {
    }
}
