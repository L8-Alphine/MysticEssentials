package org.hyzionstudios.mysticessentials.modules.customcontent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.stream.Stream;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.util.Json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Converts linear builder documents to and from QuestLines quest JSON. */
final class CustomDialogExporter {

    static final String MARKER = "MysticEssentialsDialog";

    private final MysticCore core;
    private final Path questsDir;

    CustomDialogExporter(MysticCore core, Path questLinesDirectory) {
        this.core = core;
        this.questsDir = questLinesDirectory.resolve("quests");
    }

    void export(DialogDefinition dialog) throws IOException {
        if (dialog == null || dialog.id.isBlank() || dialog.pages.isEmpty()) {
            return;
        }
        Files.createDirectories(questsDir);
        Json.writeFile(questsDir.resolve(dialog.id + ".json"), toQuest(dialog));
    }

    void exportAll(Iterable<DialogDefinition> dialogs) {
        for (DialogDefinition dialog : dialogs) {
            try {
                export(dialog);
            } catch (IOException e) {
                core.log(Level.WARNING, "[customcontent] Could not export dialog '" + dialog.id + "': "
                        + e.getMessage());
            }
        }
    }

    boolean delete(String id) {
        try {
            return Files.deleteIfExists(questsDir.resolve(DialogDefinition.safeId(id) + ".json"));
        } catch (IOException e) {
            core.log(Level.WARNING, "[customcontent] Could not delete exported dialog '" + id + "': "
                    + e.getMessage());
            return false;
        }
    }

    Map<String, DialogDefinition> scanExports() {
        Map<String, DialogDefinition> found = new LinkedHashMap<>();
        if (!Files.isDirectory(questsDir)) {
            return found;
        }
        try (Stream<Path> files = Files.list(questsDir)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(path -> {
                DialogDefinition dialog = importQuest(path);
                if (dialog != null) {
                    found.put(dialog.id, dialog);
                }
            });
        } catch (IOException e) {
            core.log(Level.WARNING, "[customcontent] Could not scan compatible dialog exports: "
                    + e.getMessage());
        }
        return found;
    }

    private JsonObject toQuest(DialogDefinition dialog) {
        JsonObject quest = new JsonObject();
        quest.addProperty("QuestLinesDialog", true);
        quest.addProperty(MARKER, true);
        quest.addProperty("QuestlineId", "");
        quest.addProperty("QuestlineTitle", "");
        quest.addProperty("Title", dialog.title);
        quest.addProperty("Description", "");
        quest.addProperty("Rewards", "");
        quest.add("Requirements", new JsonArray());
        quest.add("Actions", new JsonArray());
        quest.addProperty("Repeatable", false);

        JsonArray pageIds = new JsonArray();
        JsonObject pageData = new JsonObject();
        for (int index = 0; index < dialog.pages.size(); index++) {
            String pageId = pageId(dialog.id, index);
            pageIds.add(pageId);
            pageData.add(pageId, toPage(dialog, index));
        }
        quest.add("Pages", pageIds);
        quest.add("PageData", pageData);
        return quest;
    }

    private JsonObject toPage(DialogDefinition dialog, int index) {
        DialogDefinition.Page page = dialog.pages.get(index);
        boolean last = index == dialog.pages.size() - 1;
        JsonObject json = new JsonObject();
        json.addProperty("Id", pageId(dialog.id, index));
        json.addProperty("Title", "");
        json.addProperty("Name", dialog.npcName);
        json.add("Requirements", new JsonArray());
        json.addProperty("Dialog", page.text);
        json.add("LoadActions", new JsonArray());
        json.addProperty("GoalText", "");
        json.addProperty("AutoTrigger", false);
        json.addProperty("DialogWidth", dialog.dialogWidth);
        json.addProperty("ShowContinue", false);
        json.add("Objectives", new JsonArray());

        JsonArray responses = new JsonArray();
        JsonObject navigation = new JsonObject();
        navigation.add("Requirements", new JsonArray());
        navigation.addProperty("Text", last ? "Close" : page.continueLabel);
        JsonArray navigationActions = new JsonArray();
        if (!last) {
            navigationActions.add("page:" + pageId(dialog.id, index + 1));
        }
        navigation.add("Actions", navigationActions);
        responses.add(navigation);

        for (DialogDefinition.Button button : page.buttons) {
            if (button.text.isBlank()) {
                continue;
            }
            JsonObject response = new JsonObject();
            response.addProperty("Text", button.text);
            response.add("Requirements", new JsonArray());
            JsonArray actions = new JsonArray();
            button.actions.forEach(actions::add);
            response.add("Actions", actions);
            responses.add(response);
        }
        json.add("Responses", responses);
        return json;
    }

    private DialogDefinition importQuest(Path file) {
        try {
            JsonElement raw = Json.readFile(file);
            if (raw == null || !raw.isJsonObject()) {
                return null;
            }
            JsonObject quest = raw.getAsJsonObject();
            boolean supported = bool(quest, "QuestLinesDialog") || bool(quest, MARKER);
            if (!supported) {
                return null;
            }
            String filename = file.getFileName().toString();
            String id = filename.substring(0, filename.length() - ".json".length());
            DialogDefinition dialog = new DialogDefinition(id);
            dialog.title = string(quest, "Title");
            if (!quest.has("Pages") || !quest.has("PageData")) {
                dialog.normalize(id);
                return dialog;
            }
            JsonArray ids = quest.getAsJsonArray("Pages");
            JsonObject data = quest.getAsJsonObject("PageData");
            for (int index = 0; index < ids.size(); index++) {
                String pageId = ids.get(index).getAsString();
                JsonObject source = data.has(pageId) && data.get(pageId).isJsonObject()
                        ? data.getAsJsonObject(pageId) : new JsonObject();
                if (index == 0) {
                    dialog.npcName = string(source, "Name");
                    dialog.dialogWidth = string(source, "DialogWidth");
                }
                DialogDefinition.Page page = new DialogDefinition.Page();
                page.text = string(source, "Dialog");
                JsonArray responses = source.has("Responses") && source.get("Responses").isJsonArray()
                        ? source.getAsJsonArray("Responses") : new JsonArray();
                if (index < ids.size() - 1 && !responses.isEmpty()) {
                    page.continueLabel = string(responses.get(0).getAsJsonObject(), "Text");
                }
                for (int responseIndex = 1; responseIndex < responses.size(); responseIndex++) {
                    JsonObject response = responses.get(responseIndex).getAsJsonObject();
                    DialogDefinition.Button button = new DialogDefinition.Button();
                    button.text = string(response, "Text");
                    if (response.has("Actions") && response.get("Actions").isJsonArray()) {
                        for (JsonElement action : response.getAsJsonArray("Actions")) {
                            button.actions.add(action.getAsString());
                        }
                    }
                    if (!button.text.isBlank()) {
                        page.buttons.add(button);
                    }
                }
                dialog.pages.add(page);
            }
            dialog.normalize(id);
            return dialog;
        } catch (Exception e) {
            core.log(Level.WARNING, "[customcontent] Could not import " + file.getFileName() + ": "
                    + e.getMessage());
            return null;
        }
    }

    private static String pageId(String dialogId, int index) {
        return dialogId + "_p" + (index + 1);
    }

    private static boolean bool(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsBoolean();
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }
}
