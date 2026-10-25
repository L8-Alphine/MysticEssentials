package org.hyzionstudios.mysticessentials.api.wiki;

import java.util.List;
import java.util.Optional;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * The built-in server wiki.
 *
 * <p>Two kinds of page live side by side. <b>Stored</b> articles are written in
 * game by staff and persisted by Essentials. <b>Contributed</b> entries come
 * from other mods through {@link #registerEntry} and are rendered from their
 * owner on every open — Essentials never stores or edits them, which keeps a
 * mod's documentation accurate when the mod changes and makes an uninstalled
 * mod's pages disappear with it rather than rot.</p>
 *
 * <p>Both kinds share one id space, one search, and one {@code [[link]]}
 * syntax, so a staff-written page can link into a mod's reference and back.</p>
 */
public interface WikiService {

    /**
     * Adds or replaces a contributed page.
     *
     * @return true when it was accepted; false when the wiki module is
     *         disabled, in which case the caller should carry on without a wiki
     *         page rather than fail
     */
    boolean registerEntry(WikiEntry entry);

    /** Removes a contributed page by id. @return whether one was removed. */
    boolean unregisterEntry(String id);

    /**
     * Removes every contributed page whose id starts with {@code prefix:}.
     * A mod calls this on disable so its pages leave with it.
     *
     * @return how many were removed
     */
    int unregisterNamespace(String prefix);

    /** @return every contributed page, in registration order. */
    List<WikiEntry> entries();

    /** Opens the wiki browser for a player. */
    void open(PlayerRef player);

    /** Opens one page directly; falls back to the browser when the id is unknown. */
    void open(PlayerRef player, String articleId);

    /** @return the categories a player can see, in display order. */
    List<String> categories(PlayerRef player);

    /** @return the rendered body of a page, stored or contributed. */
    Optional<String> body(PlayerRef player, String articleId);

    /** Whether this player may edit stored articles. */
    boolean canEdit(PlayerRef player);
}
