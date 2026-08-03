package org.hyzionstudios.mysticessentials.modules.customcontent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.ui.CompiledUiBlueprint;
import org.hyzionstudios.mysticessentials.api.ui.UiDiagnostic;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.customcontent.layout.LayoutParser;
import org.hyzionstudios.mysticessentials.modules.customcontent.layout.UiDocument;

/**
 * Loads declarative GUI documents from disk and compiles them into layout
 * trees.
 *
 * <p>Compilation happens here rather than at open time so a malformed document
 * is reported once at load, and so {@code reload} is the only thing that has to
 * touch the filesystem — opening a GUI is then a pure render of an in-memory
 * tree.</p>
 */
final class CustomGuiRepository {

    private static final String EXTENSION = ".gui.html";

    /**
     * Documents written into an empty GUI folder so a fresh install has working
     * references for the page, HUD and live-value features.
     */
    private static final String[] EXAMPLES = {
        "hub.gui.html", "nameplate.gui.html", "profilecard.gui.html"
    };

    private final MysticCore core;
    private final Path directory;
    private final Map<String, UiDocument> documents = new LinkedHashMap<>();
    private final List<UiDiagnostic> diagnostics = new ArrayList<>();
    private final Set<String> registeredBlueprints = new HashSet<>();
    private int maxNodes = 400;

    CustomGuiRepository(MysticCore core, Path directory) {
        this.core = core;
        this.directory = directory;
    }

    void maxNodes(int maxNodes) {
        this.maxNodes = Math.max(1, maxNodes);
    }

    /** Re-reads every document. @return the number successfully compiled. */
    int load() {
        documents.clear();
        diagnostics.clear();
        registeredBlueprints.forEach(core.getCustomUiService().registry()::unregister);
        registeredBlueprints.clear();
        try {
            Files.createDirectories(directory);
            try (var stream = Files.walk(directory)) {
                for (Path file : stream.filter(Files::isRegularFile)
                        .filter(CustomGuiRepository::supported).sorted().toList()) {
                    try {
                        String filename = file.getFileName().toString();
                        String stem = stem(filename);
                        String source = Files.readString(file, StandardCharsets.UTF_8);
                        CompiledUiBlueprint blueprint = core.getCustomUiService().compiler()
                                .compile(source, directory.relativize(file).toString());
                        diagnostics.addAll(blueprint.diagnostics());
                        if (!blueprint.valid()) {
                            core.log(Level.WARNING, "[customcontent] Invalid Custom UI " + filename
                                    + ": " + blueprint.diagnostics().stream()
                                            .filter(d -> d.severity() == UiDiagnostic.Severity.ERROR)
                                            .map(UiDiagnostic::message).toList());
                            continue;
                        }
                        core.getCustomUiService().registry().register(blueprint);
                        registeredBlueprints.add(blueprint.id());
                        if (blueprint.kind() != CompiledUiBlueprint.Kind.SURFACE) {
                            continue;
                        }
                        UiDocument document = LayoutParser.parse(
                                source, stem, maxNodes);
                        if (document != null) {
                            documents.put(document.id, document);
                        } else {
                            core.log(Level.WARNING, "[customcontent] CustomGUI " + filename
                                    + " has no root element; skipped.");
                        }
                    } catch (Exception e) {
                        core.log(Level.WARNING, "[customcontent] Invalid CustomGUI "
                                + file.getFileName() + ": " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            core.log(Level.WARNING, "[customcontent] Could not load CustomGUI documents: "
                    + e.getMessage());
        }
        return documents.size();
    }

    private static boolean supported(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(EXTENSION) || name.endsWith(".xml");
    }

    private static String stem(String filename) {
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(EXTENSION)) return filename.substring(0, filename.length() - EXTENSION.length());
        if (lower.endsWith(".xml")) return filename.substring(0, filename.length() - 4);
        return filename;
    }

    /**
     * Writes the bundled example documents when the folder holds none, so the
     * first {@code /customguis list} is not empty. Existing files are never
     * touched, so an admin who deletes an example keeps it deleted.
     *
     * @return the number of examples written
     */
    int seedExamples() {
        int written = 0;
        try {
            Files.createDirectories(directory);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*" + EXTENSION)) {
                if (stream.iterator().hasNext()) {
                    return 0;
                }
            }
            for (String example : EXAMPLES) {
                try (var source = CustomGuiRepository.class
                        .getResourceAsStream("/examples/customguis/" + example)) {
                    if (source == null) {
                        continue;
                    }
                    Files.copy(source, directory.resolve(example));
                    written++;
                }
            }
        } catch (IOException e) {
            core.log(Level.WARNING, "[customcontent] Could not write example GUIs: " + e.getMessage());
        }
        return written;
    }

    int importStandalone(Path sourceDirectory) {
        if (!Files.isDirectory(sourceDirectory)) {
            return 0;
        }
        int imported = 0;
        try {
            Files.createDirectories(directory);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(sourceDirectory, "*" + EXTENSION)) {
                for (Path source : stream) {
                    Path target = directory.resolve(source.getFileName().toString());
                    if (Files.exists(target)) {
                        continue;
                    }
                    Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
                    imported++;
                }
            }
        } catch (IOException e) {
            core.log(Level.WARNING, "[customcontent] Could not import standalone GUIs: "
                    + e.getMessage());
        }
        return imported;
    }

    UiDocument get(String id) {
        return documents.get(LayoutParser.safeId(id));
    }

    Map<String, UiDocument> all() {
        return Collections.unmodifiableMap(documents);
    }

    List<UiDiagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    Path directory() {
        return directory;
    }
}
