package org.hyzionstudios.mysticessentials.modules.tutorial.ui;

import java.util.Map;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.tutorial.TutorialModule;
import org.hyzionstudios.mysticessentials.modules.tutorial.config.TutorialButtonDefinition;
import org.hyzionstudios.mysticessentials.modules.tutorial.session.TutorialPlayOptions;
import org.hyzionstudios.mysticessentials.modules.tutorial.util.TutorialPlaceholders;
import org.hyzionstudios.mysticessentials.api.model.MysticLocation;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Executes a tutorial page button's action. Called from the page's
 * {@code handleDataEvent} (world thread); anything heavier than a message is
 * dispatched through the platform/service layers, never run inline.
 */
public final class TutorialButtonActionHandler {

    /** Page operations the renderer lends to the handler for UI actions. */
    public interface PageControls {
        void closePage();

        void openPage(String pageId);
    }

    private final MysticCore core;
    private final TutorialModule module;

    public TutorialButtonActionHandler(MysticCore core, TutorialModule module) {
        this.core = core;
        this.module = module;
    }

    public void execute(PlayerRef player, String pageId, String tutorialContext,
            TutorialButtonDefinition button, PageControls controls) {
        TutorialButtonActionType type = TutorialButtonActionType.fromString(button.action.type);
        if (type == null) {
            module.logger().error("Page '" + pageId + "' button '" + button.id
                    + "' has unknown action type '" + button.action.type + "'");
            return;
        }
        String value = TutorialPlaceholders.apply(button.action.value, player,
                tutorialContext, module.loader());
        switch (type) {
            case CLOSE -> controls.closePage();
            case PAGE -> controls.openPage(value);
            case COMMAND, PLAYER_COMMAND -> {
                controls.closePage();
                core.platform().dispatchPlayerCommand(player,
                        value.startsWith("/") ? value.substring(1) : value);
            }
            case CONSOLE_COMMAND -> {
                controls.closePage();
                core.platform().dispatchConsoleCommand(
                        value.startsWith("/") ? value.substring(1) : value);
            }
            case TUTORIAL -> {
                controls.closePage();
                module.sessions().start(player, value,
                                TutorialPlayOptions.of(TutorialPlayOptions.Source.BUTTON))
                        .thenAccept(result -> {
                            if (!result.started()) {
                                core.getMessageService().sendKey(player, result.messageKey(),
                                        Map.of("tutorial", value));
                            }
                        });
            }
            case MESSAGE -> core.getMessageService().send(player, value);
            case URL ->
                // No client browser-open packet exists in 0.5.6; deliver as a
                // clickable chat link instead (MysticText <link:> markup).
                core.getMessageService().send(player, "&7Link: <link:" + value + ">&b" + value + "</link>");
            case TELEPORT -> {
                controls.closePage();
                MysticLocation destination = parseLocation(value);
                if (destination == null) {
                    module.logger().error("Page '" + pageId + "' button '" + button.id
                            + "' has invalid teleport value '" + value
                            + "' (expected world,x,y,z[,yaw,pitch])");
                } else {
                    core.platform().teleportEntity(player, destination);
                }
            }
            case SOUND ->
                // TODO: no verified play-sound-to-player API in the 0.5.6 server
                // jar; wire this once one exists.
                module.logger().debug("Button '" + button.id + "' requested sound '" + value
                        + "' — sound actions are not supported on Hytale 0.5.6.");
        }
    }

    /** Parses {@code world,x,y,z[,yaw,pitch]} into a location; {@code null} if malformed. */
    private static MysticLocation parseLocation(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] parts = value.split(",");
        if (parts.length < 4) {
            return null;
        }
        try {
            return new MysticLocation(parts[0].trim(),
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim()),
                    Double.parseDouble(parts[3].trim()),
                    parts.length > 4 ? Float.parseFloat(parts[4].trim()) : 0f,
                    parts.length > 5 ? Float.parseFloat(parts[5].trim()) : 0f);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
