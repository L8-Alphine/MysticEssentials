package org.hyzionstudios.mysticessentials.modules.wiki;

import static org.hyzionstudios.mysticessentials.platform.ui.MysticPage.uiText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.wiki.WikiModule.WikiPage;
import org.hyzionstudios.mysticessentials.platform.ui.MysticPage;

import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** Browser, reader and editor for the server wiki. */
final class WikiPages {

    static final String BROWSER_UI = "MysticEssentials/Wiki.ui";
    static final String CATEGORY_ROW_UI = "MysticEssentials/WikiCategoryRow.ui";
    static final String ARTICLE_ROW_UI = "MysticEssentials/WikiRow.ui";
    static final String ARTICLE_UI = "MysticEssentials/WikiArticle.ui";
    static final String LINE_UI = "MysticEssentials/WikiLine.ui";
    static final String RELATED_UI = "MysticEssentials/WikiRelatedLink.ui";
    static final String EDITOR_UI = "MysticEssentials/WikiEditor.ui";

    /** Related links shown under a page; more than this and the strip wraps badly. */
    private static final int MAX_RELATED = 3;

    private WikiPages() {
    }

    // ----- Browser -----------------------------------------------------------

    /**
     * Categories on the left, pages on the right. A category of {@code null}
     * means "everything", which is what search needs: typing a word should
     * look across the whole wiki, not inside whichever category happened to be
     * selected.
     */
    static final class BrowserPage extends MysticPage {

        private final WikiModule wiki;
        private final String category;
        private final String search;

        BrowserPage(MysticCore core, WikiModule wiki, PlayerRef player) {
            this(core, wiki, player, null, "");
        }

        BrowserPage(MysticCore core, WikiModule wiki, PlayerRef player, String category, String search) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.wiki = wiki;
            this.category = category;
            this.search = search == null ? "" : search;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(BROWSER_UI);
            WikiConfig config = wiki.config();
            cmd.set("#WikiSubtitle.TextSpans", uiText("#WikiSubtitle.TextSpans", config.subtitle));
            // The page is rebuilt on every keystroke, so the box has to be
            // handed its own text back or it would clear itself as it is typed.
            cmd.set("#SearchInput.Value", search);

            List<WikiPage> all = wiki.pages(player);
            List<WikiPage> visible = new ArrayList<>();
            for (WikiPage page : all) {
                // A search is a whole-wiki search: a hit outside the selected
                // category is still the answer the reader asked for, so the
                // category filter only applies while the box is empty.
                boolean keep = search.isBlank()
                        ? category == null || page.category().equalsIgnoreCase(category)
                        : WikiModule.matches(page, search);
                if (keep) {
                    visible.add(page);
                }
            }
            cmd.set("#WikiCount.TextSpans", uiText("#WikiCount.TextSpans",
                    rowCount(Math.min(visible.size(), MAX_LIST_ROWS), all.size(), "pages")));

            renderCategories(cmd, event, all, wiki.categoriesOf(all));
            renderArticles(cmd, event, visible);

            cmd.set("#WikiEmpty.Visible", visible.isEmpty());
            if (visible.isEmpty()) {
                cmd.set("#WikiEmpty.TextSpans", uiText("#WikiEmpty.TextSpans",
                        search.isBlank() ? "Nothing has been written here yet."
                                : "Nothing matches \"" + search + "\"."));
            }

            boolean canEdit = wiki.canEdit(player);
            cmd.set("#NewArticleButton.Visible", canEdit);
            if (canEdit) {
                event.addEventBinding(CustomUIEventBindingType.Activating, "#NewArticleButton",
                        new EventData().put("action", "new"));
            }
            event.addEventBinding(CustomUIEventBindingType.ValueChanged, "#SearchInput",
                    new EventData().put("action", "search").append("@search", "#SearchInput.Value"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#MenuButton",
                    new EventData().put("action", "menu"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                    new EventData().put("action", "close"));
        }

        private void renderCategories(UICommandBuilder cmd, UIEventBuilder event, List<WikiPage> all,
                List<String> categories) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("", all.size());
            for (String name : categories) {
                int count = 0;
                for (WikiPage page : all) {
                    if (page.category().equalsIgnoreCase(name)) {
                        count++;
                    }
                }
                counts.put(name, count);
            }
            int index = 0;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                String row = "#CategoryList[" + index + "]";
                cmd.append("#CategoryList", CATEGORY_ROW_UI);
                boolean everything = entry.getKey().isEmpty();
                cmd.set(row + " #CategoryName.TextSpans", uiText(row + " #CategoryName.TextSpans",
                        everything ? "All Pages" : entry.getKey()));
                cmd.set(row + " #CategoryCount.TextSpans",
                        uiText(row + " #CategoryCount.TextSpans", String.valueOf(entry.getValue())));
                cmd.set(row + " #CategorySelected.Visible",
                        everything ? category == null : entry.getKey().equalsIgnoreCase(category));
                event.addEventBinding(CustomUIEventBindingType.Activating, row,
                        new EventData().put("action", "category").put("category", entry.getKey()));
                index++;
            }
        }

        private void renderArticles(UICommandBuilder cmd, UIEventBuilder event, List<WikiPage> all) {
            List<WikiPage> visible = capRows(all);
            for (int i = 0; i < visible.size(); i++) {
                WikiPage page = visible.get(i);
                String row = "#ArticleList[" + i + "]";
                cmd.append("#ArticleList", ARTICLE_ROW_UI);
                cmd.set(row + " #WikiRowTitle.TextSpans",
                        uiText(row + " #WikiRowTitle.TextSpans", page.title()));
                cmd.set(row + " #WikiRowSummary.TextSpans",
                        uiText(row + " #WikiRowSummary.TextSpans",
                                page.summary().isBlank() ? page.category() : page.summary()));
                cmd.set(row + " #WikiRowSource.TextSpans",
                        uiText(row + " #WikiRowSource.TextSpans", page.stored() ? "" : "From a mod"));
                event.addEventBinding(CustomUIEventBindingType.Activating, row,
                        new EventData().put("action", "open").put("article", page.id()));
            }
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            switch (string(payload, "action")) {
                case "category" -> {
                    String picked = field(payload, "category");
                    reopen(ref, store, new BrowserPage(core, wiki, player,
                            picked.isEmpty() ? null : picked, search));
                }
                case "search" -> reopen(ref, store,
                        new BrowserPage(core, wiki, player, category, field(payload, "search")));
                case "open" -> reopen(ref, store,
                        new ArticlePage(core, wiki, player, field(payload, "article"), category, search));
                case "new" -> {
                    if (wiki.canEdit(player)) {
                        reopen(ref, store, new EditorPage(core, wiki, player, ""));
                    }
                }
                case "menu" -> {
                    close(ref, store);
                    core.platform().dispatchPlayerCommand(player, "menu");
                }
                case "close" -> close(ref, store);
                default -> {
                    // Unknown action: leave the page as it is.
                }
            }
        }
    }

    // ----- Reader ------------------------------------------------------------

    /** One page, rendered a block at a time so links inside it stay pressable. */
    static final class ArticlePage extends MysticPage {

        private final WikiModule wiki;
        private final String articleId;
        private final String backCategory;
        private final String backSearch;

        ArticlePage(MysticCore core, WikiModule wiki, PlayerRef player, String articleId) {
            this(core, wiki, player, articleId, null, "");
        }

        ArticlePage(MysticCore core, WikiModule wiki, PlayerRef player, String articleId,
                String backCategory, String backSearch) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.wiki = wiki;
            this.articleId = WikiModule.key(articleId);
            this.backCategory = backCategory;
            this.backSearch = backSearch == null ? "" : backSearch;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(ARTICLE_UI);
            WikiPage page = wiki.page(player, articleId).orElse(null);
            if (page == null) {
                // Deleted, or a [[link]] to a page that was never written.
                cmd.set("#ArticleTitle.TextSpans", uiText("#ArticleTitle.TextSpans", "Not found"));
                cmd.set("#ArticleSummary.TextSpans", uiText("#ArticleSummary.TextSpans",
                        "There is no page called \"" + articleId + "\"."));
                cmd.set("#ArticleEmpty.Visible", true);
                bindFooter(event);
                return;
            }

            cmd.set("#ArticleTitle.TextSpans", uiText("#ArticleTitle.TextSpans", page.title()));
            cmd.set("#ArticleSummary.TextSpans", uiText("#ArticleSummary.TextSpans", page.summary()));
            cmd.set("#ArticleCategory.TextSpans", uiText("#ArticleCategory.TextSpans", page.category()));
            cmd.set("#ArticleFooter.TextSpans", uiText("#ArticleFooter.TextSpans", page.footer()));

            // A very long article is one very large packet; draw the first
            // screens and say the rest is there.
            List<WikiMarkup.Block> parsed = WikiMarkup.parse(page.body());
            List<WikiMarkup.Block> blocks = capRows(parsed);
            cmd.set("#ArticleEmpty.Visible", parsed.isEmpty());
            for (int i = 0; i < blocks.size(); i++) {
                renderBlock(cmd, event, i, blocks.get(i));
            }
            if (blocks.size() < parsed.size()) {
                cmd.set("#ArticleFooter.TextSpans", uiText("#ArticleFooter.TextSpans",
                        "Showing the first " + blocks.size() + " of " + parsed.size()
                                + " lines \u00b7 " + page.footer()));
            }

            Map<String, String> related = WikiMarkup.relatedLinks(page.body());
            cmd.set("#RelatedSection.Visible", !related.isEmpty());
            int index = 0;
            for (Map.Entry<String, String> link : related.entrySet()) {
                if (index >= MAX_RELATED) {
                    break;
                }
                String row = "#RelatedList[" + index + "]";
                cmd.append("#RelatedList", RELATED_UI);
                cmd.set(row + " #RelatedLink.TextSpans",
                        uiText(row + " #RelatedLink.TextSpans", link.getValue()));
                event.addEventBinding(CustomUIEventBindingType.Activating, row + " #RelatedLink",
                        new EventData().put("action", "open").put("article", link.getKey()));
                index++;
            }

            // Only stored pages are editable here; a mod's page belongs to
            // the mod that renders it.
            boolean editable = page.stored() && wiki.canEdit(player);
            cmd.set("#EditButton.Visible", editable);
            if (editable) {
                event.addEventBinding(CustomUIEventBindingType.Activating, "#EditButton",
                        new EventData().put("action", "edit"));
            }
            bindFooter(event);
        }

        private void renderBlock(UICommandBuilder cmd, UIEventBuilder event, int index,
                WikiMarkup.Block block) {
            String row = "#ArticleBody[" + index + "]";
            cmd.append("#ArticleBody", LINE_UI);
            switch (block.kind()) {
                case HEADING -> {
                    cmd.set(row + " #LineHeading.Visible", true);
                    cmd.set(row + " #LineHeading.TextSpans",
                            uiText(row + " #LineHeading.TextSpans", block.text()));
                }
                case PARAGRAPH -> {
                    cmd.set(row + " #LineParagraph.Visible", true);
                    cmd.set(row + " #LineParagraph.TextSpans",
                            uiText(row + " #LineParagraph.TextSpans", block.text()));
                }
                case BULLET -> {
                    cmd.set(row + " #LineBullet.Visible", true);
                    cmd.set(row + " #LineBulletText.TextSpans",
                            uiText(row + " #LineBulletText.TextSpans", block.text()));
                }
                case RULE -> cmd.set(row + " #LineRule.Visible", true);
                case LINK -> {
                    cmd.set(row + " #LineLink.Visible", true);
                    cmd.set(row + " #LineLink.TextSpans",
                            uiText(row + " #LineLink.TextSpans", block.text()));
                    event.addEventBinding(CustomUIEventBindingType.Activating, row + " #LineLink",
                            new EventData().put("action", "open").put("article", block.target()));
                }
            }
        }

        private void bindFooter(UIEventBuilder event) {
            event.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton",
                    new EventData().put("action", "back"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                    new EventData().put("action", "close"));
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            switch (string(payload, "action")) {
                case "open" -> reopen(ref, store, new ArticlePage(core, wiki, player,
                        field(payload, "article"), backCategory, backSearch));
                case "edit" -> {
                    if (wiki.canEdit(player)) {
                        reopen(ref, store, new EditorPage(core, wiki, player, articleId));
                    }
                }
                case "back" -> reopen(ref, store,
                        new BrowserPage(core, wiki, player, backCategory, backSearch));
                case "close" -> close(ref, store);
                default -> {
                    // Unknown action: leave the page as it is.
                }
            }
        }
    }

    // ----- Editor ------------------------------------------------------------

    /**
     * Create or change a stored page.
     *
     * <p>The id is fixed once the page exists: changing it would silently break
     * every {@code [[link]]} pointing at it, so a rename is a new page plus a
     * delete, which at least leaves the author looking at both.</p>
     */
    static final class EditorPage extends MysticPage {

        private final WikiModule wiki;
        private final String articleId;
        private final String status;

        EditorPage(MysticCore core, WikiModule wiki, PlayerRef player, String articleId) {
            this(core, wiki, player, articleId, "");
        }

        EditorPage(MysticCore core, WikiModule wiki, PlayerRef player, String articleId, String status) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.wiki = wiki;
            this.articleId = WikiModule.key(articleId);
            this.status = status == null ? "" : status;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(EDITOR_UI);
            WikiArticle article = wiki.stored(articleId).orElse(null);
            boolean existing = article != null;

            cmd.set("#EditorId.TextSpans", uiText("#EditorId.TextSpans",
                    articleId.isEmpty() ? "New page" : articleId));
            cmd.set("#TitleInput.Value", existing ? article.displayTitle() : "");
            cmd.set("#CategoryInput.Value", existing ? article.displayCategory() : "General");
            cmd.set("#SummaryInput.Value", existing && article.summary != null ? article.summary : "");
            cmd.set("#TagsInput.Value",
                    existing && article.tags != null ? String.join(", ", article.tags) : "");
            cmd.set("#PermissionInput.Value",
                    existing && article.viewPermission != null ? article.viewPermission : "");
            cmd.set("#BodyInput.Value", existing && article.body != null ? article.body : "");
            cmd.set("#EditorStatus.TextSpans", uiText("#EditorStatus.TextSpans",
                    status.isBlank()
                            ? (articleId.isEmpty()
                                    ? "Give the page an id in the title field — it becomes the [[link]] name."
                                    : "Editing " + articleId + ".")
                            : status));

            cmd.set("#DeleteButton.Visible", existing);
            if (existing) {
                event.addEventBinding(CustomUIEventBindingType.Activating, "#DeleteButton",
                        new EventData().put("action", "delete"));
            }

            // Every field travels with the Save press: the page is rebuilt on
            // each press, so anything not sent now is lost.
            event.addEventBinding(CustomUIEventBindingType.Activating, "#SaveButton",
                    new EventData().put("action", "save")
                            .append("@title", "#TitleInput.Value")
                            .append("@category", "#CategoryInput.Value")
                            .append("@summary", "#SummaryInput.Value")
                            .append("@tags", "#TagsInput.Value")
                            .append("@permission", "#PermissionInput.Value")
                            .append("@body", "#BodyInput.Value"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#CancelButton",
                    new EventData().put("action", "back"));
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            if (!wiki.canEdit(player)) {
                close(ref, store);
                return;
            }
            switch (string(payload, "action")) {
                case "save" -> save(ref, store, payload);
                case "delete" -> {
                    wiki.delete(articleId);
                    reopen(ref, store, new BrowserPage(core, wiki, player));
                }
                case "back" -> reopen(ref, store, articleId.isEmpty()
                        ? new BrowserPage(core, wiki, player)
                        : new ArticlePage(core, wiki, player, articleId));
                default -> {
                    // Unknown action: leave the page as it is.
                }
            }
        }

        private void save(Ref<EntityStore> ref, Store<EntityStore> store, JsonObject payload) {
            String title = field(payload, "title").trim();
            if (title.isEmpty()) {
                reopen(ref, store, new EditorPage(core, wiki, player, articleId,
                        "Give the page a title before saving."));
                return;
            }
            // A new page takes its id from the title, slugged; an existing one
            // keeps the id every [[link]] already points at.
            String id = articleId.isEmpty() ? slug(title) : articleId;
            WikiArticle article = wiki.stored(id).orElseGet(WikiArticle::new);
            article.id = id;
            article.title = title;
            article.category = blankTo(field(payload, "category").trim(), "General");
            article.summary = field(payload, "summary").trim();
            article.viewPermission = field(payload, "permission").trim();
            article.body = field(payload, "body");
            article.tags = Arrays.stream(field(payload, "tags").split(","))
                    .map(String::trim).filter(tag -> !tag.isEmpty()).toList();

            if (!wiki.put(player, article)) {
                reopen(ref, store, new EditorPage(core, wiki, player, articleId,
                        "That id is not usable, or another mod already owns it."));
                return;
            }
            reopen(ref, store, new ArticlePage(core, wiki, player, id));
        }

        /** Turns a title into an id: lowercase, words joined with hyphens. */
        private static String slug(String title) {
            String slug = title.toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("(^-+)|(-+$)", "");
            return slug.length() > 64 ? slug.substring(0, 64) : slug;
        }

        private static String blankTo(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }
}
