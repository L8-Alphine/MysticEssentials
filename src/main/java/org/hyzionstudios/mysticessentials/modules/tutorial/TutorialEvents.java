package org.hyzionstudios.mysticessentials.modules.tutorial;

import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.event.MysticEvent;
import org.hyzionstudios.mysticessentials.modules.tutorial.session.TutorialStopReason;

/**
 * Tutorial events published on the MysticEssentials {@link
 * org.hyzionstudios.mysticessentials.api.event.EventBus} so other modules and
 * addons can react to tutorial activity without coupling to this module.
 */
public final class TutorialEvents {

    private TutorialEvents() {
    }

    /** Base carrying the player/tutorial identity every tutorial event shares. */
    public abstract static class TutorialEvent implements MysticEvent {
        public final UUID playerId;
        public final String playerName;
        public final String tutorialId;

        protected TutorialEvent(UUID playerId, String playerName, String tutorialId) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.tutorialId = tutorialId;
        }
    }

    /** Fired when a session starts (after validation, before the scene). */
    public static final class Start extends TutorialEvent {
        public Start(UUID playerId, String playerName, String tutorialId) {
            super(playerId, playerName, tutorialId);
        }
    }

    public static final class Complete extends TutorialEvent {
        /** Whether this completion was recorded to the player's data. */
        public final boolean markedCompleted;

        public Complete(UUID playerId, String playerName, String tutorialId, boolean markedCompleted) {
            super(playerId, playerName, tutorialId);
            this.markedCompleted = markedCompleted;
        }
    }

    public static final class Cancel extends TutorialEvent {
        public final TutorialStopReason reason;

        public Cancel(UUID playerId, String playerName, String tutorialId, TutorialStopReason reason) {
            super(playerId, playerName, tutorialId);
            this.reason = reason;
        }
    }

    public static final class Skip extends TutorialEvent {
        public Skip(UUID playerId, String playerName, String tutorialId) {
            super(playerId, playerName, tutorialId);
        }
    }

    public static final class Fail extends TutorialEvent {
        public final String detail;

        public Fail(UUID playerId, String playerName, String tutorialId, String detail) {
            super(playerId, playerName, tutorialId);
            this.detail = detail == null ? "" : detail;
        }
    }

    public static final class Timeout extends TutorialEvent {
        public Timeout(UUID playerId, String playerName, String tutorialId) {
            super(playerId, playerName, tutorialId);
        }
    }

    /** Fired when a tutorial UI page opens ({@code tutorialId} may be blank for /tutorial page). */
    public static final class PageOpen extends TutorialEvent {
        public final String pageId;

        public PageOpen(UUID playerId, String playerName, String tutorialId, String pageId) {
            super(playerId, playerName, tutorialId);
            this.pageId = pageId;
        }
    }

    public static final class PageClose extends TutorialEvent {
        public final String pageId;

        public PageClose(UUID playerId, String playerName, String tutorialId, String pageId) {
            super(playerId, playerName, tutorialId);
            this.pageId = pageId;
        }
    }

    public static final class PageButtonClick extends TutorialEvent {
        public final String pageId;
        public final String buttonId;
        public final String actionType;

        public PageButtonClick(UUID playerId, String playerName, String tutorialId,
                String pageId, String buttonId, String actionType) {
            super(playerId, playerName, tutorialId);
            this.pageId = pageId;
            this.buttonId = buttonId;
            this.actionType = actionType;
        }
    }
}
