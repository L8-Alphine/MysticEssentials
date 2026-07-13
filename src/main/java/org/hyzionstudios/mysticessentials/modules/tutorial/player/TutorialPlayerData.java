package org.hyzionstudios.mysticessentials.modules.tutorial.player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything the tutorial module persists for one player, written to
 * {@code mods/MysticEssentials/data/modules/tutorial/players/<uuid>.json}.
 */
public final class TutorialPlayerData {

    public String uuid;
    public String username = "";

    /** Progress per tutorial id (lowercase). */
    public Map<String, TutorialCompletionRecord> tutorials = new LinkedHashMap<>();

    /** Chronological activity log (capped; see {@code MAX_HISTORY}). */
    public List<TutorialHistoryEntry> history = new ArrayList<>();

    /**
     * Set while a session is running so an unclean exit (crash, disconnect
     * mid-tutorial) can be detected and the player's state repaired on the next
     * join. Cleared on every normal session end.
     */
    public String activeTutorialId;

    private static final int MAX_HISTORY = 100;

    public TutorialPlayerData() {
    }

    public TutorialPlayerData(String uuid, String username) {
        this.uuid = uuid;
        this.username = username == null ? "" : username;
    }

    public TutorialCompletionRecord record(String tutorialId) {
        return tutorials.computeIfAbsent(tutorialId.toLowerCase(Locale.ROOT),
                TutorialCompletionRecord::new);
    }

    public boolean hasCompleted(String tutorialId) {
        TutorialCompletionRecord record = tutorials.get(tutorialId.toLowerCase(Locale.ROOT));
        return record != null && record.completed;
    }

    public void addHistory(String tutorialId, String action, long now) {
        history.add(new TutorialHistoryEntry(tutorialId, action, now));
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
    }
}
