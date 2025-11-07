package com.alphine.mysticessentials.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/** Append-only moderation audit log with simple querying. */
public class AuditLogStore {
    public static final class Entry {
        public long at;                 // millis
        public String action;           // WARN, PARDON, KICK, BAN, TEMPBAN, UNBAN, IPBAN, TEMPIPBAN, UNBANIP, MUTE, UNMUTE, FREEZE, UNFREEZE, JAIL, UNJAIL
        public UUID actor;              // who performed it (may be null for system)
        public UUID target;             // player target (nullable for IP actions)
        public String targetName;       // best-effort display at time of action
        public String ip;               // for ip bans
        public String reason;           // optional
        public Long until;              // expiry for temporary actions
        public String extra;            // e.g., jail name
    }

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final List<Entry> entries = new ArrayList<>();

    public AuditLogStore(Path cfgDir) {
        this.file = cfgDir.resolve("audit_log.json");
        load();
    }

    public synchronized void log(Entry e) {
        entries.add(e);
        save();
    }

    public synchronized List<Entry> byTarget(UUID target, int limit) {
        return entries.stream()
                .filter(e -> Objects.equals(e.target, target))
                .sorted(Comparator.comparingLong(en -> en.at))
                .limit(Math.max(limit, 1000))
                .collect(Collectors.toList());
    }

    public synchronized List<Entry> recent(int limit) {
        return entries.stream()
                .sorted(Comparator.comparingLong((Entry e) -> e.at).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    // ---- IO ----
    private void load() {
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) { save(); return; }
            try (Reader r = Files.newBufferedReader(file)) {
                Entry[] arr = gson.fromJson(r, Entry[].class);
                entries.clear();
                if (arr != null) entries.addAll(Arrays.asList(arr));
            }
        } catch (Exception ignored) {}
    }

    private void save() {
        try (Writer w = Files.newBufferedWriter(file)) {
            gson.toJson(entries, w);
        } catch (Exception ignored) {}
    }

    // Helper
    public static Entry make(String action, UUID actor, UUID target, String targetName, String reason, Long until, String ip, String extra){
        Entry e = new Entry();
        e.at = System.currentTimeMillis();
        e.action = action;
        e.actor = actor;
        e.target = target;
        e.targetName = targetName;
        e.reason = reason;
        e.until = until;
        e.ip = ip;
        e.extra = extra;
        return e;
    }
}
