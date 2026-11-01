package org.hyzionstudios.mysticessentials.modules.wiki;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.wiki.WikiModule.WikiPage;
import org.hyzionstudios.mysticessentials.platform.ui.ItemCatalog;
import org.hyzionstudios.mysticessentials.platform.ui.MysticPage;
import org.hyzionstudios.mysticessentials.platform.ui.TextureIndex;
import org.hyzionstudios.mysticessentials.platform.ui.VanillaIcons;

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

/** The wiki's two windows: the reader (find and read) and the editor (write and preview). */
final class WikiPages {

    static final String READER_UI = "MysticEssentials/Wiki.ui";
    static final String CATEGORY_ROW_UI = "MysticEssentials/WikiCategoryRow.ui";
    static final String ARTICLE_ROW_UI = "MysticEssentials/WikiRow.ui";
    static final String RELATED_UI = "MysticEssentials/WikiRelatedLink.ui";
    static final String EDITOR_UI = "MysticEssentials/WikiEditor.ui";
    static final String ICON_TILE_UI = "MysticEssentials/RailEditorIconTile.ui";
    static final String ITEM_TILE_UI = "MysticEssentials/WikiItemTile.ui";

    /** Link buttons under a page; four fit across the reading column. */
    private static final int MAX_RELATED = 4;

    private WikiPages() {
    }

    // ----- Reader ------------------------------------------------------------

    /**
     * Search, categories and pages on the left; the open page on the right.
     *
     * <p>A category of {@code null} means "everything", which is what search
     * needs: typing a word looks across the whole wiki, not inside whichever
     * category happened to be selected. Typing updates only the page list —
     * rebuilding the window per keystroke would take the box's focus away.</p>
     */
    static final class ReaderPage extends MysticPage {

        private final WikiModule wiki;
        private final String category;
        private final String search;
        private final String articleId;

        ReaderPage(MysticCore core, WikiModule wiki, PlayerRef player, String category, String search,
                String articleId) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.wiki = wiki;
            this.category = category;
            this.search = search == null ? "" : search;
            this.articleId = WikiModule.key(articleId);
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(READER_UI);
            WikiConfig config = wiki.config();
            cmd.set("#WikiSubtitle.TextSpans", uiText("#WikiSubtitle.TextSpans", config.subtitle));
            cmd.set("#SearchInput.Value", search);

            List<WikiPage> all = wiki.pages(player);
            List<WikiPage> visible = filter(all, search);
            WikiPage open = articleId.isEmpty()
                    ? (visible.isEmpty() ? null : visible.get(0))
                    : wiki.page(player, articleId).orElse(null);

            cmd.set("#WikiCount.TextSpans", uiText("#WikiCount.TextSpans",
                    all.size() == 1 ? "1 page" : all.size() + " pages"));
            renderCategories(cmd, event, all, wiki.categoriesOf(all));
            renderList(cmd, event, visible, open == null ? "" : open.id());
            renderArticle(cmd, event, open);

            boolean canEdit = wiki.canEdit(player);
            cmd.set("#NewArticleButton.Visible", canEdit);
            if (canEdit) {
                event.addEventBinding(CustomUIEventBindingType.Activating, "#NewArticleButton",
                        new EventData().put("action", "new"));
            }
            // Not locking, and answered with a list-only update: see the class note.
            event.addEventBinding(CustomUIEventBindingType.ValueChanged, "#SearchInput",
                    new EventData().put("action", "search").append("@search", "#SearchInput.Value"), false);
            event.addEventBinding(CustomUIEventBindingType.Activating, "#MenuButton",
                    new EventData().put("action", "menu"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                    new EventData().put("action", "close"));
        }

        private List<WikiPage> filter(List<WikiPage> all, String query) {
            List<WikiPage> visible = new ArrayList<>();
            for (WikiPage page : all) {
                boolean keep = query.isBlank()
                        ? category == null || WikiCategories.within(page.category(), category)
                        : WikiModule.matches(page, query);
                if (keep) {
                    visible.add(page);
                }
            }
            return visible;
        }

        /**
         * "All Pages", then the category tree: the top level always, and the
         * sub-categories of every node on the way to the selected one.
         */
        private void renderCategories(UICommandBuilder cmd, UIEventBuilder event, List<WikiPage> all,
                List<String> categories) {
            List<String> pageCategories = new ArrayList<>(all.size());
            for (WikiPage page : all) {
                pageCategories.add(page.category());
            }
            categoryRow(cmd, event, 0, "", "All Pages", 0, all.size(), "", category == null);
            int index = 1;
            for (WikiCategories.Node node : WikiCategories.rows(categories, pageCategories, category)) {
                String arrow = !node.hasChildren() ? "" : node.open() ? "–" : "+";
                categoryRow(cmd, event, index++, node.path(), node.name(), node.depth(), node.count(), arrow,
                        node.selected());
            }
        }

        private void categoryRow(UICommandBuilder cmd, UIEventBuilder event, int index, String path, String name,
                int depth, int count, String arrow, boolean selected) {
            String row = "#CategoryList[" + index + "]";
            cmd.append("#CategoryList", CATEGORY_ROW_UI);
            for (int level = 1; level <= Math.min(depth, WikiCategories.MAX_INDENT); level++) {
                cmd.set(row + " #Indent" + level + ".Visible", true);
            }
            cmd.set(row + " #CategoryArrow.TextSpans", uiText(row + " #CategoryArrow.TextSpans", arrow));
            cmd.set(row + " #CategoryName.TextSpans", uiText(row + " #CategoryName.TextSpans", name));
            cmd.set(row + " #CategoryCount.TextSpans", uiText(row + " #CategoryCount.TextSpans", String.valueOf(count)));
            cmd.set(row + " #CategorySelected.Visible", selected);
            event.addEventBinding(CustomUIEventBindingType.Activating, row,
                    new EventData().put("action", "category").put("category", path));
        }

        private void renderList(UICommandBuilder cmd, UIEventBuilder event, List<WikiPage> visible, String openId) {
            List<WikiPage> rows = capRows(visible);
            for (int i = 0; i < rows.size(); i++) {
                WikiPage page = rows.get(i);
                String row = "#ArticleList[" + i + "]";
                cmd.append("#ArticleList", ARTICLE_ROW_UI);
                WikiRenderer.pageIcon(cmd, row + " #WikiRowIcon", row + " #WikiRowItem", page.icon());
                cmd.set(row + " #WikiRowTitle.TextSpans", uiText(row + " #WikiRowTitle.TextSpans", page.title()));
                cmd.set(row + " #WikiRowSummary.TextSpans", uiText(row + " #WikiRowSummary.TextSpans",
                        page.summary().isBlank() ? WikiCategories.display(page.category()) : page.summary()));
                cmd.set(row + " #WikiRowSource.TextSpans",
                        uiText(row + " #WikiRowSource.TextSpans", page.stored() ? "" : "Mod"));
                cmd.set(row + " #WikiRowSelected.Visible", page.id().equals(openId));
                event.addEventBinding(CustomUIEventBindingType.Activating, row,
                        new EventData().put("action", "open").put("article", page.id()));
            }
            cmd.set("#WikiEmpty.Visible", visible.isEmpty());
            if (visible.isEmpty()) {
                cmd.set("#WikiEmpty.TextSpans", uiText("#WikiEmpty.TextSpans",
                        search.isBlank() ? "Nothing has been written here yet." : "Nothing matches that search."));
            }
        }

        private void renderArticle(UICommandBuilder cmd, UIEventBuilder event, WikiPage page) {
            if (page == null) {
                WikiRenderer.pageIcon(cmd, "#ArticleIcon", "#ArticleItem", "");
                cmd.set("#ArticleTitle.TextSpans", uiText("#ArticleTitle.TextSpans",
                        articleId.isEmpty() ? "Server Wiki" : "Not found"));
                cmd.set("#ArticleSummary.TextSpans", uiText("#ArticleSummary.TextSpans", articleId.isEmpty()
                        ? "Pick a page on the left." : "There is no page called \"" + articleId + "\"."));
                cmd.set("#ArticleEmpty.Visible", true);
                return;
            }
            WikiRenderer.pageIcon(cmd, "#ArticleIcon", "#ArticleItem", page.icon());
            cmd.set("#ArticleTitle.TextSpans", uiText("#ArticleTitle.TextSpans", page.title()));
            cmd.set("#ArticleSummary.TextSpans", uiText("#ArticleSummary.TextSpans", page.summary()));
            cmd.set("#ArticleCategory.TextSpans", uiText("#ArticleCategory.TextSpans",
                    WikiCategories.display(page.category())));
            cmd.set("#ArticleFooter.TextSpans", uiText("#ArticleFooter.TextSpans", page.footer()));

            // A very long article is one very large packet; draw the first
            // screens and say the rest is there.
            List<WikiMarkup.Block> parsed = WikiRenderer.expand(WikiMarkup.parse(page.body()), wiki, player, page.id());
            List<WikiMarkup.Block> blocks = capRows(parsed);
            cmd.set("#ArticleEmpty.Visible", parsed.isEmpty());
            WikiRenderer.render(cmd, event, "#ArticleBody", blocks);
            if (blocks.size() < parsed.size()) {
                cmd.set("#ArticleFooter.TextSpans", uiText("#ArticleFooter.TextSpans",
                        "First " + blocks.size() + " of " + parsed.size() + " lines · " + page.footer()));
            }

            Map<String, String> related = WikiMarkup.relatedLinks(page.body());
            cmd.set("#RelatedSection.Visible", !related.isEmpty());
            int index = 0;
            for (Map.Entry<String, String> link : related.entrySet()) {
                if (index >= MAX_RELATED) {
                    break;
                }
                String row = "#RelatedList[" + index++ + "]";
                cmd.append("#RelatedList", RELATED_UI);
                cmd.set(row + " #RelatedLink.TextSpans", uiText(row + " #RelatedLink.TextSpans", link.getValue()));
                event.addEventBinding(CustomUIEventBindingType.Activating, row + " #RelatedLink",
                        new EventData().put("action", "link").put("target", link.getKey()));
            }

            // Only stored pages are editable here; a mod's page belongs to the mod that renders it.
            boolean editable = page.stored() && wiki.canEdit(player);
            cmd.set("#EditButton.Visible", editable);
            if (editable) {
                event.addEventBinding(CustomUIEventBindingType.Activating, "#EditButton",
                        new EventData().put("action", "edit").put("article", page.id()));
            }
        }

        @Override
        protected void onEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            switch (string(payload, "action")) {
                case "category" -> {
                    String picked = field(payload, "category");
                    reopen(ref, store, new ReaderPage(core, wiki, player, picked.isEmpty() ? null : picked, "", ""));
                }
                case "search" -> {
                    // The list only; the box keeps its focus and what was typed.
                    String query = field(payload, "search");
                    UICommandBuilder cmd = new UICommandBuilder();
                    UIEventBuilder event = new UIEventBuilder();
                    cmd.clear("#ArticleList");
                    renderList(cmd, event, filter(wiki.pages(player), query), articleId);
                    sendUpdate(cmd, event);
                }
                case "open" -> reopen(ref, store,
                        new ReaderPage(core, wiki, player, category, search, field(payload, "article")));
                case "link" -> follow(ref, store, field(payload, "target"));
                case "edit" -> {
                    if (wiki.canEdit(player)) {
                        reopen(ref, store, new EditorPage(core, wiki, player, field(payload, "article")));
                    }
                }
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

        /**
         * A page id opens here; a command runs as the reader, under their own
         * permissions, after the window closes; a web address goes to chat as a
         * clickable line, because a game window cannot open a browser.
         */
        private void follow(Ref<EntityStore> ref, Store<EntityStore> store, String target) {
            switch (WikiMarkup.Target.of(target)) {
                case PAGE -> reopen(ref, store, new ReaderPage(core, wiki, player, category, search, target));
                case COMMAND -> {
                    close(ref, store);
                    core.platform().dispatchPlayerCommand(player, target.substring(1));
                }
                case URL -> {
                    core.getMessageService().send(player, "&7Link: <link:" + target + "><#8fc2ec><u>" + target + "</u></#8fc2ec></link>");
                    UICommandBuilder cmd = new UICommandBuilder();
                    cmd.set("#ArticleFooter.TextSpans", uiText("#ArticleFooter.TextSpans", "&eLink sent to your chat"));
                    sendUpdate(cmd);
                }
            }
        }
    }

    // ----- Editor ------------------------------------------------------------

    /**
     * Create or change a stored page, with the rendered page beside it.
     *
     * <p>The id is fixed once the page exists: changing it would silently break
     * every {@code [[link]]} pointing at it, so a rename is a new page plus a
     * delete, which at least leaves the author looking at both.</p>
     *
     * <p>Only Save and Delete rebuild the window. Everything else — the
     * toolbar, the image search, the tabs, the preview — is a partial update,
     * so what is typed stays where it is.</p>
     */
    static final class EditorPage extends MysticPage {

        /** Preview refreshes no more often than this while typing. */
        static final long PREVIEW_DELAY_MS = 300;
        /** Image search results shown at once; a search narrows the rest. */
        static final int MAX_RESULTS = 120;

        private final WikiModule wiki;
        private final String articleId;
        /** The latest body the client sent; the debounced preview draws it. */
        private String pendingBody;
        private boolean previewScheduled;
        /** The image search's filter and text; both live only while the window is open. */
        private TextureIndex.Source source = TextureIndex.Source.ICONS;
        private String query = "";

        EditorPage(MysticCore core, WikiModule wiki, PlayerRef player, String articleId) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.wiki = wiki;
            this.articleId = WikiModule.key(articleId);
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(EDITOR_UI);
            WikiArticle article = wiki.stored(articleId).orElse(null);
            boolean existing = article != null;
            String body = existing && article.body != null ? article.body : "";

            cmd.set("#EditorId.TextSpans", uiText("#EditorId.TextSpans", articleId.isEmpty() ? "New page" : articleId));
            cmd.set("#TitleInput.Value", existing ? article.displayTitle() : "");
            cmd.set("#CategoryInput.Value", existing ? article.displayCategory() : "General");
            cmd.set("#SummaryInput.Value", existing && article.summary != null ? article.summary : "");
            cmd.set("#IconInput.Value", existing && article.icon != null ? article.icon : "");
            cmd.set("#TagsInput.Value", existing && article.tags != null ? String.join(", ", article.tags) : "");
            cmd.set("#PermissionInput.Value", existing && article.viewPermission != null ? article.viewPermission : "");
            cmd.set("#BodyInput.Value", body);
            cmd.set("#EditorStatus.TextSpans", uiText("#EditorStatus.TextSpans", articleId.isEmpty()
                    ? "The title becomes the page id, the name [[links]] use."
                    : "Editing " + articleId + ". Toolbar and pictures add to the end of the body."));
            tabs(cmd, true);
            drawPreview(cmd, body);
            drawResults(cmd, event);

            cmd.set("#DeleteButton.Visible", existing);
            if (existing) {
                event.addEventBinding(CustomUIEventBindingType.Activating, "#DeleteButton",
                        new EventData().put("action", "delete"));
            }
            bindInsert(event, "#InsertHeading", "\n## Heading\n");
            bindInsert(event, "#InsertSubheading", "\n### Subheading\n");
            bindInsert(event, "#InsertBullet", "\n- ");
            bindInsert(event, "#InsertQuote", "\n> ");
            bindInsert(event, "#InsertRule", "\n---\n");
            bindInsert(event, "#InsertLink", "\n[[page-id|Read more]]\n");
            bindInsert(event, "#InsertEmbed", "\n{{page:page-id}}\n");
            bindInsert(event, "#ColourGold", "&6");
            bindInsert(event, "#ColourBlue", "<#67a5da>");
            bindInsert(event, "#ColourGreen", "&a");
            bindInsert(event, "#ColourRed", "&c");
            bindInsert(event, "#ColourGrey", "&7");
            bindInsert(event, "#ColourReset", "&r");
            colourLabels(cmd);

            // Non-locking: these fire per keystroke, and the answers are partial updates.
            event.addEventBinding(CustomUIEventBindingType.ValueChanged, "#BodyInput",
                    new EventData().put("action", "body").append("@body", "#BodyInput.Value"), false);
            event.addEventBinding(CustomUIEventBindingType.ValueChanged, "#ImageSearch",
                    new EventData().put("action", "images").append("@query", "#ImageSearch.Value"), false);
            event.addEventBinding(CustomUIEventBindingType.Activating, "#PreviewTab",
                    new EventData().put("action", "tab").put("tab", "preview").append("@body", "#BodyInput.Value"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#ImagesTab",
                    new EventData().put("action", "tab").put("tab", "images"));
            for (TextureIndex.Source option : TextureIndex.Source.values()) {
                event.addEventBinding(CustomUIEventBindingType.Activating, sourceTab(option),
                        new EventData().put("action", "source").put("source", option.name()));
            }
            event.addEventBinding(CustomUIEventBindingType.Activating, "#RefreshPreview",
                    new EventData().put("action", "refresh").append("@body", "#BodyInput.Value"));
            // Every field travels with Save: anything not sent now is lost.
            event.addEventBinding(CustomUIEventBindingType.Activating, "#SaveButton",
                    form(new EventData().put("action", "save")));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#CancelButton",
                    new EventData().put("action", "back"));
        }

        private static EventData form(EventData data) {
            return data.append("@title", "#TitleInput.Value")
                    .append("@category", "#CategoryInput.Value")
                    .append("@summary", "#SummaryInput.Value")
                    .append("@icon", "#IconInput.Value")
                    .append("@tags", "#TagsInput.Value")
                    .append("@permission", "#PermissionInput.Value")
                    .append("@body", "#BodyInput.Value");
        }

        private void bindInsert(UIEventBuilder event, String button, String snippet) {
            event.addEventBinding(CustomUIEventBindingType.Activating, button,
                    new EventData().put("action", "insert").put("snippet", snippet).append("@body", "#BodyInput.Value"));
        }

        /** The colour buttons say their colour in it. */
        private static void colourLabels(UICommandBuilder cmd) {
            cmd.set("#ColourGold.TextSpans", uiText("#ColourGold.TextSpans", "&6Gold"));
            cmd.set("#ColourBlue.TextSpans", uiText("#ColourBlue.TextSpans", "<#67a5da>Blue"));
            cmd.set("#ColourGreen.TextSpans", uiText("#ColourGreen.TextSpans", "&aGreen"));
            cmd.set("#ColourRed.TextSpans", uiText("#ColourRed.TextSpans", "&cRed"));
            cmd.set("#ColourGrey.TextSpans", uiText("#ColourGrey.TextSpans", "&7Grey"));
            cmd.set("#ColourReset.TextSpans", uiText("#ColourReset.TextSpans", "&fPlain"));
        }

        private static void tabs(UICommandBuilder cmd, boolean preview) {
            cmd.set("#PreviewPane.Visible", preview);
            cmd.set("#ImagesPane.Visible", !preview);
            cmd.set("#PreviewTab.TextSpans", uiText("#PreviewTab.TextSpans", preview ? "&a> Preview" : "&7Preview"));
            cmd.set("#ImagesTab.TextSpans", uiText("#ImagesTab.TextSpans", preview ? "&7Images" : "&a> Images"));
        }

        /** Draws the body into the preview exactly as the reader would, links inert. */
        private void drawPreview(UICommandBuilder cmd, String body) {
            List<WikiMarkup.Block> blocks = capRows(WikiRenderer.expand(WikiMarkup.parse(body), wiki, player, articleId));
            cmd.set("#PreviewEmpty.Visible", blocks.isEmpty());
            WikiRenderer.render(cmd, null, "#PreviewBody", blocks);
        }

        /**
         * The image search. Icons: the server's own, Hyzion's, the game's
         * named picks and its other icons. Items: every item, as a slot. Models:
         * model renders, then the textures models, blocks and effects use. All:
         * every PNG the client has. Click adds it to the body; right-click makes
         * it the page's icon; hover shows its name or path.
         */
        private void drawResults(UICommandBuilder cmd, UIEventBuilder event) {
            for (TextureIndex.Source option : TextureIndex.Source.values()) {
                String label = option.name().charAt(0) + option.name().substring(1).toLowerCase(Locale.ROOT);
                cmd.set(sourceTab(option) + ".TextSpans", uiText(sourceTab(option) + ".TextSpans",
                        option == source ? "&a> " + label : "&7" + label));
            }
            String needle = query.trim();
            int shown = 0;
            if (source == TextureIndex.Source.ITEMS) {
                List<String> items = needle.isEmpty() ? List.of() : ItemCatalog.search(needle, MAX_RESULTS);
                for (String itemId : items) {
                    String tile = "#Results[" + shown++ + "]";
                    cmd.append("#Results", ITEM_TILE_UI);
                    cmd.set(tile + " #TileItem.ItemId", itemId);
                    cmd.set(tile + ".TooltipText", itemId);
                    bindPick(event, tile, "\n{{item:" + itemId + "}}\n", "item:" + itemId);
                }
            } else {
                for (VanillaIcons.Entry entry : TextureIndex.search(source, needle, MAX_RESULTS)) {
                    String tile = "#Results[" + shown++ + "]";
                    cmd.append("#Results", ICON_TILE_UI);
                    cmd.set(tile + " #TileIcon.AssetPath", entry.path());
                    cmd.set(tile + ".TooltipText", entry.name());
                    bindPick(event, tile, "\n{{image:" + entry.name() + "}}\n", entry.name());
                }
            }
            String hint;
            if (source == TextureIndex.Source.ITEMS && needle.isEmpty()) {
                hint = "Type an item's name or id. Click to add, right-click for the page icon.";
            } else if (shown == 0) {
                hint = "Nothing matches. Try part of a name: chest, sword, kweebec.";
            } else {
                hint = (shown >= MAX_RESULTS ? "First " + shown + " matches; type to narrow. " : shown + " found. ")
                        + "Click to add, right-click for the page icon, hover for the name.";
            }
            cmd.set("#ImageHint.TextSpans", uiText("#ImageHint.TextSpans", hint));
        }

        private static void bindPick(UIEventBuilder event, String tile, String snippet, String icon) {
            event.addEventBinding(CustomUIEventBindingType.Activating, tile, new EventData()
                    .put("action", "insert").put("snippet", snippet).append("@body", "#BodyInput.Value"));
            event.addEventBinding(CustomUIEventBindingType.RightClicking, tile,
                    new EventData().put("action", "icon").put("icon", icon));
        }

        private static String sourceTab(TextureIndex.Source option) {
            return switch (option) {
                case ICONS -> "#SourceIcons";
                case ITEMS -> "#SourceItems";
                case MODELS -> "#SourceModels";
                case ALL -> "#SourceAll";
            };
        }

        /** Redraws the results grid with fresh bindings, leaving the rest of the window alone. */
        private void refreshResults() {
            UICommandBuilder cmd = new UICommandBuilder();
            UIEventBuilder event = new UIEventBuilder();
            cmd.clear("#Results");
            drawResults(cmd, event);
            sendUpdate(cmd, event);
        }

        @Override
        protected void onEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            if (!wiki.canEdit(player)) {
                close(ref, store);
                return;
            }
            switch (string(payload, "action")) {
                case "body" -> schedulePreview(store, field(payload, "body"));
                case "refresh" -> {
                    UICommandBuilder cmd = new UICommandBuilder();
                    cmd.clear("#PreviewBody");
                    drawPreview(cmd, field(payload, "body"));
                    sendUpdate(cmd);
                }
                case "insert" -> {
                    String body = field(payload, "body") + string(payload, "snippet");
                    UICommandBuilder cmd = new UICommandBuilder();
                    cmd.set("#BodyInput.Value", body);
                    cmd.clear("#PreviewBody");
                    drawPreview(cmd, body);
                    sendUpdate(cmd);
                }
                case "icon" -> {
                    UICommandBuilder cmd = new UICommandBuilder();
                    cmd.set("#IconInput.Value", string(payload, "icon"));
                    cmd.set("#EditorStatus.TextSpans", uiText("#EditorStatus.TextSpans",
                            "Page icon set to " + string(payload, "icon") + ". Save to keep it."));
                    sendUpdate(cmd);
                }
                case "images" -> {
                    query = field(payload, "query");
                    refreshResults();
                }
                case "source" -> {
                    source = TextureIndex.Source.parse(string(payload, "source"));
                    refreshResults();
                }
                case "tab" -> {
                    boolean preview = "preview".equals(string(payload, "tab"));
                    UICommandBuilder cmd = new UICommandBuilder();
                    tabs(cmd, preview);
                    if (preview) {
                        cmd.clear("#PreviewBody");
                        drawPreview(cmd, field(payload, "body"));
                    }
                    sendUpdate(cmd);
                }
                case "save" -> save(ref, store, payload);
                case "delete" -> {
                    wiki.delete(articleId);
                    reopen(ref, store, new ReaderPage(core, wiki, player, null, "", ""));
                }
                case "back" -> reopen(ref, store, new ReaderPage(core, wiki, player, null, "", articleId));
                default -> {
                    // Unknown action: leave the page as it is.
                }
            }
        }

        /**
         * Coalesces keystrokes: the first schedules a draw, later ones only
         * replace what it will draw. Runs on the world thread both ways.
         */
        private void schedulePreview(Store<EntityStore> store, String body) {
            pendingBody = body;
            if (previewScheduled) {
                return;
            }
            previewScheduled = true;
            var world = store.getExternalData().getWorld();
            core.scheduler().runLater(() -> world.execute(() -> {
                previewScheduled = false;
                if (!isCurrent()) {
                    return;
                }
                UICommandBuilder cmd = new UICommandBuilder();
                cmd.clear("#PreviewBody");
                drawPreview(cmd, pendingBody);
                sendUpdate(cmd);
            }), PREVIEW_DELAY_MS, TimeUnit.MILLISECONDS);
        }

        private boolean isCurrent() {
            Ref<EntityStore> ref = player.getReference();
            if (ref == null) {
                return false;
            }
            var entity = ref.getStore().getComponent(ref,
                    com.hypixel.hytale.server.core.entity.entities.Player.getComponentType());
            return entity != null && entity.getPageManager().getCustomPage() == this;
        }

        private void save(Ref<EntityStore> ref, Store<EntityStore> store, JsonObject payload) {
            String title = field(payload, "title").trim();
            if (title.isEmpty()) {
                status("&cGive the page a title before saving.");
                return;
            }
            // A new page takes its id from the title, slugged; an existing one
            // keeps the id every [[link]] already points at.
            String id = articleId.isEmpty() ? slug(title) : articleId;
            WikiArticle article = wiki.stored(id).orElseGet(WikiArticle::new);
            article.id = id;
            article.title = title;
            article.category = WikiCategories.normalize(field(payload, "category"));
            article.summary = field(payload, "summary").trim();
            article.icon = field(payload, "icon").trim();
            article.viewPermission = field(payload, "permission").trim();
            article.body = field(payload, "body");
            article.tags = Arrays.stream(field(payload, "tags").split(","))
                    .map(String::trim).filter(tag -> !tag.isEmpty()).toList();

            if (!wiki.put(player, article)) {
                status("&cThat id is not usable, or another mod already owns it.");
                return;
            }
            reopen(ref, store, new ReaderPage(core, wiki, player, null, "", id));
        }

        /** Says something under the body without rebuilding the window (which would lose the typing). */
        private void status(String message) {
            UICommandBuilder cmd = new UICommandBuilder();
            cmd.set("#EditorStatus.TextSpans", uiText("#EditorStatus.TextSpans", message));
            sendUpdate(cmd);
        }

        /** Turns a title into an id: lowercase, words joined with hyphens. */
        private static String slug(String title) {
            String slug = title.toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("(^-+)|(-+$)", "");
            return slug.length() > 64 ? slug.substring(0, 64) : slug;
        }
    }
}
