package org.hyzionstudios.mysticessentials.modules.tutorial.scene;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Stream;

import org.hyzionstudios.mysticessentials.core.MysticCore;

/**
 * Resolves a machinima {@code sceneId} to its serialized scene bytes so the
 * server can ship the scene inline in the {@code UpdateMachinimaScene} Play
 * packet, rather than relying on a scene the client happens to have locally.
 *
 * <p>Two well-known locations give "easy pathing" (checked in order):</p>
 * <ol>
 *   <li><b>Operator override</b>:
 *       {@code mods/MysticEssentials/modules/tutorial/scenes/<sceneId>.json}
 *       (also {@code .scene} / {@code .machinima}). Drop a scene exported by the
 *       in-game editor (from {@code %APPDATA%/Hytale/UserData/Scenes/}) straight
 *       in here to add or replace it without rebuilding the mod, or use
 *       {@link #importScene} to copy + validate one from elsewhere.</li>
 *   <li><b>Bundled asset</b>: {@code /Common/Resources/MysticEssentials/Machinima/<sceneId>.json}
 *       shipped inside the mod jar (the MysticEssentials asset pack).</li>
 * </ol>
 *
 * <p>The stored bytes are the scene's UTF-8 JSON (the same format the editor
 * writes); see {@link MachinimaSceneDocument}. Results (including "not found")
 * are cached; a missing scene is not an error — the provider then sends a
 * name-only Play packet so a client-side scene of the same name still works.</p>
 */
public final class MachinimaSceneAssets {

    /** Checked in order; {@code .json} first since that is the editor's own format. */
    private static final String[] EXTENSIONS = {".json", ".scene", ".machinima"};
    private static final String BUNDLED_ROOT = "/Common/Resources/MysticEssentials/Machinima/";
    /** Sentinel so a cached "not found" isn't retried from disk every play. */
    private static final byte[] MISSING = new byte[0];

    private final MysticCore core;
    private final Path sceneDir;
    private final Path importDir;
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    /** Outcome of an {@link #importDropFolder()} scan. */
    public record ImportResult(List<String> imported, Map<String, String> failed) {
    }

    public MachinimaSceneAssets(MysticCore core, String moduleId) {
        this.core = core;
        this.sceneDir = core.paths().moduleConfigDir(moduleId).resolve("scenes");
        this.importDir = sceneDir.resolve("import");
        try {
            Files.createDirectories(importDir); // Creates sceneDir too.
        } catch (IOException e) {
            core.log(Level.WARNING, "[tutorial] Could not create machinima scenes dir "
                    + sceneDir + ": " + e.getMessage());
        }
    }

    /** Operator-editable directory where scene files may be dropped. */
    public Path sceneDir() {
        return sceneDir;
    }

    /** Drop-folder ({@code scenes/import/}) scanned by {@link #importDropFolder()}. */
    public Path importDir() {
        return importDir;
    }

    /**
     * @return the serialized scene bytes for {@code sceneId}, or {@code null} if
     *         no asset exists in either location (send a name-only packet then).
     */
    public byte[] load(String sceneId) {
        if (sceneId == null || sceneId.isBlank()) {
            return null;
        }
        byte[] cached = cache.get(sceneId);
        if (cached != null) {
            return cached == MISSING ? null : cached;
        }
        byte[] bytes = readFromDisk(sceneId);
        if (bytes == null) {
            bytes = readFromBundle(sceneId);
        }
        cache.put(sceneId, bytes == null ? MISSING : bytes);
        return bytes;
    }

    /**
     * Loads a scene and parses it into a {@link MachinimaSceneDocument} (for
     * relocation, inspection, or validation).
     *
     * @return the parsed document, or {@code null} if the scene is missing or is
     *         not valid scene JSON.
     */
    public MachinimaSceneDocument loadDocument(String sceneId) {
        byte[] bytes = load(sceneId);
        if (bytes == null) {
            return null;
        }
        try {
            return MachinimaSceneDocument.parse(bytes);
        } catch (IllegalArgumentException invalid) {
            core.log(Level.WARNING, "[tutorial] Scene '" + sceneId + "' is not valid scene JSON: "
                    + invalid.getMessage());
            return null;
        }
    }

    /**
     * Imports a scene file (e.g. one exported to
     * {@code %APPDATA%/Hytale/UserData/Scenes/}) into the operator scenes
     * directory as {@code <sceneId>.json}, validating that it parses as a
     * machinima scene first.
     *
     * @param source   the file to import; must exist and parse as a scene.
     * @param sceneId  target id; when {@code null}/blank, the scene's own
     *                 {@code Name} (sanitized) is used.
     * @return the id the scene was stored under.
     * @throws IOException              if the source cannot be read or the copy fails.
     * @throws IllegalArgumentException if the source is not a valid scene.
     */
    public String importScene(Path source, String sceneId) throws IOException {
        if (source == null || !Files.isRegularFile(source)) {
            throw new IOException("no such scene file: " + source);
        }
        byte[] bytes = Files.readAllBytes(source);
        MachinimaSceneDocument document = MachinimaSceneDocument.parse(bytes); // validates.
        String id = sanitizeId(sceneId == null || sceneId.isBlank()
                ? document.name(stripExtension(source.getFileName().toString()))
                : sceneId);
        if (id.isBlank()) {
            throw new IllegalArgumentException("could not derive a scene id");
        }
        Path target = sceneDir.resolve(id + ".json");
        Files.write(target, bytes);
        cache.remove(id); // Force a re-read on next play.
        return id;
    }

    /**
     * Imports every scene file in the drop folder ({@code scenes/import/}) into
     * the scenes directory, each stored under its (sanitized) scene {@code Name}.
     * Source files are left in place, so re-running simply refreshes them.
     */
    public ImportResult importDropFolder() {
        List<String> imported = new ArrayList<>();
        Map<String, String> failed = new java.util.LinkedHashMap<>();
        try (Stream<Path> files = Files.list(importDir)) {
            files.filter(Files::isRegularFile)
                    .filter(file -> hasSceneExtension(file.getFileName().toString()))
                    .forEach(file -> {
                        try {
                            imported.add(importScene(file, null));
                        } catch (IOException | IllegalArgumentException e) {
                            failed.put(file.getFileName().toString(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            core.log(Level.WARNING, "[tutorial] Could not scan import folder " + importDir + ": "
                    + e.getMessage());
        }
        return new ImportResult(imported, failed);
    }

    /** @return the sorted ids of every scene file currently in the scenes directory. */
    public List<String> listScenes() {
        TreeSet<String> ids = new TreeSet<>();
        try (Stream<Path> files = Files.list(sceneDir)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                String fileName = file.getFileName().toString();
                String lower = fileName.toLowerCase(Locale.ROOT);
                for (String extension : EXTENSIONS) {
                    if (lower.endsWith(extension)) {
                        ids.add(fileName.substring(0, fileName.length() - extension.length()));
                        break;
                    }
                }
            });
        } catch (IOException e) {
            core.log(Level.WARNING, "[tutorial] Could not list scenes in " + sceneDir + ": "
                    + e.getMessage());
        }
        return new ArrayList<>(ids);
    }

    /** Drops cached scenes so edited files are re-read on the next tutorial reload. */
    public void clearCache() {
        cache.clear();
    }

    private static boolean hasSceneExtension(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String extension : EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /** Makes a scene name safe for a filename/id: keep word chars, collapse the rest to '_'. */
    private static String sanitizeId(String raw) {
        return raw.trim().replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("^_+|_+$", "");
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private byte[] readFromDisk(String sceneId) {
        for (String extension : EXTENSIONS) {
            Path file = sceneDir.resolve(sceneId + extension);
            if (Files.isRegularFile(file)) {
                try {
                    return Files.readAllBytes(file);
                } catch (IOException e) {
                    core.log(Level.WARNING, "[tutorial] Could not read machinima scene "
                            + file.getFileName() + ": " + e.getMessage());
                }
            }
        }
        return null;
    }

    private byte[] readFromBundle(String sceneId) {
        for (String extension : EXTENSIONS) {
            String resource = BUNDLED_ROOT + sceneId + extension;
            try (InputStream in = getClass().getResourceAsStream(resource)) {
                if (in != null) {
                    return in.readAllBytes();
                }
            } catch (IOException e) {
                core.log(Level.WARNING, "[tutorial] Could not read bundled machinima scene "
                        + resource + ": " + e.getMessage());
            }
        }
        return null;
    }
}
