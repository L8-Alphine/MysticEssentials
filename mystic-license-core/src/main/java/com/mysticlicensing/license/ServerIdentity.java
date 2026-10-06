package com.mysticlicensing.license;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Establishes and persists the server UUID that licenses are bound to.
 *
 * <p>This closes the loop with the licensing portal: the operator gives the
 * portal a server UUID, and that UUID has to be the same one the mod checks
 * against on every subsequent boot. So the mod owns it.
 *
 * <p>Every Mystic mod on a server shares one identity, kept in
 * {@code mods/.mystic/server-id.txt} (the {@value #SHARED_DIR} folder beside the
 * mods' own data folders, see {@link #resolveShared}). On first run it is
 * generated at random - or adopted from the per-mod {@code server-id.txt} that
 * earlier versions kept in each mod's data directory. From then on that file
 * <em>is</em> the server's identity. It is deliberately plain text: an operator
 * can read it, paste it into the portal, and back it up.
 *
 * <h2>Why generated rather than derived</h2>
 * Deriving an id from hardware, a file path or a hostname sounds tamper
 * resistant but is not - all of those are trivially spoofed - and it breaks
 * legitimately: a host migration, a container rebuild or a renamed folder would
 * silently invalidate a paid license and generate a support ticket. A persisted
 * random UUID is honest about what it is (a stable label, not a secret) and
 * fails only when the operator deletes it, which the portal's
 * server-replacement flow recovers from.
 *
 * <h2>Corruption is never silently repaired</h2>
 * If {@code server-id.txt} exists but does not parse, this class does
 * <strong>not</strong> overwrite it. Regenerating would orphan the operator's
 * existing license against a UUID they can no longer produce. It reports the
 * problem instead and lets the licensed feature stay off.
 */
public final class ServerIdentity {

    /**
     * File holding the persisted server UUID: in {@link #sharedDir} since every
     * Mystic mod shares it, in each mod's data dir before that.
     */
    public static final String IDENTITY_FILE = "server-id.txt";

    /**
     * Folder directly under the server's mods folder that holds what every
     * Mystic mod shares, whichever mod created it: {@code mods/.mystic/}.
     */
    public static final String SHARED_DIR = ".mystic";

    /**
     * Data folder of the mod whose per-mod id wins a migration when several
     * disagree: MysticEssentials was the first to keep one.
     */
    public static final String PREFERRED_MOD_DIR = "MysticEssentials";

    /** File the operator uploads to the portal instead of typing the UUID. */
    public static final String REQUEST_FILE = "license-request.json";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int NONCE_BYTES = 24;

    private ServerIdentity() {
    }

    /**
     * Read the persisted server UUID, if there is a readable, well-formed one.
     *
     * @return the UUID, or empty when the file is absent, unreadable or malformed
     */
    public static Optional<UUID> load(Path dataDir) {
        Path file = dataDir.resolve(IDENTITY_FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            // Editors on Windows like to save UTF-8 with a byte-order mark.
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            text = text.trim();
            // Tolerate trailing lines so operators can annotate the file.
            int newline = text.indexOf('\n');
            if (newline >= 0) {
                text = text.substring(0, newline).trim();
            }
            return Optional.of(UUID.fromString(text));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Return the persisted UUID, creating and storing one on first run.
     *
     * <p>Never overwrites an existing file. If one is present but corrupt the
     * result reports {@link Outcome#CORRUPT} and carries no UUID, so the caller
     * can log something actionable rather than quietly re-binding the server to
     * a new identity.
     */
    public static Result resolve(Path dataDir) {
        Path file = dataDir.resolve(IDENTITY_FILE);

        if (Files.exists(file)) {
            return load(dataDir)
                    .map(uuid -> new Result(uuid, Outcome.LOADED, null))
                    .orElseGet(() -> new Result(null, Outcome.CORRUPT,
                            file + " exists but does not contain a valid UUID. It has been left "
                                    + "untouched so an existing license is not orphaned - fix or "
                                    + "delete it, then re-register the server in the portal."));
        }

        UUID created = UUID.randomUUID();
        try {
            Files.createDirectories(dataDir);
            if (!createAtomically(file, created + System.lineSeparator())) {
                // Another start (a reload racing this one) created it first: use theirs.
                return load(dataDir)
                        .map(uuid -> new Result(uuid, Outcome.LOADED, null))
                        .orElseGet(() -> new Result(null, Outcome.CORRUPT,
                                file + " exists but does not contain a valid UUID."));
            }
            return new Result(created, Outcome.CREATED, null);
        } catch (IOException | RuntimeException e) {
            // A read-only data directory must not stop the server booting.
            return new Result(created, Outcome.EPHEMERAL,
                    "Could not persist " + file + " (" + e.getMessage() + "). Using a temporary "
                            + "identity that will change on restart; licensing will not stick "
                            + "until the directory is writable.");
        }
    }

    /** The folder holding the shared identity for a server whose mods live in {@code modsDir}. */
    public static Path sharedDir(Path modsDir) {
        return modsDir.resolve(SHARED_DIR);
    }

    /**
     * Return the UUID every Mystic mod on this server shares, kept in
     * {@code <modsDir>/.mystic/server-id.txt}, creating it on first run.
     *
     * <p>Before that file existed each mod kept its own {@code server-id.txt} in
     * its data folder. The first run that finds no shared file adopts one of those
     * ({@link Outcome#MIGRATED}): the only id if they all agree, otherwise
     * {@value #PREFERRED_MOD_DIR}'s, else the calling mod's own, else the first by
     * folder name - the others are named in the result's detail. Per-mod files are
     * looked for in the calling mod's data folder and in every folder under
     * {@code modsDir} whose name contains "mystic", and are left in place for
     * older mod versions that still read them. A new id is generated only when
     * there are none.
     *
     * <p>As with {@link #resolve}, a corrupt file is never overwritten: a corrupt
     * shared file, or per-mod files that are all corrupt, report
     * {@link Outcome#CORRUPT}.
     *
     * @param modsDir    the server's mods folder, the parent of every Mystic mod's data folder
     * @param ownDataDir the calling mod's data folder, searched even when it is elsewhere
     */
    public static Result resolveShared(Path modsDir, Path ownDataDir) {
        Path dir = sharedDir(modsDir);
        Path file = dir.resolve(IDENTITY_FILE);
        if (Files.exists(file)) {
            return resolve(dir);
        }

        Map<Path, UUID> legacy = new LinkedHashMap<>();
        List<Path> unreadable = new ArrayList<>();
        for (Path candidate : legacyFiles(modsDir, ownDataDir)) {
            Optional<UUID> id = load(candidate.getParent());
            if (id.isPresent()) {
                legacy.put(candidate, id.get());
            } else {
                unreadable.add(candidate);
            }
        }
        if (legacy.isEmpty()) {
            if (!unreadable.isEmpty()) {
                boolean one = unreadable.size() == 1;
                return new Result(null, Outcome.CORRUPT, joined(unreadable)
                        + (one ? " exists but does not" : " exist but do not") + " contain a valid UUID. "
                        + (one ? "It has" : "They have") + " been left untouched so an existing license "
                        + "is not orphaned - fix or delete " + (one ? "it" : "them") + ", then "
                        + "re-register the server in the portal.");
            }
            return resolve(dir); // first run on this server: generate the shared id
        }

        Map.Entry<Path, UUID> kept = legacy.entrySet().iterator().next();
        UUID chosen = kept.getValue();
        try {
            Files.createDirectories(dir);
            if (!createAtomically(file, chosen + System.lineSeparator())) {
                return resolve(dir); // another mod migrated first: use the shared file
            }
        } catch (IOException | RuntimeException e) {
            return new Result(chosen, Outcome.LOADED, "Could not create " + file + " ("
                    + e.getMessage() + "); using the id in " + kept.getKey() + " until it can be.");
        }
        return new Result(chosen, Outcome.MIGRATED, migrationDetail(kept, legacy, unreadable));
    }

    /**
     * Per-mod identity files in preference order: {@value #PREFERRED_MOD_DIR}'s,
     * the calling mod's, then other Mystic mods' by folder name.
     */
    private static List<Path> legacyFiles(Path modsDir, Path ownDataDir) {
        List<Path> dirs = new ArrayList<>();
        dirs.add(modsDir.resolve(PREFERRED_MOD_DIR));
        dirs.add(ownDataDir);
        if (Files.isDirectory(modsDir)) {
            try (Stream<Path> children = Files.list(modsDir)) {
                children.filter(Files::isDirectory)
                        .filter(child -> !child.getFileName().toString().equals(SHARED_DIR))
                        .filter(child -> child.getFileName().toString()
                                .toLowerCase(Locale.ROOT).contains("mystic"))
                        .sorted()
                        .forEach(dirs::add);
            } catch (IOException | RuntimeException e) {
                // An unlistable mods folder leaves just the two known locations.
            }
        }
        Set<Path> seen = new LinkedHashSet<>();
        List<Path> files = new ArrayList<>();
        for (Path dir : dirs) {
            Path file = dir.resolve(IDENTITY_FILE);
            if (Files.isRegularFile(file) && seen.add(file.toAbsolutePath().normalize())) {
                files.add(file);
            }
        }
        return files;
    }

    /** Null when every per-mod file agreed: nothing for the operator to act on. */
    private static String migrationDetail(Map.Entry<Path, UUID> kept, Map<Path, UUID> legacy,
                                          List<Path> unreadable) {
        List<String> ignored = new ArrayList<>();
        legacy.forEach((file, uuid) -> {
            if (!uuid.equals(kept.getValue())) {
                ignored.add(uuid + " (" + file + ")");
            }
        });
        if (ignored.isEmpty() && unreadable.isEmpty()) {
            return null;
        }
        StringBuilder detail = new StringBuilder("Mystic mods on this server kept different "
                + "licensing ids; all of them now share " + kept.getValue() + " from " + kept.getKey()
                + ".");
        if (!ignored.isEmpty()) {
            detail.append(" Not adopted: ").append(String.join(", ", ignored)).append(". A license ")
                    .append("bound to one of those keeps working for the mod that kept it, but ")
                    .append("should be moved to the shared id with the portal's server-replacement ")
                    .append("flow.");
        }
        if (!unreadable.isEmpty()) {
            detail.append(" Unreadable and ignored: ").append(joined(unreadable)).append('.');
        }
        return detail.toString();
    }

    private static String joined(List<Path> files) {
        return String.join(", ", files.stream().map(Path::toString).toList());
    }

    /**
     * Write a {@code license-request.json} the operator can upload to the portal
     * instead of typing the UUID by hand.
     *
     * <p>Shape and field names must match the portal's importer
     * ({@code licenseRequestFileSchema}); it rejects unknown fields.
     *
     * @param serverName optional friendly name, may be null
     */
    public static Path writeLicenseRequest(Path dataDir,
                                           UUID serverUuid,
                                           String serverName,
                                           String productId,
                                           String modVersion) throws IOException {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);

        StringBuilder json = new StringBuilder(256);
        json.append("{\n");
        json.append("  \"format_version\": 1,\n");
        appendField(json, "server_uuid", serverUuid.toString().toLowerCase(Locale.ROOT), true);
        if (serverName != null && !serverName.isBlank()) {
            appendField(json, "server_name", trimTo(serverName, 64), true);
        }
        appendField(json, "product_id", productId, true);
        if (modVersion != null && !modVersion.isBlank()) {
            appendField(json, "mod_version", trimTo(modVersion, 32), true);
        }
        appendField(json, "request_nonce",
                Base64.getUrlEncoder().withoutPadding().encodeToString(nonce), true);
        appendField(json, "created_at", Instant.now().toString(), false);
        json.append("}\n");

        Path file = dataDir.resolve(REQUEST_FILE);
        Files.createDirectories(dataDir);
        writeAtomically(file, json.toString());
        return file;
    }

    /** What {@link #resolve} or {@link #resolveShared} did. */
    public enum Outcome {
        /** An existing identity was read. The normal case. */
        LOADED,
        /** First run: a new identity was generated and persisted. */
        CREATED,
        /** First run of the shared identity: a per-mod identity was adopted into it. */
        MIGRATED,
        /** The identity file is unreadable or malformed and was left alone. */
        CORRUPT,
        /** An identity was generated but could not be saved. */
        EPHEMERAL
    }

    /** Result of {@link #resolve} and {@link #resolveShared}. {@code uuid} is null only for {@link Outcome#CORRUPT}. */
    public record Result(UUID uuid, Outcome outcome, String detail) {

        public Optional<UUID> optional() {
            return Optional.ofNullable(uuid);
        }

        /** Canonical lowercase form, which is what the portal binds licenses to. */
        public Optional<String> canonical() {
            return optional().map(value -> value.toString().toLowerCase(Locale.ROOT));
        }
    }

    // ------------------------------------------------------------------ util

    /**
     * Like {@link #writeAtomically} but never replaces an existing target.
     *
     * @return {@code false} if the target already existed (and was left alone)
     */
    private static boolean createAtomically(Path target, String content) throws IOException {
        Path temp = Files.createTempFile(target.toAbsolutePath().getParent(),
                target.getFileName() + ".", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                // A hard link appears atomically and fails if the target exists. (An
                // atomic move would not do: on Windows it replaces an existing file.)
                Files.createLink(target, temp);
            } catch (UnsupportedOperationException | FileSystemException e) {
                if (e instanceof FileAlreadyExistsException exists) {
                    throw exists;
                }
                Files.move(temp, target); // no REPLACE_EXISTING: fails if it exists
            }
            return true;
        } catch (FileAlreadyExistsException e) {
            return false;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Write via a temporary file plus a move, so a crash mid-write cannot leave
     * a half-written file behind.
     */
    private static void writeAtomically(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void appendField(StringBuilder out, String key, String value, boolean comma) {
        out.append("  \"").append(key).append("\": ");
        escape(out, value);
        out.append(comma ? ",\n" : "\n");
    }

    private static String trimTo(String value, int max) {
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static void escape(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
