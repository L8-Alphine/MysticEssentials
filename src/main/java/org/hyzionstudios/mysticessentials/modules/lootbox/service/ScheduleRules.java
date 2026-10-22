package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;

/**
 * Scheduled and seasonal revisions (P3-WP5). A draft may say when its odds
 * start ({@code startsAt}) and end ({@code endsAt}); publishing it before its
 * start schedules the revision instead of switching to it, and at the end the
 * pool either goes back to the revision that was live before ({@code REVERT},
 * as a rollback, so the history shows it) or closes ({@code CLOSE}). Staff can
 * also schedule a rollback. Players always see what is coming: the preview
 * shows the start date of scheduled odds and a banner while odds are
 * time-limited.
 *
 * <p>Every change is decided inside one update of the pool's
 * {@code lootbox_def} document, which first claims the due action (clears its
 * trigger): with several servers running the same schedule, exactly one performs
 * it. Engine-free.</p>
 */
public final class ScheduleRules {

    public static final String REVERT = "REVERT";
    public static final String CLOSE = "CLOSE";

    /** What is due for a pool now. */
    public enum Due {
        NONE,
        /** A scheduled revision's start has come: make it the active one. */
        ACTIVATE,
        /** Time-limited odds ended: republish the revision that was live before them. */
        END_REVERT,
        /** Time-limited odds ended, and the pool closes. */
        END_CLOSE,
        /** A staff-scheduled rollback's time has come. */
        ROLLBACK
    }

    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .withZone(ZoneId.of("UTC"));

    private ScheduleRules() {
    }

    /** An ISO-8601 instant as epoch millis; 0 when blank. {@code DateTimeParseException} when malformed. */
    public static long instant(String iso) {
        return iso == null || iso.isBlank() ? 0 : Instant.parse(iso.trim()).toEpochMilli();
    }

    public static String shown(long epochMillis) {
        return SHOWN.format(Instant.ofEpochMilli(epochMillis));
    }

    /** {@code REVERT} (default) or {@code CLOSE}. */
    public static String afterEnd(LootBoxDraft spec) {
        return spec.afterEnd != null && CLOSE.equalsIgnoreCase(spec.afterEnd.trim()) ? CLOSE : REVERT;
    }

    /** Publish gates for the schedule fields; {@code now} is the publish time. */
    public static List<String> validate(LootBoxDraft draft, long now) {
        List<String> problems = new ArrayList<>();
        long start;
        long end;
        try {
            start = instant(draft.startsAt);
        } catch (DateTimeParseException e) {
            problems.add("startsAt must be an ISO-8601 instant such as 2026-12-01T00:00:00Z");
            start = -1;
        }
        try {
            end = instant(draft.endsAt);
        } catch (DateTimeParseException e) {
            problems.add("endsAt must be an ISO-8601 instant such as 2026-12-31T23:59:59Z");
            end = -1;
        }
        String after = draft.afterEnd == null ? "" : draft.afterEnd.trim().toUpperCase(Locale.ROOT);
        if (!after.isEmpty() && !after.equals(REVERT) && !after.equals(CLOSE)) {
            problems.add("afterEnd must be REVERT or CLOSE");
        }
        if (end > 0) {
            if (end <= now) {
                problems.add("endsAt is already in the past");
            }
            if (start > 0 && end <= start) {
                problems.add("endsAt must be after startsAt");
            }
        } else if (!after.isEmpty() && end == 0) {
            problems.add("afterEnd only applies with endsAt");
        }
        return problems;
    }

    /** Whether publishing {@code spec} now schedules it rather than switching to it. */
    public static boolean startsLater(LootBoxDraft spec, long now) {
        return instant(spec.startsAt) > now;
    }

    /**
     * What is due for {@code state} at {@code now}. A pending start comes first,
     * then the end of the live odds, then a staff rollback.
     */
    public static Due due(LootBoxRevision.State state, long now) {
        if (state == null) {
            return Due.NONE;
        }
        if (state.scheduledRevision > 0 && state.scheduledAt > 0 && now >= state.scheduledAt) {
            return Due.ACTIVATE;
        }
        if (state.endsAt > 0 && now >= state.endsAt) {
            return state.closeAtEnd || state.revertTo <= 0 ? Due.END_CLOSE : Due.END_REVERT;
        }
        if (state.rollbackTo > 0 && state.rollbackAt > 0 && now >= state.rollbackAt) {
            return Due.ROLLBACK;
        }
        return Due.NONE;
    }

    /**
     * Makes {@code revision} the live one in {@code state} (the caller holds the
     * update), remembering what to do at its end. {@code previous} is the
     * revision that was live before it, which REVERT goes back to. An end that
     * already passed (a rollback to old time-limited odds) is ignored, so the
     * copy doesn't end the moment it starts.
     */
    public static void activate(LootBoxRevision.State state, LootBoxRevision revision, int previous, long now) {
        state.activeRevision = revision.revision;
        if (state.scheduledRevision == revision.revision) {
            state.scheduledRevision = 0;
            state.scheduledAt = 0;
        }
        long end = endOf(revision.spec);
        if (end <= now) {
            end = 0;
        }
        state.endsAt = end;
        state.closeAtEnd = end > 0 && CLOSE.equals(afterEnd(revision.spec));
        state.revertTo = end > 0 ? previous : 0;
    }

    /** A revision's end as epoch millis; 0 for none or unreadable (the publish gate refuses unreadable ones). */
    static long endOf(LootBoxDraft spec) {
        try {
            return instant(spec.endsAt);
        } catch (DateTimeParseException e) {
            return 0;
        }
    }

    /** Clears the end trigger once it has been acted on (or the revision it belonged to was replaced). */
    public static void clearEnd(LootBoxRevision.State state) {
        state.endsAt = 0;
        state.closeAtEnd = false;
        state.revertTo = 0;
    }

    /** A claimed action: what this caller now performs ({@code revision}: the one activated or republished). */
    public record Claim(Due due, int revision) {
    }

    /**
     * Claims {@code expected} inside one update of {@code state} (the caller holds
     * it): returns {@code null}, changing nothing, unless that action is still due
     * now, so of several servers sweeping one pool only the first acts.
     * ACTIVATE needs the scheduled revision itself ({@code activating}, already
     * loaded; {@code null} if it is missing, which drops the schedule); END_CLOSE
     * closes the pool here; END_REVERT and ROLLBACK clear their trigger and return
     * the revision the caller then republishes.
     */
    public static Claim claim(LootBoxRevision.State state, Due expected, LootBoxRevision activating, long now) {
        if (state == null || due(state, now) != expected) {
            return null;
        }
        switch (expected) {
            case ACTIVATE -> {
                if (activating == null || activating.revision != state.scheduledRevision) {
                    int dropped = state.scheduledRevision;
                    state.scheduledRevision = 0;
                    state.scheduledAt = 0;
                    return new Claim(Due.NONE, dropped);
                }
                activate(state, activating, state.activeRevision, now);
                return new Claim(Due.ACTIVATE, activating.revision);
            }
            case END_CLOSE -> {
                clearEnd(state);
                state.disabled = true;
                return new Claim(Due.END_CLOSE, state.activeRevision);
            }
            case END_REVERT -> {
                int target = state.revertTo;
                clearEnd(state);
                return new Claim(Due.END_REVERT, target);
            }
            case ROLLBACK -> {
                int target = state.rollbackTo;
                state.rollbackTo = 0;
                state.rollbackAt = 0;
                return new Claim(Due.ROLLBACK, target);
            }
            default -> {
                return null;
            }
        }
    }

    /** The preview banner for a pool's schedule, or {@code null} when nothing is scheduled. */
    public static String banner(LootBoxRevision.State state, long now) {
        if (state == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (state.endsAt > now) {
            parts.add("Time-limited odds until " + shown(state.endsAt)
                    + (state.closeAtEnd || state.revertTo <= 0 ? "; the pool closes then."
                            : "; then the earlier odds return."));
        }
        if (state.scheduledRevision > 0 && state.scheduledAt > now) {
            parts.add("New odds (revision " + state.scheduledRevision + ") start " + shown(state.scheduledAt) + ".");
        }
        if (state.rollbackTo > 0 && state.rollbackAt > now) {
            parts.add("The odds of revision " + state.rollbackTo + " return " + shown(state.rollbackAt) + ".");
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }
}
