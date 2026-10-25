package org.hyzionstudios.mysticessentials.modules.shell;

/**
 * The Main Menu's left navigation, as the design bible fixes it (§7).
 *
 * <p>The order here is the order of the rail. A section with nothing in it for
 * this player is left out of the rail entirely rather than shown empty — except
 * {@link #OVERVIEW}, which always has something to say.</p>
 */
public enum MenuSection {

    OVERVIEW("Overview", "What you can pick up next."),
    ADVENTURE("Adventure", "Quests, character, and where to go."),
    COMMUNITY("Community", "Guilds, party, mail and trading."),
    SERVICES("Services", "Economy, the store, servers and the wiki."),
    ACCOUNT("Account", "Your profile, nameplate and notifications."),
    SETTINGS("Settings", "Shortcuts, chat and personal preferences."),
    STAFF("Staff", "Moderation and creator tooling.");

    private final String title;
    private final String blurb;

    MenuSection(String title, String blurb) {
        this.title = title;
        this.blurb = blurb;
    }

    public String title() {
        return title;
    }

    public String blurb() {
        return blurb;
    }

    /** @return the named section, or {@link #OVERVIEW} when it is unknown or missing. */
    public static MenuSection parse(String value) {
        if (value == null || value.isBlank()) {
            return OVERVIEW;
        }
        for (MenuSection section : values()) {
            if (section.name().equalsIgnoreCase(value.trim())) {
                return section;
            }
        }
        return OVERVIEW;
    }
}
