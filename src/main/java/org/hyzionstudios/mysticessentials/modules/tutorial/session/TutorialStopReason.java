package org.hyzionstudios.mysticessentials.modules.tutorial.session;

/** Why a running tutorial session ended (drives restore, events, and logging). */
public enum TutorialStopReason {
    COMPLETED(TutorialSessionState.COMPLETED, "complete"),
    CANCELLED(TutorialSessionState.CANCELLED, "cancel"),
    SKIPPED(TutorialSessionState.SKIPPED, "skip"),
    FAILED(TutorialSessionState.FAILED, "fail"),
    TIMEOUT(TutorialSessionState.TIMED_OUT, "timeout"),
    DISCONNECT(TutorialSessionState.CANCELLED, "disconnect"),
    SHUTDOWN(TutorialSessionState.CANCELLED, "shutdown"),
    MODULE_DISABLED(TutorialSessionState.CANCELLED, "module-disabled"),
    ADMIN(TutorialSessionState.CANCELLED, "admin-stop");

    private final TutorialSessionState finalState;
    private final String historyAction;

    TutorialStopReason(TutorialSessionState finalState, String historyAction) {
        this.finalState = finalState;
        this.historyAction = historyAction;
    }

    public TutorialSessionState finalState() {
        return finalState;
    }

    /** Keyword recorded in the player's history log. */
    public String historyAction() {
        return historyAction;
    }
}
