package org.hyzionstudios.mysticessentials.modules.customcontent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.util.Json;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Persistent dialog catalog, compatible with QuestLinesDialog's dialogs.json. */
final class DialogStore {

    private final MysticCore core;
    private final Path file;
    private final Map<String, DialogDefinition> dialogs = new LinkedHashMap<>();

    DialogStore(MysticCore core, Path file) {
        this.core = core;
        this.file = file;
    }

    void load() {
        dialogs.clear();
        try {
            JsonElement raw = Json.readFile(file);
            if (raw == null || !raw.isJsonObject()) {
                return;
            }
            for (Map.Entry<String, JsonElement> entry : raw.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                DialogDefinition dialog = Json.gson().fromJson(entry.getValue(), DialogDefinition.class);
                if (dialog == null) {
                    continue;
                }
                dialog.normalize(entry.getKey());
                if (!dialog.id.isBlank()) {
                    dialogs.put(dialog.id, dialog);
                }
            }
        } catch (Exception e) {
            core.log(Level.WARNING, "[customcontent] Could not load dialogs.json: " + e.getMessage());
        }
    }

    boolean importStandalone(Path standaloneFile) {
        if (Files.exists(file) || !Files.isRegularFile(standaloneFile)) {
            return false;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.copy(standaloneFile, file, StandardCopyOption.COPY_ATTRIBUTES);
            load();
            return true;
        } catch (IOException e) {
            core.log(Level.WARNING, "[customcontent] Could not import standalone dialogs: " + e.getMessage());
            return false;
        }
    }

    void save() {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, DialogDefinition> entry : dialogs.entrySet()) {
            root.add(entry.getKey(), Json.toTree(entry.getValue()));
        }
        try {
            Json.writeFile(file, root);
        } catch (IOException e) {
            core.log(Level.SEVERE, "[customcontent] Could not save dialogs.json: " + e.getMessage());
        }
    }

    DialogDefinition get(String id) {
        return dialogs.get(DialogDefinition.safeId(id));
    }

    boolean contains(String id) {
        return get(id) != null;
    }

    void put(DialogDefinition dialog) {
        dialog.normalize(dialog.id);
        if (!dialog.id.isBlank()) {
            dialogs.put(dialog.id, dialog);
        }
    }

    DialogDefinition remove(String id) {
        return dialogs.remove(DialogDefinition.safeId(id));
    }

    List<String> ids() {
        return new ArrayList<>(dialogs.keySet());
    }

    Map<String, DialogDefinition> all() {
        return Collections.unmodifiableMap(dialogs);
    }
}
