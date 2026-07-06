package org.hyzionstudios.mysticessentials.core.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.util.Json;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Loads, validates, and (on first run) generates the main {@code config.json},
 * and provides a generic, <b>version-migrating</b> loader modules reuse for
 * their own config files.
 *
 * <p>Migration model: every config file carries a {@code configVersion} field.
 * On load the raw JSON tree is stepped through registered
 * {@link Migration Migrations} ({@code fromVersion} &rarr; {@code fromVersion+1})
 * until it reaches the current version, then any keys present in the defaults
 * but missing from the file are deep-merged in, and the upgraded file is written
 * back. A file with no {@code configVersion} is treated as version 1.</p>
 */
public final class ConfigManager {

    /** A single-step config upgrade: mutates the raw JSON tree from {@code fromVersion} to {@code fromVersion + 1}. */
    public record Migration(int fromVersion, Consumer<JsonObject> upgrade) {
    }

    private final MysticCore core;
    private MainConfig config;

    /** Registered migrations per config id ("main" or a module id). */
    private final Map<String, List<Migration>> migrations = new HashMap<>();
    /** Current schema version per config id; absent = 1 (no migrations yet). */
    private final Map<String, Integer> currentVersions = new HashMap<>();

    public ConfigManager(MysticCore core) {
        this.core = core;
    }

    public MainConfig get() {
        return config;
    }

    /**
     * Registers a migration step for a config file. {@code configId} is
     * {@code "main"} for the main config or a module id for module configs.
     * The current version for that config becomes the highest
     * {@code fromVersion + 1} registered.
     */
    public void registerMigration(String configId, Migration migration) {
        migrations.computeIfAbsent(configId, k -> new ArrayList<>()).add(migration);
        currentVersions.merge(configId, migration.fromVersion() + 1, Math::max);
    }

    public int currentVersion(String configId) {
        return currentVersions.getOrDefault(configId, 1);
    }

    /** Loads the main config, writing defaults if the file is missing, then migrates and validates it. */
    public void load() {
        Path file = core.paths().configFile();
        try {
            JsonElement raw = Json.readFile(file);
            if (raw == null) {
                config = new MainConfig();
                config.configVersion = currentVersion("main");
                Json.writeFile(file, config);
                core.log(Level.INFO, "Generated default config.json");
            } else {
                JsonObject tree = Json.asObject(raw);
                boolean changed = migrateTree("main", tree, Json.asObject(Json.toTree(new MainConfig())));
                config = Json.gson().fromJson(tree, MainConfig.class);
                if (changed) {
                    Json.writeFile(file, config);
                    core.log(Level.INFO, "Migrated config.json to version " + currentVersion("main") + ".");
                }
            }
        } catch (Exception e) {
            core.log(Level.SEVERE, "Failed to read config.json (" + e.getMessage()
                    + "); falling back to in-memory defaults. The file was NOT overwritten.");
            config = new MainConfig();
        }
        validate();
    }

    /**
     * Steps {@code tree} through registered migrations for {@code configId} and
     * deep-merges keys that exist in {@code defaults} but not in the file.
     *
     * @return {@code true} if the tree was modified and should be re-saved.
     */
    private boolean migrateTree(String configId, JsonObject tree, JsonObject defaults) {
        boolean changed = false;
        int current = currentVersion(configId);
        int version = tree.has("configVersion") && tree.get("configVersion").isJsonPrimitive()
                ? tree.get("configVersion").getAsInt()
                : 1;
        if (version < current) {
            List<Migration> steps = migrations.getOrDefault(configId, List.of());
            while (version < current) {
                final int from = version;
                steps.stream().filter(m -> m.fromVersion() == from).forEach(m -> m.upgrade().accept(tree));
                version++;
                changed = true;
            }
            tree.addProperty("configVersion", current);
            core.log(Level.INFO, "Config '" + configId + "' migrated to version " + current + ".");
        }
        changed |= mergeMissingKeys(tree, defaults);
        return changed;
    }

    /** Recursively adds keys present in {@code defaults} but absent in {@code target}. */
    private static boolean mergeMissingKeys(JsonObject target, JsonObject defaults) {
        boolean changed = false;
        for (Map.Entry<String, JsonElement> entry : defaults.entrySet()) {
            JsonElement existing = target.get(entry.getKey());
            if (existing == null) {
                target.add(entry.getKey(), entry.getValue());
                changed = true;
            } else if (existing.isJsonObject() && entry.getValue().isJsonObject()) {
                changed |= mergeMissingKeys(existing.getAsJsonObject(), entry.getValue().getAsJsonObject());
            }
        }
        return changed;
    }

    private void validate() {
        if (config.storage == null) {
            config.storage = new MainConfig.Storage();
        }
        String provider = config.storage.provider == null ? "json" : config.storage.provider.toLowerCase();
        switch (provider) {
            case "json", "mysql", "mariadb", "redis" -> config.storage.provider = provider;
            default -> {
                core.log(Level.WARNING,
                        "Unknown storage provider '" + config.storage.provider + "', defaulting to 'json'.");
                config.storage.provider = "json";
            }
        }
        if (config.integrations == null) {
            config.integrations = new MainConfig.Integrations();
        }
        if (config.modules == null) {
            core.log(Level.WARNING, "config.json has no 'modules' section; enabling defaults.");
            config.modules = new MainConfig().modules;
        }
    }

    /**
     * Loads a module config file into {@code type}, writing {@code defaults} if
     * the file is missing. Existing files are stepped through any migrations
     * registered under the module id and missing default keys are merged in
     * (so new options appear on servers with older files). Never throws: on
     * error it logs and returns the provided defaults so a bad module config
     * cannot take down the server.
     */
    public <T> T loadModuleConfig(String moduleId, Class<T> type, T defaults) {
        Path file = core.paths().moduleConfigFile(moduleId);
        try {
            JsonElement raw = Json.readFile(file);
            if (raw == null) {
                Json.writeFile(file, defaults);
                core.log(Level.INFO, "Generated default config for module '" + moduleId + "'");
                return defaults;
            }
            JsonObject tree = Json.asObject(raw);
            boolean changed = migrateTree(moduleId, tree, Json.asObject(Json.toTree(defaults)));
            T loaded = Json.gson().fromJson(tree, type);
            if (changed) {
                Json.writeFile(file, loaded);
                core.log(Level.INFO, "Updated config for module '" + moduleId + "' with new defaults.");
            }
            return loaded;
        } catch (IOException e) {
            core.log(Level.SEVERE, "Failed to load config for module '" + moduleId + "': " + e.getMessage());
            return defaults;
        } catch (RuntimeException e) {
            core.log(Level.SEVERE, "Invalid config for module '" + moduleId + "' (" + e.getMessage()
                    + "); using defaults. The file was NOT overwritten.");
            return defaults;
        }
    }

    /** Loads a module's raw messages/permissions JSON object, writing {@code defaults} if absent. */
    public JsonObject loadModuleJson(Path file, JsonObject defaults) {
        try {
            JsonElement loaded = Json.readFile(file);
            if (loaded == null) {
                Json.writeFile(file, defaults);
                return defaults;
            }
            return Json.asObject(loaded);
        } catch (IOException e) {
            core.log(Level.SEVERE, "Failed to load " + file.getFileName() + ": " + e.getMessage());
            return defaults;
        }
    }
}
