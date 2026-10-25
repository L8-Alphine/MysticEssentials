package org.hyzionstudios.mysticessentials.api.shell;

import java.util.Objects;
import java.util.function.Predicate;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * One card in the Mystic Essentials main menu.
 *
 * <p>This is the registration contract the design bible asks future modules to
 * declare (§12): an id, a display name, an icon reference, a parent category,
 * the capability it offers, and what the shell should say when it is not
 * available. The shell only routes — {@link #action} runs on the owning
 * module's side and must do its own authorisation, because hiding a tile is
 * not authorisation.</p>
 *
 * @param id          unique, stable, lowercase ({@code "mysticguilds:roster"})
 * @param section     which menu section the card appears under
 * @param title       card title
 * @param glyph       one or two characters for the card's icon well
 * @param blurb       one line on what the card leads to
 * @param order       sort key within the section; lower sorts first
 * @param visibleTo   whether this player sees the card at all
 * @param availableTo whether the card is actionable for this player; when it
 *                    returns false the card renders with {@link #unavailableReason}
 * @param unavailableReason human-readable explanation for the disabled state
 * @param action      what to run when the card is pressed; may open a page,
 *                    dispatch a command, or anything else the owner wants
 */
public record MenuDestination(
        String id,
        MenuSectionId section,
        String title,
        String glyph,
        String blurb,
        int order,
        Predicate<PlayerRef> visibleTo,
        Predicate<PlayerRef> availableTo,
        String unavailableReason,
        java.util.function.Consumer<PlayerRef> action) {

    public MenuDestination {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(title, "title");
        glyph = glyph == null || glyph.isBlank() ? "*" : glyph;
        blurb = blurb == null ? "" : blurb;
        visibleTo = visibleTo == null ? player -> true : visibleTo;
        availableTo = availableTo == null ? player -> true : availableTo;
        unavailableReason = unavailableReason == null ? "" : unavailableReason;
        Objects.requireNonNull(action, "action");
    }

    /** @return a builder for the common case: always visible, always available. */
    public static MenuDestination of(String id, MenuSectionId section, String title, String glyph,
            String blurb, int order, java.util.function.Consumer<PlayerRef> action) {
        return new MenuDestination(id, section, title, glyph, blurb, order, null, null, "", action);
    }

    /** The menu sections a destination can be filed under. Mirrors the bible's §7 table. */
    public enum MenuSectionId {
        OVERVIEW, ADVENTURE, COMMUNITY, SERVICES, ACCOUNT, SETTINGS, STAFF
    }
}
