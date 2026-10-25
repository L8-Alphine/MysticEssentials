package org.hyzionstudios.mysticessentials.modules.wiki;

import java.util.ArrayList;
import java.util.List;

/**
 * A wiki page written on the server and stored by Essentials.
 *
 * <p>A plain mutable bean rather than a record: this is the Gson shape of
 * {@code data/modules/wiki/articles.json}, and the file is meant to be
 * hand-editable by a server owner who would rather work in a text editor than
 * the in-game one.</p>
 */
public final class WikiArticle {

    /** Unique, lowercase, the name {@code [[links]]} and {@code /wiki <id>} use. */
    public String id = "";
    public String title = "";
    public String category = "General";
    /** One line for the browser row. */
    public String summary = "";
    /** The page text. See {@code WikiMarkup} for the small markup it accepts. */
    public String body = "";
    /** Extra words the search should match. */
    public List<String> tags = new ArrayList<>();
    /** Sort key within the category; lower sorts first. */
    public int order = 100;
    /** Permission needed to read it; empty means everyone. */
    public String viewPermission = "";
    /** Username of whoever last saved it, for the footer. */
    public String updatedBy = "";
    /** Epoch millis of the last save; 0 when it has never been saved. */
    public long updatedAt;

    public WikiArticle() {
    }

    WikiArticle(String id, String title, String category, String summary, String body) {
        this.id = id;
        this.title = title;
        this.category = category;
        this.summary = summary;
        this.body = body;
    }

    /** @return the title, falling back to the id so a half-written page is still findable. */
    public String displayTitle() {
        return title == null || title.isBlank() ? id : title;
    }

    /** @return the category, never blank. */
    public String displayCategory() {
        return category == null || category.isBlank() ? "General" : category.trim();
    }
}
