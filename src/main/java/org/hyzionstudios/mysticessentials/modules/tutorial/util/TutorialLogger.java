package org.hyzionstudios.mysticessentials.modules.tutorial.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.tutorial.config.TutorialModuleConfig;

/**
 * Tutorial activity log, appended to
 * {@code mods/MysticEssentials/data/modules/tutorial/logs/tutorial.log}.
 * Category switches map to {@code config.logging}; {@link #debug} lines also
 * go to the server console when {@code debug} is on. Log failures are
 * swallowed after one console warning — logging must never break a tutorial.
 */
public final class TutorialLogger {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MysticCore core;
    private final Path logFile;
    private final Supplier<TutorialModuleConfig> config;
    private boolean writeFailureLogged;

    public TutorialLogger(MysticCore core, String moduleId, Supplier<TutorialModuleConfig> config) {
        this.core = core;
        this.config = config;
        this.logFile = core.paths().moduleDataDir(moduleId).resolve("logs").resolve("tutorial.log");
    }

    public void start(String player, String tutorialId, String source) {
        if (config.get().logging.logStarts) {
            write("START", player + " began '" + tutorialId + "' (" + source + ")");
        }
    }

    public void complete(String player, String tutorialId, boolean marked) {
        if (config.get().logging.logCompletions) {
            write("COMPLETE", player + " finished '" + tutorialId + "'" + (marked ? "" : " (not recorded)"));
        }
    }

    public void cancel(String player, String tutorialId, String reason) {
        if (config.get().logging.logCancels) {
            write("CANCEL", player + " left '" + tutorialId + "' (" + reason + ")");
        }
    }

    public void timeout(String player, String tutorialId) {
        if (config.get().logging.logErrors) {
            write("TIMEOUT", player + " timed out in '" + tutorialId + "'");
        }
    }

    public void error(String message) {
        if (config.get().logging.logErrors) {
            write("ERROR", message);
        }
        core.log(Level.WARNING, "[tutorial] " + message);
    }

    /** Console + file line, emitted only while {@code debug: true}. */
    public void debug(String message) {
        if (config.get().debug) {
            write("DEBUG", message);
            core.log(Level.INFO, "[tutorial] " + message);
        }
    }

    private synchronized void write(String category, String message) {
        String line = TIMESTAMP.format(LocalDateTime.now()) + " [" + category + "] " + message
                + System.lineSeparator();
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(logFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            if (!writeFailureLogged) {
                writeFailureLogged = true;
                core.log(Level.WARNING, "[tutorial] Cannot write tutorial.log: " + e.getMessage());
            }
        }
    }
}
