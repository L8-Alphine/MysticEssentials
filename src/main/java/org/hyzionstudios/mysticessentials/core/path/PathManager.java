package org.hyzionstudios.mysticessentials.core.path;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Single source of truth for every path Mystic Essentials uses under
 * {@code mods/MysticEssentials}. Nothing else in the codebase should build file
 * paths by hand.
 *
 * <pre>
 * mods/MysticEssentials/
 *   config.json
 *   messages/{en_us.json, custom.json}
 *   modules/&lt;module&gt;/{config.json, messages.json, permissions.json}
 *   data/players/&lt;uuid&gt;.json
 *   data/modules/&lt;module&gt;/
 *   data/cache/
 *   logs/
 * </pre>
 */
public final class PathManager {

    private final Path root;

    public PathManager(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public Path configFile() {
        return root.resolve("config.json");
    }

    public Path messagesDir() {
        return root.resolve("messages");
    }

    public Path messagesFile(String locale) {
        return messagesDir().resolve(locale + ".json");
    }

    public Path modulesConfigDir() {
        return root.resolve("modules");
    }

    public Path moduleConfigDir(String moduleId) {
        return modulesConfigDir().resolve(moduleId);
    }

    public Path moduleConfigFile(String moduleId) {
        return moduleConfigDir(moduleId).resolve("config.json");
    }

    public Path moduleMessagesFile(String moduleId) {
        return moduleConfigDir(moduleId).resolve("messages.json");
    }

    public Path modulePermissionsFile(String moduleId) {
        return moduleConfigDir(moduleId).resolve("permissions.json");
    }

    public Path moduleExtraConfigFile(String moduleId, String fileName) {
        return moduleConfigDir(moduleId).resolve(fileName);
    }

    public Path dataDir() {
        return root.resolve("data");
    }

    public Path playersDataDir() {
        return dataDir().resolve("players");
    }

    public Path playerDataFile(String uuid) {
        return playersDataDir().resolve(uuid + ".json");
    }

    public Path moduleDataDir(String moduleId) {
        return dataDir().resolve("modules").resolve(moduleId);
    }

    public Path cacheDir() {
        return dataDir().resolve("cache");
    }

    public Path logsDir() {
        return root.resolve("logs");
    }

    /** Creates the base directory tree if it does not already exist. */
    public void ensureBaseLayout() throws IOException {
        Files.createDirectories(messagesDir());
        Files.createDirectories(modulesConfigDir());
        Files.createDirectories(playersDataDir());
        Files.createDirectories(dataDir().resolve("modules"));
        Files.createDirectories(cacheDir());
        Files.createDirectories(logsDir());
    }
}
