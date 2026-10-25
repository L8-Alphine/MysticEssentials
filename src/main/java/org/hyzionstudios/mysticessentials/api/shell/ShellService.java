package org.hyzionstudios.mysticessentials.api.shell;

import java.util.List;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * The Mystic Essentials navigation shell: the main menu and the shortcut rail.
 *
 * <p>Other mods use this to put themselves on the menu without Essentials
 * knowing anything about them, and without taking a runtime dependency in the
 * other direction: a mod registers on enable, unregisters on disable, and the
 * menu reflects that at the next open.</p>
 */
public interface ShellService {

    /**
     * Adds a card to the main menu, replacing any card already registered under
     * the same id.
     *
     * @return true when the card was accepted; false when the shell module is
     *         disabled on this server, in which case the caller should carry on
     *         without a menu entry rather than fail
     */
    boolean registerDestination(MenuDestination destination);

    /** Removes a card by id. @return whether a card was removed. */
    boolean unregisterDestination(String id);

    /** @return every externally registered card, in registration order. */
    List<MenuDestination> destinations();

    /** Opens the main menu for a player, on that player's section. */
    void openMenu(PlayerRef player);

    /** Opens the pointer-driven shortcuts page. */
    void openShortcuts(PlayerRef player);

    /**
     * Activates a rail slot as if the player had pressed it.
     *
     * @param slot 1–9
     * @return false when the number is out of range, the slot is empty, or the
     *         player may not use it — the player is told why either way
     */
    boolean activateSlot(PlayerRef player, int slot);

    /** Whether this player currently has the rail drawn. */
    boolean railVisible(PlayerRef player);

    /** Shows or hides the rail for one player and remembers the choice. */
    void setRailVisible(PlayerRef player, boolean visible);
}
