package org.hyzionstudios.mysticessentials.modules.tutorial.scene;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.tutorial.config.TutorialModuleConfig.CameraPlayback;

import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.PositionType;
import com.hypixel.hytale.protocol.RotationType;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.server.core.entity.entities.player.CameraManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Plays a cinematic by <b>driving the player's camera from the server</b>: it
 * samples the scene's keyframe path over time and pushes {@code SetServerCamera}
 * (the packet behind the builtin {@code /camera}) at a fixed rate, letting the
 * client lerp between updates.
 *
 * <p>This is the only mechanism that actually plays these scenes on Hytale
 * 0.5.6: the client has no receiver for {@code UpdateMachinimaScene} (machinima
 * playback is editor-local), but it does process {@code SetServerCamera}. Camera
 * settings follow the verified builtin recipe — {@code ClientCameraView.Custom},
 * locked, {@code PositionType.Custom} + absolute {@code Position},
 * {@code RotationType.Custom} + {@code Direction} in radians.</p>
 *
 * <p>Every path always ends by resetting the camera
 * ({@code CameraManager.resetCamera}) and sending an unlock packet, and
 * {@link #stopScene} is idempotent, so the tutorial failsafe can never leave a
 * player stuck in a locked camera.</p>
 */
public final class CameraSceneProvider implements TutorialSceneProvider {

    private final MysticCore core;
    private final MachinimaSceneAssets sceneAssets;
    private final Supplier<CameraPlayback> config;

    private record Running(CompletableFuture<TutorialSceneResult> completion,
            ScheduledFuture<?> tickTask, PlayerRef player) {
    }

    private final Map<UUID, Running> running = new ConcurrentHashMap<>();

    public CameraSceneProvider(MysticCore core, MachinimaSceneAssets sceneAssets,
            Supplier<CameraPlayback> config) {
        this.core = core;
        this.sceneAssets = sceneAssets;
        this.config = config;
    }

    @Override
    public String id() {
        return "camera";
    }

    @Override
    public boolean isAvailable() {
        try {
            return SetServerCamera.class != null && ClientCameraView.Custom != null;
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
        MachinimaSceneDocument document = sceneAssets.loadDocument(request.sceneId);
        if (document == null) {
            return CompletableFuture.completedFuture(TutorialSceneResult.of(
                    TutorialSceneResultType.FAILED, "scene '" + request.sceneId + "' not found or invalid"));
        }
        if (request.relocate) {
            document = document.relocatedTo(request.targetX, request.targetY, request.targetZ);
        }
        List<MachinimaSceneDocument.Keyframe> keyframes = document.cameraKeyframes();
        if (keyframes.isEmpty()) {
            return CompletableFuture.completedFuture(TutorialSceneResult.of(
                    TutorialSceneResultType.FAILED, "scene has no camera keyframes"));
        }

        CameraPlayback cfg = config.get();
        CameraTimeline timeline = new CameraTimeline(keyframes, cfg);
        int hz = Math.max(1, Math.min(60, cfg.updateHz));
        long periodMillis = Math.max(1, 1000L / hz);
        double totalSeconds = timeline.durationSeconds() + Math.max(0, cfg.holdEndSeconds);

        CompletableFuture<TutorialSceneResult> completion = new CompletableFuture<>();
        long startNanos = System.nanoTime();

        // Push the first frame immediately so the camera snaps into the shot
        // before the player notices, then drive the rest on the tick loop.
        sendSample(request.player, timeline.sample(0), cfg);

        ScheduledFuture<?> tickTask = core.scheduler().runRepeating(() -> {
            Running entry = running.get(request.playerId);
            if (entry == null) {
                return; // Stopped between ticks.
            }
            double elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            if (elapsed >= totalSeconds) {
                finish(request.playerId, TutorialSceneResultType.COMPLETED, "path complete");
                return;
            }
            try {
                sendSample(request.player, timeline.sample(elapsed), cfg);
            } catch (Throwable t) {
                core.log(Level.WARNING, "[tutorial] camera path tick failed for "
                        + request.player.getUsername() + ": " + t);
            }
        }, periodMillis, periodMillis, TimeUnit.MILLISECONDS);

        running.put(request.playerId, new Running(completion, tickTask, request.player));

        if (!request.waitForCompletion) {
            // Fire-and-forget (e.g. the test command): the loop still runs and
            // self-terminates + resets the camera; report the start as success.
            return CompletableFuture.completedFuture(
                    TutorialSceneResult.of(TutorialSceneResultType.COMPLETED, "playing"));
        }
        return completion;
    }

    @Override
    public CompletableFuture<Void> stopScene(UUID playerId) {
        finish(playerId, TutorialSceneResultType.STOPPED, "stopped");
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean isSceneRunning(UUID playerId) {
        return running.containsKey(playerId);
    }

    // ----- Internals -------------------------------------------------------------

    /** Ends playback exactly once: cancels the loop, resets the camera, completes the future. */
    private void finish(UUID playerId, TutorialSceneResultType type, String detail) {
        Running entry = running.remove(playerId);
        if (entry == null) {
            return;
        }
        if (entry.tickTask() != null) {
            entry.tickTask().cancel(false);
        }
        resetCamera(entry.player());
        if (!entry.completion().isDone()) {
            entry.completion().complete(TutorialSceneResult.of(type, detail));
        }
    }

    /** Builds and sends a locked custom-camera packet for one sampled pose. */
    private void sendSample(PlayerRef player, CameraTimeline.Sample sample, CameraPlayback cfg) {
        ServerCameraSettings settings = new ServerCameraSettings();
        settings.positionLerpSpeed = cfg.positionLerpSpeed;
        settings.rotationLerpSpeed = cfg.rotationLerpSpeed;
        settings.distance = 0f;
        settings.isFirstPerson = false;
        settings.displayCursor = false;
        settings.displayReticle = false;
        settings.allowPitchControls = false;
        settings.sendMouseMotion = false;
        settings.positionType = PositionType.Custom;
        settings.position = new Position(sample.x(), sample.y(), sample.z());
        settings.rotationType = RotationType.Custom;
        // Direction(yaw, pitch, roll) — radians (builtin top-down demo uses pitch = -PI/2).
        settings.rotation = new Direction(sample.yaw(), sample.pitch(), 0f);
        try {
            player.getPacketHandler().writeNoCache(
                    new SetServerCamera(ClientCameraView.Custom, true, settings));
        } catch (Throwable t) {
            core.log(Level.WARNING, "[tutorial] SetServerCamera write failed for "
                    + player.getUsername() + ": " + t);
        }
    }

    /** Releases the camera back to the player (component reset + unlock packet). */
    private void resetCamera(PlayerRef player) {
        core.platform().runOnEntityThread(player, (store, ref, world) -> {
            try {
                player.getPacketHandler().writeNoCache(
                        new SetServerCamera(ClientCameraView.FirstPerson, false, null));
            } catch (Throwable t) {
                core.log(Level.WARNING, "[tutorial] camera unlock packet failed for "
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
        });
    }
}
