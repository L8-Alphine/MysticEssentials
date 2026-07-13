package org.hyzionstudios.mysticessentials.modules.tutorial;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.hyzionstudios.mysticessentials.modules.tutorial.config.TutorialDefinition;
import org.hyzionstudios.mysticessentials.modules.tutorial.session.TutorialPlayOptions;
import org.hyzionstudios.mysticessentials.modules.tutorial.session.TutorialSession;
import org.hyzionstudios.mysticessentials.modules.tutorial.session.TutorialStopReason;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Default {@link TutorialService}: a thin coordinator over the session
 * manager, config loader, storage, and page service owned by
 * {@link TutorialModule}.
 */
public final class TutorialServiceImpl implements TutorialService {

    private final TutorialModule module;

    public TutorialServiceImpl(TutorialModule module) {
        this.module = module;
    }

    @Override
    public CompletableFuture<TutorialStartResult> playTutorial(PlayerRef player, String tutorialId,
            TutorialPlayOptions options) {
        if (player == null) {
            return CompletableFuture.completedFuture(TutorialStartResult.PLAYER_OFFLINE);
        }
        return module.sessions().start(player, tutorialId,
                options == null ? TutorialPlayOptions.defaults() : options);
    }

    @Override
    public CompletableFuture<TutorialStopResult> stopTutorial(PlayerRef player, TutorialStopReason reason) {
        if (player == null) {
            return CompletableFuture.completedFuture(TutorialStopResult.NOT_IN_TUTORIAL);
        }
        return CompletableFuture.completedFuture(module.sessions().stop(player.getUuid(),
                reason == null ? TutorialStopReason.CANCELLED : reason));
    }

    @Override
    public boolean isInTutorial(PlayerRef player) {
        return player != null && module.sessions().isInTutorial(player.getUuid());
    }

    @Override
    public Optional<TutorialSession> getActiveSession(PlayerRef player) {
        return player == null ? Optional.empty() : module.sessions().session(player.getUuid());
    }

    @Override
    public boolean hasCompleted(PlayerRef player, String tutorialId) {
        if (player == null || tutorialId == null) {
            return false;
        }
        return module.storage().cached(player.getUuid())
                .map(data -> data.hasCompleted(tutorialId))
                .orElse(false);
    }

    @Override
    public void markCompleted(PlayerRef player, String tutorialId) {
        if (player == null || tutorialId == null || tutorialId.isBlank()) {
            return;
        }
        module.storage().load(player.getUuid(), player.getUsername()).thenAccept(data -> {
            synchronized (data) {
                data.record(tutorialId).markCompleted(System.currentTimeMillis());
                data.addHistory(tutorialId, "complete", System.currentTimeMillis());
            }
            module.storage().markDirty(player.getUuid());
        });
    }

    @Override
    public void resetCompletion(PlayerRef player, String tutorialId) {
        if (player == null || tutorialId == null || tutorialId.isBlank()) {
            return;
        }
        module.storage().load(player.getUuid(), player.getUsername()).thenAccept(data -> {
            synchronized (data) {
                data.tutorials.remove(tutorialId.toLowerCase(java.util.Locale.ROOT));
                data.addHistory(tutorialId, "reset", System.currentTimeMillis());
            }
            module.storage().markDirty(player.getUuid());
        });
    }

    @Override
    public Collection<TutorialDefinition> getTutorials() {
        return module.loader().tutorials();
    }

    @Override
    public Optional<TutorialDefinition> getTutorial(String tutorialId) {
        return module.loader().tutorial(tutorialId);
    }

    @Override
    public CompletableFuture<Void> openPage(PlayerRef player, String pageId) {
        return module.pageService().openPage(player, pageId, "").thenApply(opened -> null);
    }
}
