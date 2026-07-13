package org.hyzionstudios.mysticessentials.modules.tutorial.scene;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;

import com.hypixel.hytale.protocol.packets.machinima.SceneUpdateType;
import com.hypixel.hytale.protocol.packets.machinima.UpdateMachinimaScene;
import com.hypixel.hytale.server.core.entity.entities.player.CameraManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Plays cinematic scenes through the <b>verified</b> Hytale 0.5.6 machinima
 * protocol: {@code UpdateMachinimaScene(player, sceneName, frame, updateType,
 * scene)} sent to the client via {@code PlayerRef.getPacketHandler()}, with
 * {@code SceneUpdateType.Play} / {@code Stop}. Camera recovery uses the
 * verified {@code CameraManager.resetCamera(PlayerRef)} component call.
 *
 * <p><b>Known limits of the 0.5.6 surface</b> (checked against the decompiled
 * server jar): the server has no machinima scene registry, no scene-progress
 * event, and no completion callback — the packet layer is fire-and-forget and
 * scene/path assets live client-side. Consequences:</p>
 * <ul>
 *   <li>Completion is time-based: a "playing" scene is considered complete
 *       after {@code request.timeoutSeconds}. TODO: replace with a real
 *       completion signal if a later server version exposes the ToServer
 *       {@code UpdateMachinimaScene} messages to plugins.</li>
 *   <li>Whether the client honours a server-initiated {@code Play} for a named
 *       scene without inline scene bytes could not be verified without a game
 *       client. TODO: if clients require serialized scene data, load it and
 *       set the packet's {@code scene} field here. The session failsafe keeps
 *       players safe either way.</li>
 * </ul>
 */
public final class MachinimaSceneProvider implements TutorialSceneProvider {

    private final MysticCore core;
    private final MachinimaSceneAssets sceneAssets;

    private record Running(CompletableFuture<TutorialSceneResult> future, ScheduledFuture<?> completionTask) {
    }

    private final Map<UUID, Running> running = new ConcurrentHashMap<>();

    public MachinimaSceneProvider(MysticCore core) {
        this(core, new MachinimaSceneAssets(core, "tutorial"));
    }

    public MachinimaSceneProvider(MysticCore core, MachinimaSceneAssets sceneAssets) {
        this.core = core;
        this.sceneAssets = sceneAssets;
    }

    /** Exposes the scene-asset loader (for reload cache clears / operator path display). */
    public MachinimaSceneAssets sceneAssets() {
        return sceneAssets;
    }

    @Override
    public String id() {
        return "machinima";
    }

    /**
     * The packet API is part of the server jar, so the provider itself is
     * always constructible; verify the classes are actually present at runtime
     * in case a future server version removes them.
     */
    @Override
    public boolean isAvailable() {
        try {
            return UpdateMachinimaScene.class != null && SceneUpdateType.Play != null;
        } catch (Throwable missing) {
            return false;
        }
    }

    @Override
    public CompletableFuture<TutorialSceneResult> playScene(TutorialSceneRequest request) {
        if (request.sceneId.isBlank()) {
            return CompletableFuture.completedFuture(
                    TutorialSceneResult.of(TutorialSceneResultType.FAILED, "blank sceneId"));
        }
        CompletableFuture<TutorialSceneResult> future = new CompletableFuture<>();
        // Ship the scene bytes inline when the mod (or operator) provides the asset,
        // so playback does not depend on the client already having the named scene.
        byte[] sceneData = sceneAssets.load(request.sceneId);
        // Relocate the scene to the requested world point when asked (see
        // MachinimaSceneDocument). A parse failure is non-fatal: fall back to the
        // authored coordinates rather than dropping the tutorial.
        if (request.relocate && sceneData != null) {
            try {
                sceneData = MachinimaSceneDocument.parse(sceneData)
                        .relocatedTo(request.targetX, request.targetY, request.targetZ)
                        .toBytes();
            } catch (IllegalArgumentException invalid) {
                core.log(Level.WARNING, "[tutorial] Could not relocate scene '" + request.sceneId
                        + "' (" + invalid.getMessage() + "); playing at authored coordinates.");
            }
        }
        boolean sent = sendSceneUpdate(request.player, request.sceneId, SceneUpdateType.Play, sceneData);
        if (!sent) {
            return CompletableFuture.completedFuture(
                    TutorialSceneResult.of(TutorialSceneResultType.FAILED, "packet send failed"));
        }
        if (!request.waitForCompletion) {
            return CompletableFuture.completedFuture(
                    TutorialSceneResult.of(TutorialSceneResultType.COMPLETED, "fire-and-forget"));
        }
        // No completion signal exists in 0.5.6 (see class javadoc); treat the
        // configured scene time budget as the scene duration.
        ScheduledFuture<?> completionTask = core.scheduler().runLater(() -> {
            Running entry = running.remove(request.playerId);
            if (entry != null && !entry.future().isDone()) {
                entry.future().complete(
                        TutorialSceneResult.of(TutorialSceneResultType.COMPLETED, "time budget elapsed"));
            }
        }, request.timeoutSeconds, TimeUnit.SECONDS);
        running.put(request.playerId, new Running(future, completionTask));
        return future;
    }

    @Override
    public CompletableFuture<Void> stopScene(UUID playerId) {
        Running entry = running.remove(playerId);
        if (entry != null) {
            entry.completionTask().cancel(false);
            if (!entry.future().isDone()) {
                entry.future().complete(TutorialSceneResult.of(TutorialSceneResultType.STOPPED));
            }
        }
        PlayerRef player = core.platform().findPlayer(playerId).orElse(null);
        if (player == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        boolean dispatched = core.platform().runOnEntityThread(player, (store, ref, world) -> {
            try {
                player.getPacketHandler().writeNoCache(
                        new UpdateMachinimaScene(null, "", 0f, SceneUpdateType.Stop, null));
            } catch (Throwable t) {
                core.log(Level.WARNING, "[tutorial] machinima Stop packet failed for "
                        + player.getUsername() + ": " + t);
            }
            try {
                CameraManager camera = store.getComponent(ref, CameraManager.getComponentType());
                if (camera != null) {
                    camera.resetCamera(player);
                }
            } catch (Throwable t) {
                core.log(Level.WARNING, "[tutorial] camera reset failed for "
                        + player.getUsername() + ": " + t);
            }
            done.complete(null);
        });
        if (!dispatched) {
            done.complete(null);
        }
        return done;
    }

    @Override
    public boolean isSceneRunning(UUID playerId) {
        return running.containsKey(playerId);
    }

    private boolean sendSceneUpdate(PlayerRef player, String sceneName, SceneUpdateType type, byte[] sceneData) {
        CompletableFuture<Boolean> sent = new CompletableFuture<>();
        boolean dispatched = core.platform().runOnEntityThread(player, (store, ref, world) -> {
            try {
                // 'player' names the machinima actor (nullable in the protocol);
                // the viewing player is addressed by the connection itself. 'sceneData'
                // is the serialized scene (null = rely on a client-side named scene).
                player.getPacketHandler().writeNoCache(
                        new UpdateMachinimaScene(null, sceneName, 0f, type, sceneData));
                sent.complete(true);
            } catch (Throwable t) {
                core.log(Level.WARNING, "[tutorial] machinima " + type + " packet failed for "
                        + player.getUsername() + ": " + t);
                sent.complete(false);
            }
        });
        if (!dispatched) {
            return false;
        }
        try {
            return sent.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }
}
