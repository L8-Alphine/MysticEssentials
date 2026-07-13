package org.hyzionstudios.mysticessentials.modules.tutorial.session;

/** Lifecycle state of one {@link TutorialSession}. */
public enum TutorialSessionState {
    /** Created; player state being captured/applied. */
    STARTING,
    /** Cinematic scene playing (or scene phase skipped straight through). */
    PLAYING_SCENE,
    /** Scene done; completion page shown, waiting for the player to close it. */
    SHOWING_PAGE,
    COMPLETED,
    CANCELLED,
    SKIPPED,
    FAILED,
    TIMED_OUT;

    /** @return {@code true} for states in which the session is still running. */
    public boolean isActive() {
        return this == STARTING || this == PLAYING_SCENE || this == SHOWING_PAGE;
    }
}
