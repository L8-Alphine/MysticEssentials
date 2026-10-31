package org.hyzionstudios.mysticessentials.modules.shell;

import org.hyzionstudios.mysticessentials.platform.ui.VanillaIcons;

/**
 * The Main Menu's left navigation, as the design bible fixes it (§7).
 *
 * <p>The order here is the order of the rail. A section with nothing in it for
 * this player is left out of the rail entirely rather than shown empty — except
 * {@link #OVERVIEW}, which always has something to say.</p>
 */
public enum MenuSection {

    OVERVIEW("Overview", "What you can pick up next.", VanillaIcons.MARKER_COORDINATE),
    ADVENTURE("Adventure", "Quests, character, and where to go.", VanillaIcons.WEAPONS),
    COMMUNITY("Community", "Guilds, party, mail and trading.", VanillaIcons.EMOTE_FIST_BUMP),
    SERVICES("Services", "Economy, the store, servers and the wiki.", VanillaIcons.ORE),
    ACCOUNT("Account", "Your profile, nameplate and notifications.", VanillaIcons.MARKER_PLAYER),
    SETTINGS("Settings", "Shortcuts, chat and personal preferences.", VanillaIcons.TOOLS),
    STAFF("Staff", "Moderation and creator tooling.", VanillaIcons.EDITOR);

    private final String title;
    private final String blurb;
    private final String icon;

    MenuSection(String title, String blurb, String icon) {
        this.title = title;
        this.blurb = blurb;
        this.icon = icon;
    }

    /** @return the section's vanilla icon, Common-rooted */
    public String icon() {
        return icon;
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
