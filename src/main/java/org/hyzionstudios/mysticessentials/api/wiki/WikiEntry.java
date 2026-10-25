package org.hyzionstudios.mysticessentials.api.wiki;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * A wiki page contributed by another mod.
 *
 * <p>This is how a mod puts its own documentation inside the server wiki
 * without the wiki storing — or being able to edit — someone else's content.
 * Contributed entries are read-only in the browser: they are rendered from the
 * owner's {@link #body} supplier every time they are opened, so a mod can keep
 * them current (live command lists, the player's own quest state) rather than
 * shipping a frozen copy.</p>
 *
 * <p>Body text uses the same light markup as stored articles: blank lines
 * separate paragraphs, a line starting with {@code ## } is a heading, a line
 * starting with {@code - } is a bullet, and {@code [[article-id]]} links to
 * another wiki page — contributed or stored, it makes no difference to the
 * reader.</p>
 *
 * @param id       unique and stable, lowercase, used by {@code [[links]]}
 *                 ({@code "mysticguilds:claims"})
 * @param title    heading shown in the browser and at the top of the page
 * @param category which section of the browser it files under
 * @param summary  one line for the browser row
 * @param tags     extra words the search should match
 * @param order    sort key within the category; lower sorts first
 * @param visibleTo whether this player may see the entry at all
 * @param body     the page text, rendered fresh on every open
 */
public record WikiEntry(
        String id,
        String title,
        String category,
        String summary,
        List<String> tags,
        int order,
        Predicate<PlayerRef> visibleTo,
        java.util.function.Function<PlayerRef, String> body) {

    public WikiEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        category = category == null || category.isBlank() ? "General" : category.trim();
        summary = summary == null ? "" : summary;
        tags = tags == null ? List.of() : List.copyOf(tags);
        visibleTo = visibleTo == null ? player -> true : visibleTo;
        Objects.requireNonNull(body, "body");
    }

    /** A fixed page: same text for everyone, visible to everyone. */
    public static WikiEntry of(String id, String title, String category, String summary, String body) {
        return new WikiEntry(id, title, category, summary, List.of(), 100, null, player -> body);
    }
}
