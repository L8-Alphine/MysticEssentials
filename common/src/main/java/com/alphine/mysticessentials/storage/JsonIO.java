package com.alphine.mysticessentials.storage;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.*;

public final class JsonIO {
    private JsonIO() {}

    public static <T> T read(Gson gson, Path file, Class<T> type) throws IOException {
        try (Reader r = Files.newBufferedReader(file)) {
            return gson.fromJson(r, type);
        }
    }

    public static void write(Gson gson, Path file, Object obj, boolean atomic) throws IOException {
        Files.createDirectories(file.getParent());

        if (!atomic) {
            try (Writer w = Files.newBufferedWriter(file)) {
                gson.toJson(obj, w);
            }
            return;
        }

        Path tmpDir = file.getParent().resolve(".tmp");
        Files.createDirectories(tmpDir);

        Path tmp = tmpDir.resolve(file.getFileName().toString() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            gson.toJson(obj, w);
        }

        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static boolean exists(Path file) {
        return Files.exists(file);
    }

    public static void deleteIfExists(Path file) throws IOException {
        Files.deleteIfExists(file);
    }
}
