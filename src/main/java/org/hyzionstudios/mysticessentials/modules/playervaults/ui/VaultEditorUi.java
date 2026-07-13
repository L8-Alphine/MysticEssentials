package org.hyzionstudios.mysticessentials.modules.playervaults.ui;

import java.util.List;
import java.util.UUID;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.playervaults.api.VaultOpenMode;
import org.hyzionstudios.mysticessentials.modules.playervaults.config.PlayerVaultConfig;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.VaultMetadata;
import org.hyzionstudios.mysticessentials.modules.playervaults.service.VaultItemCatalog;
import org.hyzionstudios.mysticessentials.modules.playervaults.ui.PlayerVaultUiController.EditorDraft;
import org.hyzionstudios.mysticessentials.platform.ui.MysticPage;

import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Customizes a vault card: name, colour, icon, and description. The right column
 * is a live item picker — an {@code ItemSlot} preview of the current icon, a
 * search field over the whole item registry, and a scrollable list of matching
 * items rendered as real item icons. Clicking a result sets the icon
 * immediately; the search and pick refreshes preserve the other in-progress
 * fields via a passed-through {@link EditorDraft}.
 *
 * <p>Each field is shown only when the viewer holds the matching editor
 * permission. The metadata, draft, and search results are prepared by the
 * controller before the page opens so {@code build} is synchronous.</p>
 */
final class VaultEditorUi extends MysticPage {

    static final String EDITOR_UI = "MysticEssentials/VaultEditor.ui";
    static final String ICON_ROW_UI = "MysticEssentials/VaultIconRow.ui";

    private final PlayerVaultUiController controller;
    private final UUID ownerUuid;
    private final String ownerName;
    private final int vaultNumber;
    private final boolean adminView;
    private final VaultMetadata metadata;
    private final EditorDraft draft;
    private final String query;
    private final List<String> results;

    VaultEditorUi(MysticCore core, PlayerVaultUiController controller, PlayerRef player, UUID ownerUuid,
            String ownerName, int vaultNumber, boolean adminView, VaultMetadata metadata, EditorDraft draft,
            String query, List<String> results) {
        super(core, player, CustomPageLifetime.CanDismiss);
        this.controller = controller;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.vaultNumber = vaultNumber;
        this.adminView = adminView;
        this.metadata = metadata == null ? new VaultMetadata() : metadata;
        this.draft = draft;
        this.query = query == null ? "" : query;
        this.results = results == null ? List.of() : results;
    }

    // Resolve each field from the in-progress draft (if any) else the stored metadata.
    private String currentName() {
        return draft != null ? nullToEmpty(draft.name()) : nullToEmpty(metadata.name);
    }

    private String currentColor() {
        return draft != null ? nullToEmpty(draft.color()) : nullToEmpty(metadata.color);
    }

    private String currentIcon() {
        if (draft != null) {
            return nullToEmpty(draft.icon());
        }
        return metadata.icon == null || metadata.icon.itemId == null ? "" : metadata.icon.itemId;
    }

    private String currentDescription() {
        return draft != null ? nullToEmpty(draft.description()) : nullToEmpty(metadata.description);
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event, Store<EntityStore> store) {
        cmd.append(EDITOR_UI);
        PlayerVaultConfig config = controller.config();

        boolean canName = controller.permissions().canEditName(player);
        boolean canColor = controller.permissions().canEditColor(player);
        boolean canIcon = controller.permissions().canEditIcon(player);
        boolean canDescription = controller.permissions().canEditDescription(player);
        boolean canReset = controller.permissions().canResetMetadata(player);

        cmd.set("#HeaderTitle.Text", "Edit Vault #" + vaultNumber);
        cmd.set("#Subtitle.Text", adminView && ownerName != null ? "Owner: " + ownerName : "");

        cmd.set("#NameRow.Visible", canName);
        cmd.set("#NameInput.Value", currentName());

        cmd.set("#ColorRow.Visible", canColor);
        cmd.set("#ColorInput.Color", safeColor(currentColor()));

        cmd.set("#IconRow.Visible", canIcon);
        cmd.set("#IconInput.Value", currentIcon());

        cmd.set("#DescriptionRow.Visible", canDescription);
        cmd.set("#DescriptionInput.Value", currentDescription());

        cmd.set("#ResetButton.Visible", canReset);

        // ----- Icon picker column (only when the viewer may edit icons) -----
        cmd.set("#IconPickerColumn.Visible", canIcon);
        if (canIcon) {
            String icon = currentIcon();
            cmd.set("#IconPreview.ItemId", icon.isBlank()
                    ? (config.defaultIconItemId == null ? "" : config.defaultIconItemId) : icon);
            cmd.set("#IconCurrent.Text", icon.isBlank() ? "None selected" : icon);
            cmd.set("#IconSearch.Value", query);
            cmd.set("#IconEmpty.Visible", results.isEmpty());
            for (int i = 0; i < results.size(); i++) {
                String itemId = results.get(i);
                String row = "#IconResults[" + i + "]";
                cmd.append("#IconResults", ICON_ROW_UI);
                cmd.set(row + " #Icon.ItemId", itemId);
                cmd.set(row + " #Name.Text", itemId);
                event.addEventBinding(CustomUIEventBindingType.Activating, row,
                        pickEventData(canName, canColor, canDescription).put("action", "pick").put("icon", itemId));
            }
            // Live search: rebuild filtered, preserving the other in-progress fields.
            EventData search = new EventData().put("action", "search").append("@query", "#IconSearch.Value");
            appendFieldDrafts(search, canName, canColor, canIcon, canDescription);
            event.addEventBinding(CustomUIEventBindingType.ValueChanged, "#IconSearch", search);
        }

        // ----- Actions -----
        EventData apply = new EventData().put("action", "apply");
        appendFieldDrafts(apply, canName, canColor, canIcon, canDescription);
        event.addEventBinding(CustomUIEventBindingType.Activating, "#ApplyButton", apply);
        event.addEventBinding(CustomUIEventBindingType.Activating, "#ResetButton",
                new EventData().put("action", "reset"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton",
                new EventData().put("action", "back"));
    }

    /** Appends the current editable field values so a refresh can restore them. */
    private void appendFieldDrafts(EventData data, boolean name, boolean color, boolean icon, boolean description) {
        if (name) {
            data.append("@name", "#NameInput.Value");
        }
        if (color) {
            data.append("@color", "#ColorInput.Color");
        }
        if (icon) {
            data.append("@icon", "#IconInput.Value");
        }
        if (description) {
            data.append("@description", "#DescriptionInput.Value");
        }
    }

    private EventData pickEventData(boolean name, boolean color, boolean description) {
        EventData data = new EventData();
        // Icon comes from the clicked row, not the text field, so don't append @icon here.
        if (name) {
            data.append("@name", "#NameInput.Value");
        }
        if (color) {
            data.append("@color", "#ColorInput.Color");
        }
        if (description) {
            data.append("@description", "#DescriptionInput.Value");
        }
        return data;
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
        JsonObject payload = parse(data);
        switch (string(payload, "action")) {
            case "apply" -> {
                // Persist in the background; refresh the page synchronously (with the
                // submitted values) so the client never sticks on the loading overlay.
                controller.applyMetadata(player, ownerUuid, ownerName, vaultNumber, adminView,
                        new PlayerVaultUiController.MetadataEdit(
                                optional(payload, "name"), optional(payload, "color"),
                                optional(payload, "icon"), optional(payload, "description")),
                        null);
                reopen(ref, store, rebuild(draftFrom(payload, optional(payload, "icon")), query, results));
            }
            case "reset" -> {
                controller.resetMetadata(player, ownerUuid, ownerName, vaultNumber, adminView, null);
                reopen(ref, store, rebuild(new EditorDraft("", "", "", ""), query, results));
            }
            case "search" -> {
                String q = string(payload, "@query");
                List<String> filtered = VaultItemCatalog.search(q, controller.config().iconPickerResultLimit);
                reopen(ref, store, rebuild(draftFrom(payload, currentIcon()), q, filtered));
            }
            case "pick" -> {
                String icon = field(payload, "icon");
                controller.applyMetadata(player, ownerUuid, ownerName, vaultNumber, adminView,
                        new PlayerVaultUiController.MetadataEdit(null, null, icon, null), null);
                reopen(ref, store, rebuild(draftFrom(payload, icon), query, results));
            }
            case "back" -> {
                close(ref, store);
                if (adminView) {
                    controller.openAdminVault(player, ownerUuid, ownerName, vaultNumber,
                            VaultOpenMode.ADMIN_EDIT, core.platform().findPlayer(ownerUuid).isPresent());
                } else {
                    controller.openOwnVault(player, vaultNumber);
                }
            }
            default -> {
            }
        }
    }

    /** A fresh editor instance carrying the given in-progress state, for a synchronous refresh. */
    private VaultEditorUi rebuild(EditorDraft draft, String searchQuery, List<String> searchResults) {
        return new VaultEditorUi(core, controller, player, ownerUuid, ownerName, vaultNumber, adminView,
                metadata, draft, searchQuery, searchResults);
    }

    /** Builds a draft from the submitted field values, forcing the icon to {@code iconOverride}. */
    private EditorDraft draftFrom(JsonObject payload, String iconOverride) {
        return new EditorDraft(optional(payload, "name"), optional(payload, "color"),
                iconOverride, optional(payload, "description"));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Returns a submitted field value ({@code ""} = cleared), or {@code null} when the field was absent. */
    private static String optional(JsonObject payload, String key) {
        if (payload.has(key)) {
            return string(payload, key);
        }
        if (payload.has("@" + key)) {
            return string(payload, "@" + key);
        }
        return null;
    }
}
