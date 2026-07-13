package org.hyzionstudios.mysticessentials.modules.tutorial;

/** Outcome of a {@code TutorialService.stopTutorial} request. */
public enum TutorialStopResult {
    STOPPED,
    NOT_IN_TUTORIAL;

    public boolean stopped() {
        return this == STOPPED;
    }
}
