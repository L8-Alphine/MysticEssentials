package org.hyzionstudios.mysticessentials.modules.patchnotes;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.util.Json;

/**
 * Loads patch notes from
 * {@code mods/MysticEssentials/modules/patchnotes/patches/*.json} and generates a
 * small set of example patches on first startup (so operators have a working
 * template to copy). A malformed or id-less file is logged and skipped, never
 * fatal; existing owner files are never overwritten.
 */
public final class PatchNoteFileLoader {

    private final MysticCore core;
    private final String moduleId;

    /** Files that failed to parse on the last {@link #load} (for {@code /patchnotes reload} feedback). */
    private final List<String> fileErrors = new ArrayList<>();

    public PatchNoteFileLoader(MysticCore core, String moduleId) {
        this.core = core;
        this.moduleId = moduleId;
    }

    public Path patchesDir() {
        return core.paths().moduleConfigDir(moduleId).resolve("patches");
    }

    public List<String> fileErrors() {
        return List.copyOf(fileErrors);
    }

    /**
     * Generates the examples on first startup (when the patches directory does not
     * exist yet), then loads every patch file. Each note remembers its source file.
     */
    public List<PatchNote> load(boolean generateExamples) {
        boolean firstStartup = !Files.isDirectory(patchesDir());
        if (firstStartup) {
            try {
                Files.createDirectories(patchesDir());
                if (generateExamples) {
                    writeExamples();
                }
            } catch (IOException e) {
                core.log(Level.SEVERE, "[" + moduleId + "] Could not create patches directory: "
                        + e.getMessage());
            }
        }

        List<PatchNote> notes = new ArrayList<>();
        fileErrors.clear();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(patchesDir(), "*.json")) {
            for (Path file : stream) {
                try {
                    PatchNote note = Json.readFile(file, PatchNote.class);
                    if (note == null || note.id == null || note.id.isBlank()) {
                        fileErrors.add(file.getFileName() + ": missing or blank 'id'");
                        core.log(Level.WARNING, "[" + moduleId + "] Skipping "
                                + file.getFileName() + ": missing or blank 'id'.");
                        continue;
                    }
                    note.sourceFile = file;
                    notes.add(note);
                } catch (Exception e) {
                    fileErrors.add(file.getFileName() + ": " + e.getMessage());
                    core.log(Level.WARNING, "[" + moduleId + "] Skipping invalid patch file "
                            + file.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            core.log(Level.SEVERE, "[" + moduleId + "] Failed to read " + patchesDir()
                    + ": " + e.getMessage());
        }
        return notes;
    }

    // ----- Example generation ------------------------------------------------------

    private void writeExamples() {
        writeExample("patch-1.0.0.json", exampleWelcome());
        writeExample("patch-1.0.1.json", exampleFixes());
        core.log(Level.INFO, "[" + moduleId + "] Generated example patch notes in " + patchesDir());
    }

    private void writeExample(String fileName, PatchNote note) {
        try {
            Json.writeFile(patchesDir().resolve(fileName), note);
        } catch (IOException e) {
            core.log(Level.WARNING, "[" + moduleId + "] Could not write example "
                    + fileName + ": " + e.getMessage());
        }
    }

    private static PatchNote exampleWelcome() {
        PatchNote note = new PatchNote();
        note.id = "welcome-1-0-0";
        note.title = "Welcome to the Server!";
        note.version = "1.0.0";
        note.date = "2026-01-01";
        note.author = "Server Team";
        note.pinned = true;
        note.priority = 100;
        note.showOnLogin = true;
        note.summary = "The first release. Here is what you can do and where to start.";
        note.tags.add("welcome");
        note.tags.add("release");
        note.categories.add("additions");
        note.categories.add("changes");

        PatchNote.Section intro = new PatchNote.Section();
        intro.type = "changes";
        intro.title = "GETTING STARTED";
        intro.body = "Welcome aboard! This panel shows the latest server updates.\n\n"
                + "+ Type `/patchnotes` any time to reopen this window.\n"
                + "+ New notes are marked unread until you read them.\n"
                + "- Use the search box on the left to find an older update.";
        note.sections.add(intro);

        PatchNote.Section features = new PatchNote.Section();
        features.type = "additions";
        features.title = "WHAT'S NEW";
        features.body = "**Everything is here to make your stay great:**\n\n"
                + "- Homes, warps, and quick teleports.\n"
                + "- A full mail system with item rewards.\n"
                + "- Kits, player vaults, and much more.";
        note.sections.add(features);
        return note;
    }

    private static PatchNote exampleFixes() {
        PatchNote note = new PatchNote();
        note.id = "patch-1-0-1";
        note.title = "Patch 1.0.1 - Stability";
        note.version = "1.0.1";
        note.date = "2026-01-08";
        note.author = "Server Team";
        note.priority = 80;
        note.showOnLogin = true;
        note.summary = "A small round of fixes and tweaks.";
        note.tags.add("fixes");
        note.categories.add("fixes");
        note.categories.add("changes");
        note.categories.add("removals");

        PatchNote.Section fixes = new PatchNote.Section();
        fixes.type = "fixes";
        fixes.title = "FIXES";
        fixes.body = "- Fixed the home list not refreshing after a rename.\n"
                + "- Fixed a rare error when claiming mail rewards with a full inventory.";
        note.sections.add(fixes);

        PatchNote.Section changes = new PatchNote.Section();
        changes.type = "changes";
        changes.title = "CHANGES";
        changes.body = "- Improved the warp menu layout.\n"
                + "- Teleport warmups now show a clearer countdown.";
        note.sections.add(changes);

        PatchNote.Section removals = new PatchNote.Section();
        removals.type = "removals";
        removals.title = "REMOVALS";
        removals.body = "- Removed an unused debug command.";
        note.sections.add(removals);
        return note;
    }
}
