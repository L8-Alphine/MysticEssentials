package com.alphine.mysticessentials.config;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

//TODO: ADD TO STARTUP CONFIG LOADING
/** Utility to upgrade user JSON config files by merging with default structure. */
public final class JsonFileUpgrader {
    private JsonFileUpgrader(){}

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    public static void upgrade(Path userFile, JsonElement defaultRoot) {
        try {
            JsonElement oldRoot = read(userFile);
            if (oldRoot == null) {
                writeAtomic(userFile, defaultRoot);
                return;
            }

            JsonElement merged = deepMerge(defaultRoot, oldRoot);

            if (!oldRoot.toString().equals(merged.toString())) {
                backup(userFile);
                writeAtomic(userFile, merged);
                System.out.println("[MysticEssentials] Upgraded: " + userFile.getFileName());
            }
        } catch (Exception e) {
            System.err.println("[MysticEssentials] Failed to upgrade file " + userFile + ": " + e.getMessage());
        }
    }

    /** defaults-first deep merge. old overrides defaults. */
    private static JsonElement deepMerge(JsonElement defaults, JsonElement old) {
        if (defaults == null || defaults.isJsonNull()) return old;
        if (old == null || old.isJsonNull()) return defaults;

        // Object merge
        if (defaults.isJsonObject() && old.isJsonObject()) {
            JsonObject out = defaults.getAsJsonObject().deepCopy();
            JsonObject o = old.getAsJsonObject();

            for (var e : o.entrySet()) {
                String key = e.getKey();
                JsonElement oldVal = e.getValue();

                if (out.has(key)) {
                    out.add(key, deepMerge(out.get(key), oldVal));
                } else {
                    // preserve unknown key from old file
                    out.add(key, oldVal);
                }
            }
            return out;
        }

        // Array behavior:
        // If user has an array, KEEP IT (arrays are usually "lists" not "maps")
        // If you want smarter merging by "id", we can add that later.
        if (defaults.isJsonArray() && old.isJsonArray()) {
            return old;
        }

        // Primitive or type mismatch -> old wins
        return old;
    }

    private static JsonElement read(Path file) {
        if (!Files.exists(file)) return null;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r);
        } catch (Exception e) {
            return null;
        }
    }

    private static void backup(Path file) {
        try {
            if (!Files.exists(file)) return;
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path bak = file.resolveSibling(file.getFileName().toString() + ".bak-" + ts);
            Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {}
    }

    private static void writeAtomic(Path file, JsonElement root) throws Exception {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");

        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            GSON.toJson(root, w);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
