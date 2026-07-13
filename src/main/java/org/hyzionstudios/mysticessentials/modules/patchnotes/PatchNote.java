package org.hyzionstudios.mysticessentials.modules.patchnotes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A single patch note loaded from
 * {@code mods/MysticEssentials/modules/patchnotes/patches/*.json}. JSON carries
 * the structure and metadata (sorting, pinning, filtering, read tracking); the
 * per-section {@link Section#body} holds the human-written content in the
 * Markdown subset rendered by {@link PatchMarkup}.
 *
 * <p>Field names map directly to JSON keys via Gson. {@link #sourceFile} is
 * transient bookkeeping so it never round-trips into the file.</p>
 */
public final class PatchNote {

    public String id;
    public String title;
    public String version;
    /** ISO date {@code yyyy-MM-dd}. */
    public String date;
    public String author;

    public boolean pinned;
    /** Higher sorts first (within the same pinned tier); ties break on {@link #date}. */
    public int priority;
    /** Counts toward the join notification / auto-surfacing. */
    public boolean showOnLogin = true;
    /** Reserved: patches operators may want to force-acknowledge. */
    public boolean requiredRead;

    public String summary;
    public List<String> tags = new ArrayList<>();
    /** Optional convenience list of the category ids used by this patch. */
    public List<String> categories = new ArrayList<>();
    public List<Section> sections = new ArrayList<>();

    /** Source file this note was loaded from (never serialized). */
    public transient Path sourceFile;

    /** A titled block of body content, tagged with a category {@code type} for filtering. */
    public static final class Section {
        /** One of the configured category ids (additions / fixes / changes / removals / ...). */
        public String type;
        public String title;
        /** Markdown-subset content; see {@link PatchMarkup}. */
        public String body;
    }

    /** @return a non-null, safe id for keying/read-tracking (empty if the file omitted one). */
    public String safeId() {
        return id == null ? "" : id.trim();
    }

    public boolean hasSections() {
        return sections != null && !sections.isEmpty();
    }
}
