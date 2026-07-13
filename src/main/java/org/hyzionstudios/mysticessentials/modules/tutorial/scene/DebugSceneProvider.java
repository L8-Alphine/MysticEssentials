package org.hyzionstudios.mysticessentials.modules.tutorial.scene;

import java.util.UUID;
import java.util.logging.Level;

import java.util.concurrent.CompletableFuture;

import org.hyzionstudios.mysticessentials.core.MysticCore;

/**
 * Scene provider for testing tutorial flow without a client-side scene: logs
 * the request and completes immediately. Selected with
 * {@code sceneProvider.type = "debug"}.
 */
public final class DebugSceneProvider implements TutorialSceneProvider {

    private final MysticCore core;

    public DebugSceneProvider(MysticCore core) {
        this.core = core;
    }

    @Override
    public String id() {
        return "debug";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public CompletableFuture<TutorialSceneResult> playScene(TutorialSceneRequest request) {
        core.log(Level.INFO, "[tutorial] DebugSceneProvider would play " + request);
        return CompletableFuture.completedFuture(
                TutorialSceneResult.of(TutorialSceneResultType.COMPLETED, "debug"));
    }

    @Override
    public CompletableFuture<Void> stopScene(UUID playerId) {
        core.log(Level.INFO, "[tutorial] DebugSceneProvider stopScene for " + playerId);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean isSceneRunning(UUID playerId) {
        return false;
    }
}
