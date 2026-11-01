package org.hyzionstudios.mysticessentials.modules.wiki;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.api.wiki.WikiEntry;
import org.hyzionstudios.mysticessentials.api.wiki.WikiService;
import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * The built-in server wiki: pages staff write in game, alongside pages other
 * mods contribute at runtime.
 *
 * <h2>Two kinds of page, one browser</h2>
 *
 * <p>Stored articles are owned by Essentials and persisted to
 * {@code data/modules/wiki/articles.json}. Contributed entries belong to
 * whoever registered them and are rendered from their owner on every open, so
 * a mod's reference never goes stale and an uninstalled mod's pages leave with
 * it. They share one id space and one {@code [[link]]} syntax, which is what
 * makes "link other mods' information in-game" work in both directions: a
 * staff page can link into MysticGuilds' reference, and MysticGuilds can link
 * back to the server's own rules page.</p>
 *
 * <p>Every mutation goes through this class on the caller's thread and the
 * article map is guarded by the module's monitor: pages are edited from world
 * threads, listed from command threads, and searched from Netty during tab
 * completion.</p>
 */
public final class WikiModule extends AbstractMysticModule implements WikiService {

    private static final Type ARTICLE_MAP_TYPE = new TypeToken<LinkedHashMap<String, WikiArticle>>() {
    }.getType();

    /** Stored articles by id. Guarded by this module's monitor. */
    private Map<String, WikiArticle> articles = new LinkedHashMap<>();

    /** Pages other mods registered, in registration order. */
    private final Map<String, WikiEntry> entries = new LinkedHashMap<>();

    private WikiConfig config = new WikiConfig();

    public WikiModule() {
        super("wiki", "Server Wiki", "1.0.0");
    }

    @Override
    public void onEnable() {
        config = core.configManager().loadModuleConfig(id(), WikiConfig.class, new WikiConfig());
        synchronized (this) {
            articles = load();
            if (config.seedStarterArticle && articles.isEmpty()) {
                seed();
            }
        }
        registerCommand(new WikiCommand());
        log("Enabled: " + articles.size() + " stored articles");
    }

    @Override
    public void onReload() {
        config = core.configManager().loadModuleConfig(id(), WikiConfig.class, new WikiConfig());
        synchronized (this) {
            // Re-read rather than write first: a file edited behind the
            // module's back is a supported way to author pages, and every edit
            // made in game was already saved as it was made.
            articles = load();
        }
    }

    @Override
    public void onDisable() {
        save();
        entries.clear();
    }

    WikiConfig config() {
        return config;
    }

    // ----- Storage -----------------------------------------------------------

    private Path articleFile() {
        return core.paths().moduleDataDir(id()).resolve("articles.json");
    }

    private Map<String, WikiArticle> load() {
        try {
            JsonElement element = Json.readFile(articleFile());
            Map<String, WikiArticle> loaded = element == null ? null
                    : Json.gson().fromJson(element, ARTICLE_MAP_TYPE);
            if (loaded == null) {
                return new LinkedHashMap<>();
            }
            // A hand-edited file can disagree with itself about ids; the map
            // key wins, so /wiki <id> always finds what the browser listed.
            loaded.forEach((key, article) -> {
                if (article != null) {
                    article.id = key;
                }
            });
            loaded.values().removeIf(java.util.Objects::isNull);
            return loaded;
        } catch (Exception e) {
            log("Failed to load wiki articles: " + e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private synchronized void save() {
        try {
            Json.writeFile(articleFile(), articles);
        } catch (Exception e) {
            log("Failed to save wiki articles: " + e.getMessage());
        }
    }

    private void seed() {
        WikiArticle welcome = new WikiArticle("welcome", "Welcome", "Getting Started",
                "Start here.",
                """
                ## Welcome
                This is the server wiki. Anything staff want players to know lives here.

                ## Editing
                Staff with the wiki edit permission can press Edit on any page, or run
                /wiki new <id> to start one. Pages use a small markup:

                - A line starting with ## is a heading
                - A line starting with - is a bullet
                - A blank line starts a new paragraph
                - [[another-page]] links to another page

                ## Other mods
                Mods on this server can add their own pages. Those appear in the list
                alongside these and stay up to date on their own.
                """);
        welcome.order = 1;
        articles.put(welcome.id, welcome);
        save();
    }

    // ----- WikiService: contributed entries ----------------------------------

    @Override
    public boolean registerEntry(WikiEntry entry) {
        if (entry == null) {
            return false;
        }
        entries.put(key(entry.id()), entry);
        return true;
    }

    @Override
    public boolean unregisterEntry(String id) {
        return id != null && entries.remove(key(id)) != null;
    }

    @Override
    public int unregisterNamespace(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return 0;
        }
        String head = key(prefix).endsWith(":") ? key(prefix) : key(prefix) + ":";
        int before = entries.size();
        entries.keySet().removeIf(id -> id.startsWith(head));
        return before - entries.size();
    }

    @Override
    public List<WikiEntry> entries() {
        return List.copyOf(entries.values());
    }

    // ----- WikiService: reading ----------------------------------------------

    @Override
    public void open(PlayerRef player) {
        core.platform().openPage(player, new WikiPages.ReaderPage(core, this, player, null, "", ""));
    }

    @Override
    public void open(PlayerRef player, String articleId) {
        if (page(player, articleId).isEmpty()) {
            open(player);
            return;
        }
        core.platform().openPage(player, new WikiPages.ReaderPage(core, this, player, null, "", key(articleId)));
    }

    @Override
    public List<String> categories(PlayerRef player) {
        return categoriesOf(pages(player));
    }

    /**
     * Category order for an already-resolved page list: the configured order
     * first, then anything else alphabetically, so a new category shows up
     * without a config edit.
     *
     * <p>Takes the list rather than fetching it because {@link #pages} renders
     * every contributed entry's body — calling it once per category would run
     * other mods' suppliers dozens of times for one browser open.</p>
     */
    List<String> categoriesOf(List<WikiPage> pages) {
        List<String> configured = config.categoryOrder == null ? List.of() : config.categoryOrder;
        List<String> ordered = new ArrayList<>();
        TreeSet<String> rest = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        TreeSet<String> present = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (WikiPage page : pages) {
            present.add(page.category());
            if (!containsIgnoreCase(configured, page.category())) {
                rest.add(page.category());
            }
        }
        for (String category : configured) {
            if (present.contains(category)) {
                ordered.add(category);
            }
        }
        ordered.addAll(rest);
        return ordered;
    }

    private static boolean containsIgnoreCase(List<String> values, String needle) {
        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(needle)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Optional<String> body(PlayerRef player, String articleId) {
        return page(player, articleId).map(WikiPage::body);
    }

    @Override
    public boolean canEdit(PlayerRef player) {
        return player.hasPermission(Permissions.WIKI_EDIT);
    }

    // ----- The merged view ---------------------------------------------------

    /**
     * One page, whichever kind it is. The browser and reader work entirely in
     * terms of this, so a contributed page and a stored one are the same thing
     * to everything above storage.
     *
     * @param stored whether it can be edited here
     */
    record WikiPage(String id, String title, String category, String summary, String body,
            List<String> tags, int order, boolean stored, String footer, String icon) {
    }

    /** @return every page this player may read, ordered by category then order then title. */
    List<WikiPage> pages(PlayerRef player) {
        List<WikiPage> pages = new ArrayList<>();
        synchronized (this) {
            for (WikiArticle article : articles.values()) {
                if (!canRead(player, article)) {
                    continue;
                }
                pages.add(toPage(article));
            }
        }
        if (config.includeModEntries) {
            for (WikiEntry entry : entries.values()) {
                WikiPage page = toPage(player, entry);
                if (page != null) {
                    pages.add(page);
                }
            }
        }
        pages.sort(Comparator.comparing(WikiPage::category, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(WikiPage::order)
                .thenComparing(WikiPage::title, String.CASE_INSENSITIVE_ORDER));
        return pages;
    }

    /** @return one page by id, if this player may read it. */
    Optional<WikiPage> page(PlayerRef player, String articleId) {
        String id = key(articleId);
        if (id.isEmpty()) {
            return Optional.empty();
        }
        synchronized (this) {
            WikiArticle article = articles.get(id);
            if (article != null) {
                return canRead(player, article) ? Optional.of(toPage(article)) : Optional.empty();
            }
        }
        if (!config.includeModEntries) {
            return Optional.empty();
        }
        WikiEntry entry = entries.get(id);
        return entry == null ? Optional.empty() : Optional.ofNullable(toPage(player, entry));
    }

    private WikiPage toPage(WikiArticle article) {
        return new WikiPage(article.id, article.displayTitle(), article.displayCategory(),
                article.summary == null ? "" : article.summary,
                article.body == null ? "" : article.body,
                article.tags == null ? List.of() : List.copyOf(article.tags),
                article.order, true, footer(article), article.icon == null ? "" : article.icon);
    }

    /**
     * Renders a contributed entry. The owner's suppliers run inside our page
     * build, so a mod that throws costs its own page and nothing else.
     */
    private WikiPage toPage(PlayerRef player, WikiEntry entry) {
        try {
            if (!entry.visibleTo().test(player)) {
                return null;
            }
            String body = entry.body().apply(player);
            return new WikiPage(key(entry.id()), entry.title(), entry.category(), entry.summary(),
                    body == null ? "" : body, entry.tags(), entry.order(), false,
                    "Provided by another mod", "");
        } catch (Throwable t) {
            core.log(java.util.logging.Level.WARNING,
                    "Wiki entry '" + entry.id() + "' failed to render: " + t);
            return null;
        }
    }

    private static String footer(WikiArticle article) {
        if (article.updatedAt <= 0) {
            return "Never edited";
        }
        String who = article.updatedBy == null || article.updatedBy.isBlank() ? "someone"
                : article.updatedBy;
        return "Last edited by " + who + " on "
                + Instant.ofEpochMilli(article.updatedAt).toString().substring(0, 10);
    }

    private boolean canRead(PlayerRef player, WikiArticle article) {
        if (article.viewPermission == null || article.viewPermission.isBlank()) {
            return true;
        }
        return player.hasPermission(article.viewPermission);
    }

    // ----- Editing -----------------------------------------------------------

    /** @return the stored article with this id, for the editor. */
    synchronized Optional<WikiArticle> stored(String articleId) {
        return Optional.ofNullable(articles.get(key(articleId)));
    }

    /**
     * Creates or replaces a stored article.
     *
     * @return false when the id is unusable, or when it is already taken by a
     *         contributed entry — Essentials will not shadow another mod's page
     */
    boolean put(PlayerRef editor, WikiArticle article) {
        String id = key(article.id);
        if (id.isEmpty() || !id.matches("[a-z0-9][a-z0-9.:-]{0,63}")) {
            return false;
        }
        if (entries.containsKey(id)) {
            return false;
        }
        article.id = id;
        article.updatedBy = editor == null ? "console" : editor.getUsername();
        article.updatedAt = System.currentTimeMillis();
        synchronized (this) {
            articles.put(id, article);
        }
        save();
        return true;
    }

    /** @return whether a stored article was removed. */
    boolean delete(String articleId) {
        boolean removed;
        synchronized (this) {
            removed = articles.remove(key(articleId)) != null;
        }
        if (removed) {
            save();
        }
        return removed;
    }

    /** @return stored article ids, for tab completion. */
    synchronized List<String> storedIds() {
        return List.copyOf(articles.keySet());
    }

    static String key(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    /** Whether a page matches a search box, over title, summary, tags and body. */
    static boolean matches(WikiPage page, String search) {
        if (search == null || search.isBlank()) {
            return true;
        }
        String needle = search.toLowerCase(Locale.ROOT).trim();
        if (page.id().contains(needle)
                || page.title().toLowerCase(Locale.ROOT).contains(needle)
                || page.summary().toLowerCase(Locale.ROOT).contains(needle)
                || page.category().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        for (String tag : page.tags()) {
            if (tag != null && tag.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return WikiMarkup.plain(page.body()).toLowerCase(Locale.ROOT).contains(needle);
    }

    // ----- Command -----------------------------------------------------------

    /**
     * {@code /wiki} opens the browser. {@code /wiki <id>} opens a page,
     * {@code /wiki new <id>} and {@code /wiki edit <id>} open the editor, and
     * {@code /wiki delete <id>} removes a stored page. Free-form arguments so
     * an article id can be anything the browser shows without having to be a
     * registered subcommand.
     */
    private final class WikiCommand extends MysticCommand {
        WikiCommand() {
            super(WikiModule.this.core, "wiki", "Open the server wiki.");
            addAliases("codex");
            requirePermission(Permissions.WIKI_USE);
            allowExtraArguments();
        }

        @Override
        protected void run(MysticCommandSender sender) {
            Optional<PlayerRef> viewer = sender.player();
            if (viewer.isEmpty()) {
                sender.reply("&cThe wiki is for players.");
                return;
            }
            PlayerRef player = viewer.get();
            String first = sender.arg(0).map(WikiModule::key).orElse("");
            String second = sender.arg(1).map(WikiModule::key).orElse("");

            switch (first) {
                case "" -> open(player);
                case "new", "edit" -> {
                    if (!canEdit(player)) {
                        sender.reply("&cYou cannot edit the wiki.");
                        return;
                    }
                    if (second.isEmpty()) {
                        sender.reply("&cUsage: /wiki " + first + " <id>");
                        return;
                    }
                    core.platform().openPage(player,
                            new WikiPages.EditorPage(core, WikiModule.this, player, second));
                }
                case "delete", "remove" -> {
                    if (!canEdit(player)) {
                        sender.reply("&cYou cannot edit the wiki.");
                        return;
                    }
                    sender.reply(delete(second) ? "&7Deleted &f" + second + "&7."
                            : "&cNo stored article called " + second + ".");
                }
                default -> {
                    if (page(player, first).isPresent()) {
                        open(player, first);
                        return;
                    }
                    // Not an id: treat the whole line as a search, which is
                    // what someone typing /wiki how do i claim land meant.
                    core.platform().openPage(player, new WikiPages.ReaderPage(
                            core, WikiModule.this, player, null, sender.argString(), ""));
                }
            }
        }
    }
}
