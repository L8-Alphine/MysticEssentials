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
 * Public tutorial API for other MysticEssentials modules and addons. The
 * {@code TutorialModule} implements this interface, so it resolves through the
 * standard module system:
 *
 * <pre>{@code
 * api.getModuleManager().getModule("tutorial")
 *         .filter(TutorialService.class::isInstance)
 *         .map(TutorialService.class::cast)
 *         .ifPresent(tutorials -> tutorials.playTutorial(player, "first_join",
 *                 TutorialPlayOptions.defaults()));
 * }</pre>
 */
public interface TutorialService {

    /** Starts a tutorial for the player (validation, freeze, scene, completion flow). */
    CompletableFuture<TutorialStartResult> playTutorial(PlayerRef player, String tutorialId,
            TutorialPlayOptions options);

    /** Stops the player's running tutorial and restores their state. */
    CompletableFuture<TutorialStopResult> stopTutorial(PlayerRef player, TutorialStopReason reason);

    boolean isInTutorial(PlayerRef player);

    Optional<TutorialSession> getActiveSession(PlayerRef player);

    /** @return whether the (online) player has completed the tutorial. */
    boolean hasCompleted(PlayerRef player, String tutorialId);

    /** Marks the tutorial completed without playing it (admin/API use). */
    void markCompleted(PlayerRef player, String tutorialId);

    /** Clears the player's completion record so the tutorial can run again. */
    void resetCompletion(PlayerRef player, String tutorialId);

    Collection<TutorialDefinition> getTutorials();

    Optional<TutorialDefinition> getTutorial(String tutorialId);

    /** Opens a tutorial UI page outside of any tutorial flow. */
    CompletableFuture<Void> openPage(PlayerRef player, String pageId);
}
