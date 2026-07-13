package org.hyzionstudios.mysticessentials.modules.tutorial.scene;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Adapter over whatever plays a cinematic scene for a player. Implementations
 * must never leave a player camera-locked: {@link #stopScene(UUID)} must always
 * be safe to call (even redundantly), because the session failsafe calls it on
 * every abnormal exit.
 */
public interface TutorialSceneProvider {

    /** Stable provider id used in config and logs (e.g. {@code "machinima"}). */
    String id();

    /** @return {@code true} if this provider can actually play scenes right now. */
    boolean isAvailable();

    /**
     * Starts the requested scene. The returned future completes when the scene
     * ends (or immediately for providers that do not track progress) and must
     * never complete exceptionally — failures are reported through
     * {@link TutorialSceneResult}.
     */
    CompletableFuture<TutorialSceneResult> playScene(TutorialSceneRequest request);

    /** Stops any scene running for the player; must be idempotent. */
    CompletableFuture<Void> stopScene(UUID playerId);

    boolean isSceneRunning(UUID playerId);
}
