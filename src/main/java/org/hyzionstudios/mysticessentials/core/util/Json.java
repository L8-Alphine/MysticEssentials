package org.hyzionstudios.mysticessentials.core.util;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Small JSON helper wrapping Gson (bundled on the Hytale server classpath).
 * Centralizes pretty-printing and atomic-ish file reads/writes so the rest of
 * the codebase never touches Gson configuration directly.
 */
public final class Json {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private Json() {
    }

    public static Gson gson() {
        return GSON;
    }

    public static JsonElement parse(String raw) {
        return JsonParser.parseString(raw);
    }

    public static <T> T fromJson(JsonElement element, Class<T> type) {
        return GSON.fromJson(element, type);
    }

    public static JsonElement toTree(Object value) {
        return GSON.toJsonTree(value);
    }

    public static String toString(Object value) {
        return GSON.toJson(value);
    }

    /** Reads and parses a JSON file, or returns {@code null} if it does not exist. */
    public static JsonElement readFile(Path file) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        }
    }

    /** Reads a JSON file into an object of {@code type}, or {@code null} if the file is absent. */
    public static <T> T readFile(Path file, Class<T> type) throws IOException {
        JsonElement element = readFile(file);
        return element == null ? null : GSON.fromJson(element, type);
    }

    /**
     * Writes a value as pretty JSON, creating parent directories as needed. The
     * write is atomic: the JSON is fully written to a sibling temp file which is
     * then moved into place, so a crash mid-write can never leave a truncated
     * (unparseable) target file behind.
     */
    public static void writeFile(Path file, Object value) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(value, writer);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Some filesystems can't do an atomic replace; fall back to a plain
            // replace, which still avoids a partially written target file.
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** @return an empty object if {@code element} is null or not an object, otherwise the object. */
    public static JsonObject asObject(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }
}
