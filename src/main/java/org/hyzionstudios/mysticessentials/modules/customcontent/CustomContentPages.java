package org.hyzionstudios.mysticessentials.modules.customcontent;

import static org.hyzionstudios.mysticessentials.platform.ui.MysticPage.uiText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.platform.ui.MysticPage;

import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** Native Custom UI page for dialog authoring. */
final class CustomContentPages {

    private static final String DIALOG_UI = "MysticEssentials/CustomDialogsBuilder.ui";
    private static final String EDITOR_ROW_UI = "MysticEssentials/CustomDialogEditorRow.ui";

    private CustomContentPages() {
    }

    static final class DialogBuilderPage extends MysticPage {

        private final CustomContentModule module;
        private final String dialogId;
        private final int pageIndex;
        private final int buttonIndex;
        private final boolean confirmDelete;

        DialogBuilderPage(MysticCore core, CustomContentModule module, PlayerRef player,
                String dialogId, int pageIndex, int buttonIndex, boolean confirmDelete) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.module = module;
            this.dialogId = dialogId;
            this.pageIndex = pageIndex;
            this.buttonIndex = buttonIndex;
            this.confirmDelete = confirmDelete;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(DIALOG_UI);
            DialogDefinition dialog = module.dialogs().get(dialogId);
            cmd.set("#DialogDropdown.Entries", dialogEntries());
            cmd.set("#DialogDropdown.Value", dialog == null ? "" : dialog.id);
            cmd.set("#DeleteDialogButton.TextSpans", uiText("#DeleteDialogButton.TextSpans", confirmDelete ? "Confirm Delete" : "Delete"));

            boolean selected = dialog != null;
            cmd.set("#DialogEditor.Visible", selected);
            cmd.set("#PageEditor.Visible", selected);
            cmd.set("#ButtonPanel.Visible", selected && validPage(dialog));

            if (selected) {
                cmd.set("#DialogTitle.Value", dialog.title);
                cmd.set("#NpcName.Value", dialog.npcName);
                cmd.set("#DialogWidth.Entries", widthEntries());
                cmd.set("#DialogWidth.Value", dialog.dialogWidth);
                buildPages(dialog, cmd, event);
                if (validPage(dialog)) {
                    DialogDefinition.Page page = dialog.pages.get(pageIndex);
                    cmd.set("#PageHeading.TextSpans", uiText("#PageHeading.TextSpans", "Page " + (pageIndex + 1)));
                    cmd.set("#PageText.Value", page.text);
                    cmd.set("#ContinueLabel.Value", page.continueLabel);
                    buildButtons(page, cmd, event);
                    boolean validButton = validButton(page);
                    cmd.set("#ButtonEditor.Visible", validButton);
                    if (validButton) {
                        DialogDefinition.Button button = page.buttons.get(buttonIndex);
                        cmd.set("#ButtonText.Value", button.text);
                        cmd.set("#ButtonActions.Value", String.join(", ", button.actions));
                    }
                } else {
                    cmd.set("#PageHeading.TextSpans", uiText("#PageHeading.TextSpans", "Add a page to begin"));
                    cmd.set("#ButtonEditor.Visible", false);
                }
            }

            bind(event, "#DialogDropdown", CustomUIEventBindingType.ValueChanged, data("dialog-select"));
            bind(event, "#CreateDialogButton", CustomUIEventBindingType.Activating, data("dialog-create")
                    .append("@newId", "#NewDialogId.Value"));
            bind(event, "#DeleteDialogButton", CustomUIEventBindingType.Activating, data("dialog-delete"));
            bind(event, "#AssignButton", CustomUIEventBindingType.Activating, data("dialog-assign"));
            bind(event, "#AddPageButton", CustomUIEventBindingType.Activating, data("page-add"));
            bind(event, "#RemovePageButton", CustomUIEventBindingType.Activating, data("page-remove"));
            bind(event, "#MovePageUpButton", CustomUIEventBindingType.Activating, data("page-up"));
            bind(event, "#MovePageDownButton", CustomUIEventBindingType.Activating, data("page-down"));
            bind(event, "#AddButtonButton", CustomUIEventBindingType.Activating, data("button-add"));
            bind(event, "#RemoveButtonButton", CustomUIEventBindingType.Activating, data("button-remove"));
            bind(event, "#MoveButtonUpButton", CustomUIEventBindingType.Activating, data("button-up"));
            bind(event, "#MoveButtonDownButton", CustomUIEventBindingType.Activating, data("button-down"));
            bind(event, "#SaveButton", CustomUIEventBindingType.Activating, data("save"));
            bind(event, "#SaveCloseButton", CustomUIEventBindingType.Activating, data("close"));

            EventData autoSave = data("save");
            bind(event, "#DialogTitle", CustomUIEventBindingType.ValueChanged, autoSave);
            bind(event, "#NpcName", CustomUIEventBindingType.ValueChanged, autoSave);
            bind(event, "#DialogWidth", CustomUIEventBindingType.ValueChanged, autoSave);
            bind(event, "#PageText", CustomUIEventBindingType.ValueChanged, autoSave);
            bind(event, "#ContinueLabel", CustomUIEventBindingType.ValueChanged, autoSave);
            bind(event, "#ButtonText", CustomUIEventBindingType.ValueChanged, autoSave);
            bind(event, "#ButtonActions", CustomUIEventBindingType.ValueChanged, autoSave);
        }

        private void buildPages(DialogDefinition dialog, UICommandBuilder cmd, UIEventBuilder event) {
            cmd.set("#PageCount.TextSpans", uiText("#PageCount.TextSpans", dialog.pages.size() + " page(s)"));
            for (int index = 0; index < dialog.pages.size(); index++) {
                String row = "#PageList[" + index + "]";
                cmd.append("#PageList", EDITOR_ROW_UI);
                cmd.set(row + " #Label.TextSpans", uiText(row + " #Label.TextSpans", "Page " + (index + 1)));
                cmd.set(row + " #Selected.Visible", index == pageIndex);
                event.addEventBinding(CustomUIEventBindingType.Activating, row,
                        data("page-select").put("index", Integer.toString(index)));
            }
        }

        private void buildButtons(DialogDefinition.Page page, UICommandBuilder cmd, UIEventBuilder event) {
            cmd.set("#ButtonCount.TextSpans", uiText("#ButtonCount.TextSpans", page.buttons.size() + " optional button(s)"));
            for (int index = 0; index < page.buttons.size(); index++) {
                DialogDefinition.Button button = page.buttons.get(index);
                String row = "#ButtonList[" + index + "]";
                cmd.append("#ButtonList", EDITOR_ROW_UI);
                cmd.set(row + " #Label.TextSpans",
                        uiText(row + " #Label.TextSpans", button.text.isBlank() ? "Button " + (index + 1) : button.text));
                cmd.set(row + " #Selected.Visible", index == buttonIndex);
                event.addEventBinding(CustomUIEventBindingType.Activating, row,
                        data("button-select").put("index", Integer.toString(index)));
            }
        }

        private EventData data(String action) {
            return new EventData().put("action", action)
                    .append("@dialogValue", "#DialogDropdown.Value")
                    .append("@title", "#DialogTitle.Value")
                    .append("@npc", "#NpcName.Value")
                    .append("@width", "#DialogWidth.Value")
                    .append("@pageText", "#PageText.Value")
                    .append("@continueLabel", "#ContinueLabel.Value")
                    .append("@buttonText", "#ButtonText.Value")
                    .append("@buttonActions", "#ButtonActions.Value");
        }

        private static void bind(UIEventBuilder event, String selector, CustomUIEventBindingType type,
                EventData data) {
            event.addEventBinding(type, selector, data);
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            String action = string(payload, "action");
            saveVisibleFields(payload);
            DialogDefinition dialog = module.dialogs().get(dialogId);
            String nextDialog = dialogId;
            int nextPage = pageIndex;
            int nextButton = buttonIndex;
            boolean nextConfirm = false;

            switch (action) {
                case "dialog-select" -> {
                    nextDialog = field(payload, "dialogValue");
                    nextPage = -1;
                    nextButton = -1;
                }
                case "dialog-create" -> {
                    String id = DialogDefinition.safeId(field(payload, "newId"));
                    if (id.isBlank()) {
                        message("&cDialog ID cannot be empty.");
                    } else if (module.dialogs().contains(id)) {
                        message("&cDialog '" + id + "' already exists.");
                    } else {
                        DialogDefinition created = new DialogDefinition(id);
                        module.dialogs().put(created);
                        module.dialogs().save();
                        nextDialog = id;
                        nextPage = -1;
                        nextButton = -1;
                    }
                }
                case "dialog-delete" -> {
                    if (dialog != null && confirmDelete) {
                        module.dialogs().remove(dialog.id);
                        module.dialogs().save();
                        module.exporter().delete(dialog.id);
                        module.bridge().unbindAndReload(dialog.id);
                        nextDialog = null;
                        nextPage = -1;
                        nextButton = -1;
                    } else {
                        nextConfirm = dialog != null;
                    }
                }
                case "dialog-assign" -> {
                    if (dialog != null && !dialog.pages.isEmpty()) {
                        module.persistAndExport(dialog);
                        module.bridge().reloadQuests();
                        if (module.bridge().startAssignment(player, dialog.id)) {
                            close(ref, store);
                            return;
                        }
                        message("&cThe configured compatibility plugin is unavailable or does not support NPC assignment.");
                    }
                }
                case "page-add" -> {
                    if (dialog != null) {
                        dialog.pages.add(new DialogDefinition.Page());
                        nextPage = dialog.pages.size() - 1;
                        nextButton = -1;
                        module.persistAndExport(dialog);
                    }
                }
                case "page-remove" -> {
                    if (validPage(dialog)) {
                        dialog.pages.remove(pageIndex);
                        nextPage = Math.min(pageIndex, dialog.pages.size() - 1);
                        nextButton = -1;
                        module.persistAndExport(dialog);
                    }
                }
                case "page-up", "page-down" -> {
                    if (validPage(dialog)) {
                        int target = pageIndex + (action.endsWith("up") ? -1 : 1);
                        if (target >= 0 && target < dialog.pages.size()) {
                            Collections.swap(dialog.pages, pageIndex, target);
                            nextPage = target;
                            module.persistAndExport(dialog);
                        }
                    }
                }
                case "page-select" -> {
                    int selected = integer(payload, "index", -1);
                    if (dialog != null && selected >= 0 && selected < dialog.pages.size()) {
                        nextPage = selected;
                        nextButton = -1;
                    }
                }
                case "button-add" -> {
                    if (validPage(dialog)) {
                        DialogDefinition.Page page = dialog.pages.get(pageIndex);
                        page.buttons.add(new DialogDefinition.Button());
                        nextButton = page.buttons.size() - 1;
                        module.persistAndExport(dialog);
                    }
                }
                case "button-remove" -> {
                    if (validPage(dialog)) {
                        DialogDefinition.Page page = dialog.pages.get(pageIndex);
                        if (validButton(page)) {
                            page.buttons.remove(buttonIndex);
                            nextButton = Math.min(buttonIndex, page.buttons.size() - 1);
                            module.persistAndExport(dialog);
                        }
                    }
                }
                case "button-up", "button-down" -> {
                    if (validPage(dialog)) {
                        DialogDefinition.Page page = dialog.pages.get(pageIndex);
                        int target = buttonIndex + (action.endsWith("up") ? -1 : 1);
                        if (validButton(page) && target >= 0 && target < page.buttons.size()) {
                            Collections.swap(page.buttons, buttonIndex, target);
                            nextButton = target;
                            module.persistAndExport(dialog);
                        }
                    }
                }
                case "button-select" -> {
                    int selected = integer(payload, "index", -1);
                    if (validPage(dialog) && selected >= 0
                            && selected < dialog.pages.get(pageIndex).buttons.size()) {
                        nextButton = selected;
                    }
                }
                case "close" -> {
                    if (dialog != null) {
                        module.persistAndExport(dialog);
                    }
                    module.bridge().reloadQuests();
                    close(ref, store);
                    return;
                }
                case "save" -> {
                    // ValueChanged autosave intentionally leaves the current page in place.
                    return;
                }
                default -> {
                    return;
                }
            }
            reopen(ref, store, new DialogBuilderPage(core, module, player,
                    blankToNull(nextDialog), nextPage, nextButton, nextConfirm));
        }

        private void saveVisibleFields(JsonObject payload) {
            DialogDefinition dialog = module.dialogs().get(dialogId);
            if (dialog == null) {
                return;
            }
            dialog.title = field(payload, "title");
            dialog.npcName = field(payload, "npc");
            String width = field(payload, "width");
            dialog.dialogWidth = width.isBlank() ? "Full" : width;
            if (validPage(dialog)) {
                DialogDefinition.Page page = dialog.pages.get(pageIndex);
                page.text = field(payload, "pageText");
                String continueLabel = field(payload, "continueLabel");
                page.continueLabel = continueLabel.isBlank() ? "Continue" : continueLabel;
                if (validButton(page)) {
                    DialogDefinition.Button button = page.buttons.get(buttonIndex);
                    button.text = field(payload, "buttonText");
                    button.actions = commaList(field(payload, "buttonActions"));
                }
            }
            dialog.normalize(dialog.id);
            module.persistAndExport(dialog);
        }

        private boolean validPage(DialogDefinition dialog) {
            return dialog != null && pageIndex >= 0 && pageIndex < dialog.pages.size();
        }

        private boolean validButton(DialogDefinition.Page page) {
            return page != null && buttonIndex >= 0 && buttonIndex < page.buttons.size();
        }

        private List<DropdownEntryInfo> dialogEntries() {
            List<DropdownEntryInfo> entries = new ArrayList<>();
            entries.add(entry("-- Select Dialog --", ""));
            for (String id : module.dialogs().ids()) {
                DialogDefinition dialog = module.dialogs().get(id);
                String label = dialog == null || dialog.title.isBlank()
                        ? id : id + " (" + dialog.title + ")";
                entries.add(entry(label, id));
            }
            return entries;
        }

        private static List<DropdownEntryInfo> widthEntries() {
            return List.of(entry("Full", "Full"), entry("Mid", "Mid"), entry("Compact", "Compact"));
        }

        private static List<String> commaList(String value) {
            if (value == null || value.isBlank()) {
                return new ArrayList<>();
            }
            List<String> actions = new ArrayList<>();
            for (String action : value.split(",")) {
                if (!action.isBlank()) {
                    actions.add(action.trim());
                }
            }
            return actions;
        }

        static int integer(JsonObject object, String key, int fallback) {
            try {
                return string(object, key).isBlank() ? fallback : object.get(key).getAsInt();
            } catch (Exception ignored) {
                return fallback;
            }
        }

        private void message(String raw) {
            core.getMessageService().send(player, raw);
        }
    }

    private static DropdownEntryInfo entry(String label, String value) {
        return new DropdownEntryInfo(LocalizableString.fromString(label), value);
    }
}
