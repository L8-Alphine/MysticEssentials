package org.hyzionstudios.mysticessentials.modules.tutorial.scene;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Fallback provider used when machinima playback is unavailable or disabled:
 * every scene "plays" instantly, so tutorials degrade to their page/command
 * flow instead of breaking.
 */
public final class NoOpSceneProvider implements TutorialSceneProvider {

    @Override
    public String id() {
        return "noop";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public CompletableFuture<TutorialSceneResult> playScene(TutorialSceneRequest request) {
        return CompletableFuture.completedFuture(
                TutorialSceneResult.of(TutorialSceneResultType.COMPLETED, "noop"));
    }

    @Override
    public CompletableFuture<Void> stopScene(UUID playerId) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean isSceneRunning(UUID playerId) {
        return false;
    }
}
