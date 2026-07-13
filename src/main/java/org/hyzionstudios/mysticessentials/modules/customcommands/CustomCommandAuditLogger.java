package org.hyzionstudios.mysticessentials.modules.customcommands;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;

/**
 * Usage and audit logging for custom commands, both off by default:
 *
 * <ul>
 *   <li><b>Usage logging</b> ({@code logging.usage}) — one console line per
 *       execution, plus in-memory usage counters flushed through the storage
 *       abstraction (periodically and on disable).</li>
 *   <li><b>Audit logging</b> ({@code logging.audit}) — timestamped records in
 *       {@code logs/customcommands.log}: executions, dispatched command
 *       actions, blocked commands, recursion/budget aborts, reloads, and
 *       enable/disable changes.</li>
 * </ul>
 */
public final class CustomCommandAuditLogger {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MysticCore core;
    private final CustomCommandStorage storage;
    private final Supplier<CustomCommandsConfig> config;
    private final Path auditFile;
    private final Object writeLock = new Object();

    /** command name -> total executions; loaded on enable, flushed on disable/interval. */
    private final Map<String, AtomicLong> usageCounters = new ConcurrentHashMap<>();
    private volatile boolean countersDirty;

    public CustomCommandAuditLogger(MysticCore core, CustomCommandStorage storage,
            Supplier<CustomCommandsConfig> config) {
        this.core = core;
        this.storage = storage;
        this.config = config;
        this.auditFile = core.paths().logsDir().resolve("customcommands.log");
    }

    /** Loads persisted usage counters; call once on module enable. */
    public void loadCounters() {
        storage.loadUsageStats().thenAccept(loaded ->
                loaded.forEach((command, count) -> usageCounters.put(command, new AtomicLong(count))));
    }

    /** Writes dirty usage counters back through storage. */
    public void flushCounters() {
        if (!countersDirty) {
            return;
        }
        countersDirty = false;
        Map<String, Long> snapshot = new java.util.LinkedHashMap<>();
        usageCounters.forEach((command, counter) -> snapshot.put(command, counter.get()));
        storage.saveUsageStats(snapshot);
    }

    public long usageCount(String commandName) {
        AtomicLong counter = usageCounters.get(commandName);
        return counter == null ? 0 : counter.get();
    }

    // ----- Events ---------------------------------------------------------------

    /** Records one execution: counter always, console line and audit record if enabled. */
    public void logExecution(CustomCommand definition, String senderName, boolean console,
            String rawArgs, boolean test) {
        usageCounters.computeIfAbsent(definition.nameLower(), k -> new AtomicLong()).incrementAndGet();
        countersDirty = true;

        String detail = (test ? "TEST " : "") + "/" + definition.nameLower()
                + (rawArgs == null || rawArgs.isBlank() ? "" : " " + rawArgs)
                + " by " + senderName + (console ? " (console)" : "");
        if (config.get().logging.usage) {
            core.log(Level.INFO, "[customcommands] " + detail);
        }
        audit("EXEC " + detail);
    }

    /** Records a command action dispatch (what actually ran, and as whom). */
    public void logDispatch(CustomCommand definition, String dispatched, String executorDescription) {
        audit("DISPATCH /" + definition.nameLower() + " -> '" + dispatched + "' as " + executorDescription);
    }

    /** Records a safety stop: blocked command, recursion, depth, or budget exhaustion. */
    public void logSafetyStop(CustomCommand definition, String reason) {
        core.log(Level.WARNING, "[customcommands] /" + definition.nameLower() + ": " + reason);
        audit("BLOCKED /" + definition.nameLower() + ": " + reason);
    }

    /** Records module lifecycle events (reload, per-command enable/disable). */
    public void logAdmin(String actor, String what) {
        audit("ADMIN " + actor + ": " + what);
    }

    // ----- File writing ------------------------------------------------------------

    private void audit(String line) {
        if (!config.get().logging.audit) {
            return;
        }
        String record = TIMESTAMP.format(LocalDateTime.now()) + " " + line + System.lineSeparator();
        synchronized (writeLock) {
            try {
                Files.createDirectories(auditFile.getParent());
                Files.writeString(auditFile, record, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                core.log(Level.WARNING, "[customcommands] Could not write audit log: " + e.getMessage());
            }
        }
    }
}
