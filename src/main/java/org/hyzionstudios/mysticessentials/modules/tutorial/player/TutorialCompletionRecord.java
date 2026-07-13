package org.hyzionstudios.mysticessentials.modules.tutorial.player;

/**
 * Per-tutorial progress counters stored inside {@link TutorialPlayerData}.
 * Timestamps are epoch milliseconds.
 */
public final class TutorialCompletionRecord {

    public String tutorialId;
    public boolean completed;
    public long firstCompletedAt;
    public long lastCompletedAt;
    public int timesCompleted;
    public int timesPlayed;
    public int timesSkipped;

    public TutorialCompletionRecord() {
    }

    public TutorialCompletionRecord(String tutorialId) {
        this.tutorialId = tutorialId;
    }

    public void markCompleted(long now) {
        if (!completed) {
            completed = true;
            firstCompletedAt = now;
        }
        lastCompletedAt = now;
        timesCompleted++;
    }
}
