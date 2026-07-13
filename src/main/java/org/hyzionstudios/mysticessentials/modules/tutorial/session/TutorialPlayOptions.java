package org.hyzionstudios.mysticessentials.modules.tutorial.session;

/**
 * How a tutorial start was requested. {@code force} bypasses replay and
 * requirement checks (admin/API use); {@code source} is informational for
 * events and the log.
 */
public final class TutorialPlayOptions {

    public enum Source {
        FIRST_JOIN,
        COMMAND,
        BUTTON,
        API
    }

    private final Source source;
    private final boolean force;
    /** Who initiated the start (player name or "console"); may be blank. */
    private final String startedBy;

    private TutorialPlayOptions(Source source, boolean force, String startedBy) {
        this.source = source;
        this.force = force;
        this.startedBy = startedBy == null ? "" : startedBy;
    }

    public static TutorialPlayOptions defaults() {
        return new TutorialPlayOptions(Source.API, false, "");
    }

    public static TutorialPlayOptions of(Source source) {
        return new TutorialPlayOptions(source, false, "");
    }

    public static TutorialPlayOptions of(Source source, boolean force, String startedBy) {
        return new TutorialPlayOptions(source, force, startedBy);
    }

    public Source source() {
        return source;
    }

    public boolean force() {
        return force;
    }

    public String startedBy() {
        return startedBy;
    }
}
