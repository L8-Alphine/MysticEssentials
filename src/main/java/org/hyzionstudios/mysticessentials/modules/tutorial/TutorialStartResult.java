package org.hyzionstudios.mysticessentials.modules.tutorial;

/** Outcome of a {@code TutorialService.playTutorial} request. */
public enum TutorialStartResult {
    STARTED,
    MODULE_DISABLED,
    UNKNOWN_TUTORIAL,
    TUTORIAL_DISABLED,
    ALREADY_IN_TUTORIAL,
    ALREADY_COMPLETED,
    REQUIREMENTS_NOT_MET,
    PLAYER_OFFLINE,
    ERROR;

    public boolean started() {
        return this == STARTED;
    }

    /** Message-bundle key describing this result to a command sender. */
    public String messageKey() {
        return switch (this) {
            case STARTED -> "tutorial-started";
            case MODULE_DISABLED -> "module-disabled";
            case UNKNOWN_TUTORIAL -> "tutorial-unknown";
            case TUTORIAL_DISABLED -> "tutorial-disabled";
            case ALREADY_IN_TUTORIAL -> "tutorial-already-active";
            case ALREADY_COMPLETED -> "tutorial-already-completed";
            case REQUIREMENTS_NOT_MET -> "tutorial-requirements-not-met";
            case PLAYER_OFFLINE -> "player-not-found";
            case ERROR -> "tutorial-start-error";
        };
    }
}
