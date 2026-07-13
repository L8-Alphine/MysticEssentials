package org.hyzionstudios.mysticessentials.modules.tutorial.scene;

import java.util.UUID;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/** Everything a {@link TutorialSceneProvider} needs to play one scene. */
public final class TutorialSceneRequest {

    public final UUID playerId;
    public final PlayerRef player;
    public final String tutorialId;
    public final String sceneId;
    public final String pathId;
    public final boolean waitForCompletion;
    public final int timeoutSeconds;

    /**
     * When {@code true}, the provider translates the scene so its origin sits at
     * {@code (targetX, targetY, targetZ)} before playing (see
     * {@link MachinimaSceneDocument#relocatedTo}); when {@code false} the scene
     * plays at its authored world coordinates.
     */
    public final boolean relocate;
    public final double targetX;
    public final double targetY;
    public final double targetZ;

    public TutorialSceneRequest(PlayerRef player, String tutorialId, String sceneId,
            String pathId, boolean waitForCompletion, int timeoutSeconds) {
        this(player, tutorialId, sceneId, pathId, waitForCompletion, timeoutSeconds,
                false, 0, 0, 0);
    }

    public TutorialSceneRequest(PlayerRef player, String tutorialId, String sceneId,
            String pathId, boolean waitForCompletion, int timeoutSeconds,
            boolean relocate, double targetX, double targetY, double targetZ) {
        this.playerId = player.getUuid();
        this.player = player;
        this.tutorialId = tutorialId;
        this.sceneId = sceneId == null ? "" : sceneId;
        this.pathId = pathId == null ? "" : pathId;
        this.waitForCompletion = waitForCompletion;
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.relocate = relocate;
        this.targetX = targetX;
        this.targetY = targetY;
        this.targetZ = targetZ;
    }

    @Override
    public String toString() {
        return "scene='" + sceneId + "' path='" + pathId + "' tutorial='" + tutorialId
                + "' player=" + playerId + " wait=" + waitForCompletion
                + " timeout=" + timeoutSeconds + "s"
                + (relocate ? " relocate=(" + targetX + "," + targetY + "," + targetZ + ")" : "");
    }
}
