package org.hyzionstudios.mysticessentials.modules.tutorial.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.util.Json;

import com.google.gson.JsonElement;

/**
 * Loads tutorial definitions, page definitions, and the localization bundle
 * from {@code mods/MysticEssentials/modules/tutorial/}, generating the default
 * files on first run. A malformed file is logged and skipped — it can never
 * take the module (or the server) down — and existing owner files are never
 * overwritten.
 */
public final class TutorialConfigLoader {

    private static final String DEFAULTS_MARKER = ".defaults-generated";

    private final MysticCore core;
    private final String moduleId;

    private final Map<String, TutorialDefinition> tutorials = new LinkedHashMap<>();
    private final Map<String, TutorialPageDefinition> pages = new LinkedHashMap<>();
    private final Map<String, String> localization = new LinkedHashMap<>();

    public TutorialConfigLoader(MysticCore core, String moduleId) {
        this.core = core;
        this.moduleId = moduleId;
    }

    public Path tutorialsDir() {
        return core.paths().moduleConfigDir(moduleId).resolve("tutorials");
    }

    public Path pagesDir() {
        return core.paths().moduleConfigDir(moduleId).resolve("pages");
    }

    public Path localizationFile() {
        return core.paths().moduleConfigDir(moduleId).resolve("localization").resolve("en_us.json");
    }

    /** Generates missing default files, then (re)loads everything from disk. */
    public void load() {
        generateDefaults();
        tutorials.clear();
        pages.clear();
        localization.clear();
        loadDirectory(tutorialsDir(), TutorialDefinition.class,
                def -> def.isValid() ? def.id : null,
                (id, def) -> tutorials.put(id.toLowerCase(Locale.ROOT), def));
        loadDirectory(pagesDir(), TutorialPageDefinition.class,
                def -> def.isValid() ? def.id : null,
                (id, def) -> pages.put(id.toLowerCase(Locale.ROOT), def));
        loadLocalization();
        core.log(Level.INFO, "[" + moduleId + "] Loaded " + tutorials.size() + " tutorial(s) and "
                + pages.size() + " page(s).");
    }

    private <T> void loadDirectory(Path dir, Class<T> type,
            java.util.function.Function<T, String> idOf,
            java.util.function.BiConsumer<String, T> sink) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                try {
                    T loaded = Json.readFile(file, type);
                    String id = loaded == null ? null : idOf.apply(loaded);
                    if (id == null) {
                        core.log(Level.WARNING, "[" + moduleId + "] Skipping " + file.getFileName()
                                + ": missing or blank 'id'.");
                        continue;
                    }
                    sink.accept(id, loaded);
                } catch (Exception e) {
                    core.log(Level.WARNING, "[" + moduleId + "] Skipping invalid JSON file "
                            + file.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            core.log(Level.SEVERE, "[" + moduleId + "] Failed to read " + dir + ": " + e.getMessage());
        }
    }

    private void loadLocalization() {
        try {
            JsonElement raw = Json.readFile(localizationFile());
            if (raw != null && raw.isJsonObject()) {
                raw.getAsJsonObject().entrySet().forEach(entry -> {
                    if (entry.getValue().isJsonPrimitive()) {
                        localization.put(entry.getKey(), entry.getValue().getAsString());
                    }
                });
            }
        } catch (Exception e) {
            core.log(Level.WARNING, "[" + moduleId + "] Failed to read localization/en_us.json: "
                    + e.getMessage());
        }
    }

    // ----- Lookups -----------------------------------------------------------

    public java.util.Optional<TutorialDefinition> tutorial(String id) {
        return id == null ? java.util.Optional.empty()
                : java.util.Optional.ofNullable(tutorials.get(id.toLowerCase(Locale.ROOT)));
    }

    public java.util.Collection<TutorialDefinition> tutorials() {
        return Collections.unmodifiableCollection(tutorials.values());
    }

    public java.util.Optional<TutorialPageDefinition> page(String id) {
        return id == null ? java.util.Optional.empty()
                : java.util.Optional.ofNullable(pages.get(id.toLowerCase(Locale.ROOT)));
    }

    public java.util.Collection<TutorialPageDefinition> pages() {
        return Collections.unmodifiableCollection(pages.values());
    }

    /** Localization lookup used by {@code {lang:key}} placeholders; falls back to the key itself. */
    public String localize(String key) {
        return localization.getOrDefault(key, key);
    }

    // ----- Default file generation --------------------------------------------

    /**
     * Seeds the example files once. After that, deleting or renaming an example
     * is treated as an owner edit and it is not recreated on reload/restart.
     */
    public void generateDefaults() {
        Path moduleDir = core.paths().moduleConfigDir(moduleId);
        Path marker = moduleDir.resolve(DEFAULTS_MARKER);
        try {
            Files.createDirectories(tutorialsDir());
            Files.createDirectories(pagesDir());
            Files.createDirectories(localizationFile().getParent());
        } catch (IOException e) {
            core.log(Level.SEVERE, "[" + moduleId + "] Failed to create tutorial folders: " + e.getMessage());
            return;
        }

        if (Files.exists(marker)) {
            return;
        }

        // Older installations predate the marker. Any generated/owner-managed
        // artifact proves that initialization already happened, so adopt it
        // without restoring samples the owner may have intentionally removed.
        Path readme = moduleDir.resolve("README.md");
        if (hasJsonFiles(tutorialsDir()) || hasJsonFiles(pagesDir())
                || Files.exists(localizationFile()) || Files.exists(readme)) {
            writeDefaultsMarker(marker);
            return;
        }

        writeIfAbsent(tutorialsDir().resolve("first_join.json"), Json.toString(defaultFirstJoinTutorial()));
        writeIfAbsent(tutorialsDir().resolve("getting_started.json"), Json.toString(defaultGettingStartedTutorial()));
        writeIfAbsent(pagesDir().resolve("getting_started.json"), Json.toString(defaultGettingStartedPage()));
        writeIfAbsent(pagesDir().resolve("rules.json"), Json.toString(defaultRulesPage()));
        writeIfAbsent(pagesDir().resolve("tutorial_error.json"), Json.toString(defaultErrorPage()));
        writeIfAbsent(localizationFile(), Json.toString(defaultLocalization()));
        writeIfAbsent(readme, defaultReadme());
        writeDefaultsMarker(marker);
    }

    private boolean hasJsonFiles(Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            return stream.iterator().hasNext();
        } catch (IOException e) {
            core.log(Level.WARNING, "[" + moduleId + "] Could not inspect " + dir + ": " + e.getMessage());
            // Preserve owner files when their state cannot be determined.
            return true;
        }
    }

    private void writeDefaultsMarker(Path marker) {
        try {
            Files.writeString(marker, "Defaults initialized; owner-managed files are not regenerated.\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            core.log(Level.WARNING, "[" + moduleId + "] Could not record default initialization: "
                    + e.getMessage());
        }
    }

    private void writeIfAbsent(Path file, String content) {
        if (Files.exists(file)) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            core.log(Level.INFO, "[" + moduleId + "] Generated default " + file.getFileName());
        } catch (IOException e) {
            core.log(Level.SEVERE, "[" + moduleId + "] Failed to write " + file + ": " + e.getMessage());
        }
    }

    private static TutorialDefinition defaultFirstJoinTutorial() {
        TutorialDefinition def = new TutorialDefinition();
        def.id = "first_join";
        def.displayName = "First Join Tutorial";
        def.description = "Introduces new players to the server spawn, rules, and starter flow.";
        def.enabled = true;
        def.playerState = new TutorialDefinition.PlayerState();
        def.playerState.freezePlayer = true;
        def.playerState.disableMovement = true;
        def.playerState.disableInteraction = true;
        def.playerState.disableDamage = true;
        def.playerState.disableChat = false;
        def.playerState.hideHud = true;
        def.playerState.hideOtherPlayers = false;
        def.playerState.restoreLocationAfterTutorial = false;
        def.machinima.enabled = true;
        def.machinima.sceneId = "first_join_spawn_intro";
        def.machinima.pathId = "spawn_intro_path";
        def.machinima.waitForCompletion = true;
        def.machinima.startDelayTicks = 20;
        def.machinima.timeoutSeconds = 180;
        def.completion.markCompleted = true;
        def.completion.showPage = true;
        def.completion.pageId = "getting_started";
        return def;
    }

    private static TutorialDefinition defaultGettingStartedTutorial() {
        TutorialDefinition def = new TutorialDefinition();
        def.id = "getting_started";
        def.displayName = "Getting Started";
        def.description = "A quick, replayable orientation that opens the getting-started page.";
        def.enabled = true;
        def.replay.allowReplay = true;
        def.playerState = new TutorialDefinition.PlayerState();
        def.playerState.freezePlayer = false;
        def.playerState.disableMovement = false;
        def.playerState.disableInteraction = false;
        def.playerState.disableDamage = true;
        def.playerState.hideHud = false;
        def.machinima.enabled = false;
        def.machinima.sceneId = "";
        def.machinima.pathId = "";
        def.completion.markCompleted = true;
        def.completion.showPage = true;
        def.completion.pageId = "getting_started";
        return def;
    }

    private static TutorialPageDefinition defaultGettingStartedPage() {
        TutorialPageDefinition page = new TutorialPageDefinition();
        page.id = "getting_started";
        page.title = "Getting Started";
        page.subtitle = "Welcome to the server. Choose what you want to do next.";
        TutorialPageDefinition.ContentItem text = new TutorialPageDefinition.ContentItem();
        text.text = "You have completed the tutorial.";
        page.content.add(text);
        page.buttons.add(button("rules", "Read Rules", "book", "page", "rules"));
        page.buttons.add(button("spawn", "Go To Spawn", "home", "command", "spawn"));
        page.buttons.add(button("close", "Close", "close", "close", ""));
        return page;
    }

    private static TutorialPageDefinition defaultRulesPage() {
        TutorialPageDefinition page = new TutorialPageDefinition();
        page.id = "rules";
        page.title = "Server Rules";
        page.subtitle = "Play fair and be kind.";
        String[] rules = {
                "1. Be respectful to other players.",
                "2. No griefing, stealing, or cheating.",
                "3. Keep chat friendly and spam-free.",
                "4. Follow staff instructions.",
        };
        for (String rule : rules) {
            TutorialPageDefinition.ContentItem item = new TutorialPageDefinition.ContentItem();
            item.text = rule;
            page.content.add(item);
        }
        page.buttons.add(button("back", "Back", "back", "page", "getting_started"));
        page.buttons.add(button("close", "Close", "close", "close", ""));
        return page;
    }

    private static TutorialPageDefinition defaultErrorPage() {
        TutorialPageDefinition page = new TutorialPageDefinition();
        page.id = "tutorial_error";
        page.title = "Tutorial Interrupted";
        page.subtitle = "Something went wrong, but you are free to keep playing.";
        TutorialPageDefinition.ContentItem text = new TutorialPageDefinition.ContentItem();
        text.text = "The tutorial could not finish. Your character has been restored. "
                + "You can replay it later with /tutorial play.";
        page.content.add(text);
        page.buttons.add(button("close", "Close", "close", "close", ""));
        return page;
    }

    private static TutorialButtonDefinition button(String id, String text, String icon,
            String actionType, String actionValue) {
        TutorialButtonDefinition btn = new TutorialButtonDefinition();
        btn.id = id;
        btn.text = text;
        btn.icon = icon;
        btn.action.type = actionType;
        btn.action.value = actionValue;
        return btn;
    }

    private static Map<String, String> defaultLocalization() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("tutorial.first_join.welcome", "Welcome to the server, {player}!");
        map.put("tutorial.error.title", "Tutorial Interrupted");
        map.put("tutorial.getting_started.title", "Getting Started");
        return map;
    }

    private static String defaultReadme() {
        return """
                # MysticEssentials Tutorial Module

                Cinematic and UI-based tutorials for new (and returning) players.

                ## Folders

                - `config.json` — module settings. The module ships **disabled**; also set
                  `"tutorial": true` in the main `mods/MysticEssentials/config.json` modules map.
                - `tutorials/*.json` — tutorial definitions (one file per tutorial, `id` must match usage).
                - `pages/*.json` — UI pages shown by tutorials and `/tutorial page`.
                - `localization/en_us.json` — strings referenced from pages via `{lang:key}`.

                Player progress is stored in
                `mods/MysticEssentials/data/modules/tutorial/players/<uuid>.json`, and an
                activity log is written to
                `mods/MysticEssentials/data/modules/tutorial/logs/tutorial.log`.

                ## Commands

                `/tutorial help|list|info|play|stop|skip|reset|complete|status|page|reload|debug`
                — see the main MysticEssentials README for the full reference.
                `/tutorial <tutorial> [player]` is a shortcut for `/tutorial play`.

                ## Scene providers

                `sceneProvider.type` picks how cinematic scenes are played:

                - `machinima` — sends the Hytale machinima scene-play packet to the client.
                  The scene/path assets must exist client-side; if a scene never reports
                  progress the per-tutorial failsafe timeout ends it safely.
                - `debug` — logs the scene request and completes immediately.
                - `noop` — skips scenes entirely (pages and completion still run).

                A player can never be left frozen: every session has a failsafe timeout, and
                player state is restored on completion, cancel, skip, failure, timeout,
                disconnect, module disable, and server shutdown.
                """;
    }
}
