package org.hyzionstudios.mysticessentials.modules.tutorial.player;

/** One line of a player's tutorial history (start/complete/cancel/skip/timeout/fail). */
public final class TutorialHistoryEntry {

    public String tutorialId;
    /** Lowercase action keyword, e.g. {@code start}, {@code complete}, {@code timeout}. */
    public String action;
    /** Epoch milliseconds. */
    public long timestamp;

    public TutorialHistoryEntry() {
    }

    public TutorialHistoryEntry(String tutorialId, String action, long timestamp) {
        this.tutorialId = tutorialId;
        this.action = action;
        this.timestamp = timestamp;
    }
}
