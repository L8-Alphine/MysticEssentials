package org.hyzionstudios.mysticessentials.spikes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Records spike observations twice: in the server log, and appended to
 * {@code spike-results.log} in the plugin's data folder so a test session can be
 * written up afterwards without scraping the console.
 */
final class SpikeLog {

    private final Consumer<String> serverLog;
    private final Path file;

    SpikeLog(Consumer<String> serverLog, Path file) {
        this.serverLog = serverLog;
        this.file = file;
    }

    synchronized void line(String spike, String message) {
        String text = "[LBSpike " + spike + "] " + message;
        serverLog.accept(text);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Instant.now() + " " + text + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // The server log still has it.
        }
    }

    Path file() {
        return file;
    }
}
