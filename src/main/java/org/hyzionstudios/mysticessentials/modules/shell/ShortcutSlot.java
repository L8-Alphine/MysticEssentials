package org.hyzionstudios.mysticessentials.modules.shell;

/**
 * One rail position, resolved for one player at one moment.
 *
 * <p>Availability is carried with a reason rather than as a bare flag: the
 * bible's unavailable-behaviour table (§8) asks every denied slot to say why —
 * "you are not in a guild" and "the server has no guild channel" are different
 * answers and the player deserves the right one.</p>
 *
 * @param index      1–9, the number drawn on the tile
 * @param kind       what pressing it does
 * @param label      display name
 * @param glyph      one or two characters for the tile
 * @param target     channel id or command line, by kind
 * @param state      a short word for the tile's state line ("speaking", "")
 * @param available  whether pressing it will do anything
 * @param reason     why not, when {@code available} is false
 * @param active     whether this is the channel the player is speaking in
 */
public record ShortcutSlot(
        int index,
        ShortcutKind kind,
        String label,
        String glyph,
        String target,
        String state,
        boolean available,
        String reason,
        boolean active) {

    /** An unoccupied position: drawn, numbered, inert. */
    static ShortcutSlot empty(int index) {
        return new ShortcutSlot(index, ShortcutKind.NONE, "Unused", "-", "", "",
                false, "Nothing is assigned to this slot", false);
    }

    /** @return the line the tile and row show under the name. */
    public String stateLine() {
        if (!available) {
            return reason;
        }
        return state == null || state.isBlank() ? "Ready" : state;
    }
}
