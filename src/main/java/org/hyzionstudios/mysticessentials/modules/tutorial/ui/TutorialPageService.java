package org.hyzionstudios.mysticessentials.modules.tutorial.ui;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.tutorial.TutorialEvents;
import org.hyzionstudios.mysticessentials.modules.tutorial.TutorialModule;
import org.hyzionstudios.mysticessentials.modules.tutorial.config.TutorialPageDefinition;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Opens tutorial UI pages for players (completion pages, fallback pages, page
 * buttons, and {@code /tutorial page}). Failures degrade to a chat message —
 * a missing or broken page never blocks the tutorial flow around it.
 */
public final class TutorialPageService {

    private final MysticCore core;
    private final TutorialModule module;

    public TutorialPageService(MysticCore core, TutorialModule module) {
        this.core = core;
        this.module = module;
    }

    /**
     * Opens {@code pageId} for the player. {@code tutorialContext} (may be
     * blank) is carried into placeholders and events.
     *
     * @return a future completing {@code true} if the open was dispatched.
     */
    public CompletableFuture<Boolean> openPage(PlayerRef player, String pageId, String tutorialContext) {
        if (!module.config().ui.enabled) {
            module.logger().debug("UI disabled; not opening page '" + pageId + "'");
            return CompletableFuture.completedFuture(false);
        }
        if (player == null) {
            return CompletableFuture.completedFuture(false);
        }
        TutorialPageDefinition page = module.loader().page(pageId).orElse(null);
        if (page == null) {
            module.logger().error("Unknown tutorial page '" + pageId + "'");
            core.getMessageService().sendKey(player, "tutorial-page-unknown",
                    Map.of("page", pageId == null ? "" : pageId));
            return CompletableFuture.completedFuture(false);
        }
        boolean dispatched = core.platform().openPage(player,
                new TutorialPageRenderer(core, module, player, page, tutorialContext));
        if (dispatched) {
            core.getEventBus().publish(new TutorialEvents.PageOpen(player.getUuid(),
                    player.getUsername(), tutorialContext == null ? "" : tutorialContext, page.id));
        }
        return CompletableFuture.completedFuture(dispatched);
    }
}
