package org.hyzionstudios.mysticessentials.modules.wiki;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.stream.Stream;

import org.hyzionstudios.mysticessentials.api.wiki.WikiEntry;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.platform.ui.HyzionIcons;
import org.hyzionstudios.mysticessentials.platform.ui.ItemCatalog;

import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.common.plugin.AuthorInfo;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * A wiki category for every other mod on the server.
 *
 * <p>Each mod files under {@code Mods/<its name>} with an overview page,
 * written fresh for each reader from what the server knows right now: the
 * manifest's description, version and authors, and the commands the mod
 * registered that this reader may run. Nothing is keyed on a version, so an
 * update to a mod updates its page.</p>
 *
 * <h2>Defer, or show</h2>
 *
 * <p>Some mods carry a guide of their own — Endgame &amp; QoL's wiki and
 * journal, the Mermaids menu, Tamework's Companion Guide. Copying those would
 * only make a second, staler version, so the overview sends the reader to
 * the original with a button that runs the command opening it (shown only
 * while that command exists). A mod that ships Markdown documentation in its
 * asset pack ({@link ModDocs}) has it shown here as pages, nested the way its
 * authors arranged it.</p>
 */
final class ModWikiPages {

    static final String NAMESPACE = "mods";
    /** Commands listed per mod; a mod with more lists these and says so. */
    private static final int MAX_COMMANDS = 30;
    /** Subcommand names shown after a command. */
    private static final int MAX_SUBCOMMANDS = 8;

    /** A way into a mod's own guide: a command that opens it, or a sentence saying where it is. */
    private record GuideLink(String label, String command) {
    }

    private record Guide(String text, String item, List<GuideLink> links) {
    }

    /**
     * Mods known to have an in-game guide of their own, by plugin id. The text
     * says where it is; a link is drawn only while its command is registered.
     */
    private static final Map<String, Guide> GUIDES = Map.of(
            "config:endgame&qol", new Guide(
                    "Endgame & QoL has its own in-game wiki, and a Journal for bounties, the bestiary and "
                            + "achievements. Both are kept up to date by the mod itself, so read them there. "
                            + "Its Journal hotkey (Alt+2 unless the server moved it) opens the Journal too.",
                    "", List.of(new GuideLink("Open the Endgame wiki", "eg wiki"),
                            new GuideLink("Open the Journal", "eg journal"))),
            "siren:mermaids", new Guide(
                    "Mermaids has its own menu for your form and tail, and a codex of what you have done. "
                            + "Its documentation is filed under this category too.",
                    "", List.of(new GuideLink("Open the Mermaids menu", "mermaids"),
                            new GuideLink("Show your codex in chat", "mermaids codex"))),
            "alechilles:alec's tamework!", new Guide(
                    "Tamework's Companion Guide lives in a command flute's menu: hold a command flute, open "
                            + "its menu and pick the guide. It covers taming, ownership, commands and groups.",
                    "", List.of()),
            "org.herolias:simpleenchantments", new Guide(
                    "The enchanting window has a walkthrough built in. Open it to see how enchanting works "
                            + "on this server.",
                    "", List.of(new GuideLink("Open the enchanting window", "enchanting"))),
            "major76:majordungeons", new Guide(
                    "Major Dungeons keeps its lore in game: lore pages found in dungeons are read at a "
                            + "Lore Lectern.",
                    "Bench_Lectern", List.of()));

    private final MysticCore core;
    private final WikiModule wiki;

    ModWikiPages(MysticCore core, WikiModule wiki) {
        this.core = core;
        this.wiki = wiki;
    }

    /**
     * Replaces every installed-mod page with a fresh set.
     *
     * @return how many mods have pages
     */
    int publish() {
        wiki.unregisterNamespace(NAMESPACE);
        WikiConfig.Mods config = wiki.config().mods;
        if (config == null || !config.enabled) {
            return 0;
        }
        String parent = WikiCategories.normalize(config.category == null ? "Mods" : config.category);
        TreeSet<String> hidden = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (config.hidden != null) {
            hidden.addAll(config.hidden);
        }
        Map<String, AssetPack> packs = packsByName();
        int published = 0;
        for (PluginBase plugin : plugins()) {
            try {
                PluginManifest manifest = plugin.getManifest();
                String id = plugin.getIdentifier().toString();
                if (PluginManifest.CORE_GROUP.equals(manifest.getGroup()) || hidden.contains(id)
                        || plugin.getClass().getName().startsWith("org.hyzionstudios.mysticessentials.")) {
                    continue;
                }
                AssetPack pack = packs.remove(id.toLowerCase(Locale.ROOT));
                publishMod(parent, id, manifest, plugin, pack, config);
                published++;
            } catch (Throwable t) {
                core.log(Level.WARNING, "Wiki: could not write a page for " + plugin.getName() + ": " + t);
            }
        }
        // Content-only packs (no code) that ship documentation get pages too.
        if (config.importDocs) {
            for (AssetPack pack : packs.values()) {
                PluginManifest manifest = pack.getManifest();
                if (manifest == null || PluginManifest.CORE_GROUP.equals(manifest.getGroup())
                        || hidden.contains(pack.getName()) || docsDir(pack) == null) {
                    continue;
                }
                try {
                    publishMod(parent, pack.getName(), manifest, null, pack, config);
                    published++;
                } catch (Throwable t) {
                    core.log(Level.WARNING, "Wiki: could not read the docs in " + pack.getName() + ": " + t);
                }
            }
        }
        return published;
    }

    private void publishMod(String parent, String pluginId, PluginManifest manifest, PluginBase plugin,
            AssetPack pack, WikiConfig.Mods config) throws IOException {
        String name = displayName(manifest.getName());
        String category = parent + WikiCategories.SEPARATOR + name;
        String slug = NAMESPACE + ":" + slug(pluginId);

        List<String> docPages = new ArrayList<>();
        if (config.importDocs && pack != null) {
            docPages = publishDocs(pack, category, slug, name);
        }
        List<String> docLinks = List.copyOf(docPages);
        Guide guide = GUIDES.get(pluginId.toLowerCase(Locale.ROOT));
        String summary = firstSentence(manifest.getDescription());
        wiki.registerEntry(new WikiEntry(slug, name, category,
                summary.isEmpty() ? "An installed mod." : summary,
                List.of("mod", pluginId, manifest.getName()), 0, null,
                reader -> overview(reader, manifest, plugin, guide, docLinks, config)),
                HyzionIcons.SERVICES, "About this mod, read from the server as it runs");
    }

    // ----- The overview --------------------------------------------------------

    private String overview(PlayerRef reader, PluginManifest manifest, PluginBase plugin, Guide guide,
            List<String> docPages, WikiConfig.Mods config) {
        StringBuilder body = new StringBuilder();
        String description = ModDocs.clean(manifest.getDescription());
        if (!description.isEmpty()) {
            body.append(description).append("\n\n");
        }
        if (guide != null) {
            body.append("## Its own guide\n").append(guide.text()).append("\n\n");
            if (!guide.item().isEmpty() && ItemCatalog.exists(guide.item())) {
                body.append("{{item:").append(guide.item()).append("}}\n");
            }
            for (GuideLink link : guide.links()) {
                if (canRun(reader, link.command())) {
                    body.append('[').append(link.label()).append("](/").append(link.command()).append(")\n");
                }
            }
            body.append('\n');
        }
        if (!docPages.isEmpty()) {
            body.append("## Its documentation\n");
            for (String page : docPages) {
                body.append("[[").append(page).append("]]\n");
            }
            body.append('\n');
        }
        if (config.listCommands && plugin != null) {
            List<String> commands = commandLines(reader, plugin);
            if (!commands.isEmpty()) {
                body.append("## Commands\n");
                commands.forEach(line -> body.append(line).append('\n'));
                body.append('\n');
            }
        }
        body.append("## About\n");
        body.append("- Version ").append(manifest.getVersion() == null ? "unknown" : manifest.getVersion()).append('\n');
        String authors = authors(manifest.getAuthors());
        if (!authors.isEmpty()) {
            body.append("- By ").append(authors).append('\n');
        }
        String website = manifest.getWebsite();
        if (website != null && (website.startsWith("https://") || website.startsWith("http://"))) {
            body.append("- Website: [").append(website.replaceFirst("^https?://", "")).append("](")
                    .append(website.strip()).append(")\n");
        }
        return body.toString();
    }

    /** One bullet per command the reader may run: its names, what it does, and its subcommands. */
    private List<String> commandLines(PlayerRef reader, PluginBase plugin) {
        List<AbstractCommand> owned = new ArrayList<>();
        for (AbstractCommand command : registeredCommands()) {
            if (command.getOwner() == plugin && allowed(reader, command)) {
                owned.add(command);
            }
        }
        owned.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(String.valueOf(a.getName()),
                String.valueOf(b.getName())));
        List<String> lines = new ArrayList<>();
        for (AbstractCommand command : owned) {
            if (lines.size() >= MAX_COMMANDS) {
                lines.add("- ...and " + (owned.size() - MAX_COMMANDS) + " more.");
                break;
            }
            StringBuilder line = new StringBuilder("- /").append(command.getName());
            List<String> aliases = new ArrayList<>(command.getAliases());
            aliases.sort(String.CASE_INSENSITIVE_ORDER);
            if (!aliases.isEmpty()) {
                line.append(" (/").append(String.join(", /", aliases)).append(')');
            }
            String description = describe(command.getDescription());
            if (!description.isEmpty()) {
                line.append(" - ").append(description);
            }
            List<String> subs = new ArrayList<>();
            for (Map.Entry<String, AbstractCommand> sub : command.getSubCommands().entrySet()) {
                if (allowed(reader, sub.getValue())) {
                    subs.add(sub.getKey());
                }
            }
            if (!subs.isEmpty()) {
                subs.sort(String.CASE_INSENSITIVE_ORDER);
                List<String> shown = subs.subList(0, Math.min(subs.size(), MAX_SUBCOMMANDS));
                line.append(". Also: ").append(String.join(", ", shown)).append(subs.size() > shown.size() ? ", ..." : "");
            }
            lines.add(ModDocs.clean(line.toString()));
        }
        return lines;
    }

    private static boolean allowed(PlayerRef reader, AbstractCommand command) {
        String permission = command.getPermission();
        return permission == null || permission.isBlank() || reader.hasPermission(permission);
    }

    /** Whether the first word of {@code commandLine} is a registered command this reader may run. */
    private static boolean canRun(PlayerRef reader, String commandLine) {
        String root = commandLine.split(" ", 2)[0].toLowerCase(Locale.ROOT);
        try {
            AbstractCommand command = CommandManager.get().resolveCommand(root);
            return command != null && allowed(reader, command);
        } catch (Throwable t) {
            return false;
        }
    }

    private static Collection<AbstractCommand> registeredCommands() {
        try {
            return new ArrayList<>(CommandManager.get().getCommandRegistration().values());
        } catch (Throwable t) {
            // Mid-registration on another thread, or no command manager: list nothing this time.
            return List.of();
        }
    }

    /** A description, translated when a mod gave a translation key instead of text. */
    private static String describe(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }
        String text = description.strip();
        if (text.matches("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+")) {
            try {
                String translated = I18nModule.get().getMessage(I18nModule.DEFAULT_LANGUAGE, text);
                return translated == null || translated.isBlank() ? "" : translated.strip();
            } catch (Throwable t) {
                return "";
            }
        }
        return text;
    }

    // ----- Shipped documentation -----------------------------------------------

    /**
     * Registers a mod's {@code Common/Docs} pages under its category.
     *
     * @return {@code id|title} of each top-level page, for the overview's links
     */
    private List<String> publishDocs(AssetPack pack, String category, String slug, String modName) throws IOException {
        Path docs = docsDir(pack);
        List<String> top = new ArrayList<>();
        if (docs == null) {
            return top;
        }
        List<Path> indexes;
        try (Stream<Path> files = Files.list(docs)) {
            indexes = files.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted().limit(8).toList();
        }
        int[] budget = {ModDocs.MAX_TOPICS};
        for (Path index : indexes) {
            List<ModDocs.Documentation> sets = ModDocs.documentations(ModDocs.decode(Files.readAllBytes(index)));
            for (ModDocs.Documentation set : sets) {
                String base = index.getFileName().toString().replaceFirst("(?i)\\.json$", "");
                Path folder = docs.resolve(set.group().isEmpty() ? base : set.group() + "_" + set.id());
                if (!Files.isDirectory(folder)) {
                    folder = docs.resolve(base);
                }
                String setCategory = sets.size() > 1 && !set.name().isBlank()
                        ? category + WikiCategories.SEPARATOR + ModDocs.clean(set.name()).replace("/", "-") : category;
                String setSlug = slug + "." + slug(set.id().isEmpty() ? base : set.id());
                Map<String, String> ids = new HashMap<>();
                assignIds(set.topics(), setSlug, ids);
                int[] order = {1};
                for (ModDocs.Topic topic : set.topics()) {
                    String id = publishTopic(folder, topic, setCategory, ids, modName, order, budget);
                    if (id != null) {
                        top.add(id);
                    }
                }
            }
        }
        return top;
    }

    /** Gives every topic file its page id up front, so topics can link to each other. */
    private static void assignIds(List<ModDocs.Topic> topics, String setSlug, Map<String, String> ids) {
        for (ModDocs.Topic topic : topics) {
            ids.putIfAbsent(topic.file().toLowerCase(Locale.ROOT),
                    setSlug + "." + slug(topic.file().replaceFirst("(?i)\\.md$", "")));
            assignIds(topic.subTopics(), setSlug, ids);
        }
    }

    /**
     * Registers one topic and its sub-topics. A topic with sub-topics becomes a
     * sub-category holding itself first and its sub-topics after it.
     */
    private String publishTopic(Path folder, ModDocs.Topic topic, String category, Map<String, String> ids,
            String modName, int[] order, int[] budget) throws IOException {
        if (budget[0] <= 0) {
            return null;
        }
        Path file = folder.resolve(topic.file()).normalize();
        if (!file.startsWith(folder) || !Files.isRegularFile(file)) {
            return null;
        }
        budget[0]--;
        String fallback = displayName(file.getFileName().toString().replaceFirst("(?i)\\.md$", ""));
        ModDocs.Doc doc = ModDocs.convert(ModDocs.decode(Files.readAllBytes(file)), fallback,
                target -> ids.get(target.toLowerCase(Locale.ROOT)));
        // The mod's own front page is usually titled with the mod's name, which the overview already has.
        String title = doc.title().replace(" ", "").equalsIgnoreCase(modName.replace(" ", "")) ? "Welcome" : doc.title();
        String id = ids.get(topic.file().toLowerCase(Locale.ROOT));
        String own = topic.subTopics().isEmpty() ? category
                : category + WikiCategories.SEPARATOR + title.replace("/", "-");
        String body = doc.body();
        int position = topic.subTopics().isEmpty() ? order[0]++ : 0;
        wiki.registerEntry(new WikiEntry(id, title, own, doc.summary(), List.of("mod", modName), position, null,
                reader -> body), HyzionIcons.WIKI, "From " + modName + "'s own documentation");
        int[] inner = {1};
        for (ModDocs.Topic sub : topic.subTopics()) {
            publishTopic(folder, sub, own, ids, modName, inner, budget);
        }
        return id + "|" + title.replace("]", "").replace("|", "/");
    }

    private static Path docsDir(AssetPack pack) {
        try {
            Path docs = pack.getRoot().resolve("Common").resolve("Docs");
            return Files.isDirectory(docs) ? docs : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ----- Lookups and names ---------------------------------------------------

    private static List<PluginBase> plugins() {
        try {
            return new ArrayList<>(HytaleServer.get().getPluginManager().getPlugins());
        } catch (Throwable t) {
            return List.of();
        }
    }

    private static Map<String, AssetPack> packsByName() {
        Map<String, AssetPack> packs = new LinkedHashMap<>();
        try {
            for (AssetPack pack : AssetModule.get().getAssetPacks()) {
                packs.put(pack.getName().toLowerCase(Locale.ROOT), pack);
            }
        } catch (Throwable ignored) {
            // No asset module: plugins still get overview pages.
        }
        return packs;
    }

    /** {@code MmoMobScaling} reads as {@code Mmo Mob Scaling}; {@code Endgame&QoL} as {@code Endgame & QoL}. */
    static String displayName(String name) {
        if (name == null || name.isBlank()) {
            return "Unnamed mod";
        }
        String spaced = name.strip()
                .replaceAll("([a-z0-9])([A-Z][a-z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replaceAll("\\s*&\\s*", " & ")
                .replaceAll("[_]+", " ")
                .replaceAll("\\s{2,}", " ");
        String readable = ModDocs.clean(spaced.replace("/", "-"));
        return readable.isEmpty() ? "Unnamed mod" : Character.toUpperCase(readable.charAt(0)) + readable.substring(1);
    }

    /** A plugin id or file name as a page id part: lowercase letters, digits and hyphens. */
    static String slug(String value) {
        String slug = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+)|(-+$)", "");
        return slug.isEmpty() ? "mod" : slug.length() > 48 ? slug.substring(0, 48) : slug;
    }

    private static String firstSentence(String description) {
        String text = ModDocs.clean(description);
        int stop = text.indexOf(". ");
        String first = stop > 0 ? text.substring(0, stop + 1) : text;
        return first.length() > 120 ? first.substring(0, 117).strip() + "..." : first;
    }

    private static String authors(List<AuthorInfo> authors) {
        if (authors == null) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (AuthorInfo author : authors) {
            if (author != null && author.getName() != null && !author.getName().isBlank()) {
                names.add(ModDocs.clean(author.getName()));
            }
        }
        return String.join(", ", names);
    }
}
