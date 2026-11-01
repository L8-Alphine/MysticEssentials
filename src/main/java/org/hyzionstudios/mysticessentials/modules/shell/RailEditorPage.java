package org.hyzionstudios.mysticessentials.modules.shell;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.platform.ui.MysticPage;
import org.hyzionstudios.mysticessentials.platform.ui.VanillaIcons;

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

/**
 * The Rail Editor ({@code /shortcuts edit}, {@code mysticessentials.shell.admin}):
 * what each of the nine slots does, its label, its command or channel, and
 * its icon from the game's own set — the same fields as the config file,
 * without a restart.
 *
 * <p>The page holds a draft of the selected slot. Every press carries the
 * typed fields, so picking an icon or changing the kind rebuilds the page
 * without losing them; nothing reaches the config until Save, which re-checks
 * the permission, writes the file off the world thread and redraws every
 * player's rail.</p>
 */
final class RailEditorPage extends MysticPage {

    static final String PAGE_UI = "MysticEssentials/RailEditor.ui";
    static final String SLOT_ROW_UI = "MysticEssentials/RailEditorSlotRow.ui";
    static final String ICON_TILE_UI = "MysticEssentials/RailEditorIconTile.ui";

    static final int MAX_LABEL = 32;
    static final int MAX_GLYPH = 2;
    static final int MAX_TARGET = 256;

    private final ShellModule shell;
    private final int selected;
    private final ShellConfig.Slot draft;

    RailEditorPage(MysticCore core, ShellModule shell, PlayerRef player, int selected) {
        this(core, shell, player, selected, ShellConfig.copy(shell.configuredSlot(selected), selected));
    }

    private RailEditorPage(MysticCore core, ShellModule shell, PlayerRef player, int selected,
            ShellConfig.Slot draft) {
        super(core, player, CustomPageLifetime.CanDismiss);
        this.shell = shell;
        this.selected = selected;
        this.draft = draft;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
            Store<EntityStore> store) {
        cmd.append(PAGE_UI);
        cmd.set("#EditorContext.TextSpans", uiText("#EditorContext.TextSpans", "Changes apply to every player"));
        cmd.set("#EditorNote.TextSpans", uiText("#EditorNote.TextSpans",
                "Save redraws every player's rail at once. Slot numbers are fixed: "
                        + "Alt+N and /N always run slot N."));

        for (int index = 1; index <= 9; index++) {
            renderSlotRow(cmd, event, index);
        }

        ShortcutKind kind = ShortcutKind.parse(draft.kind);
        ShellConfig.Slot saved = shell.configuredSlot(selected);
        cmd.set("#EditTitle.TextSpans", uiText("#EditTitle.TextSpans",
                "Slot " + selected + "  ·  " + shell.keyLabel(selected)));
        cmd.set("#EditDirty.TextSpans", uiText("#EditDirty.TextSpans",
                same(draft, saved) ? "" : "Unsaved changes"));

        List<DropdownEntryInfo> kinds = new ArrayList<>();
        for (ShortcutKind option : ShortcutKind.values()) {
            kinds.add(new DropdownEntryInfo(LocalizableString.fromString(kindName(option)), option.name()));
        }
        cmd.set("#KindInput.Entries", kinds);
        cmd.set("#KindInput.Value", kind.name());
        cmd.set("#KindHint.TextSpans", uiText("#KindHint.TextSpans", kindHint(kind)));

        cmd.set("#LabelInput.Value", nullToEmpty(draft.label));
        cmd.set("#TargetInput.Value", nullToEmpty(draft.target));
        cmd.set("#GlyphInput.Value", nullToEmpty(draft.glyph));
        cmd.set("#PermissionInput.Value", nullToEmpty(draft.permission));
        cmd.set("#TargetHint.TextSpans", uiText("#TargetHint.TextSpans", targetHint(kind)));

        String icon = ShellConfig.iconFor(draft);
        cmd.set("#IconPreview.Visible", !icon.isEmpty());
        cmd.set("#GlyphPreview.Visible", icon.isEmpty());
        if (icon.isEmpty()) {
            cmd.set("#GlyphPreview.TextSpans", uiText("#GlyphPreview.TextSpans",
                    nullToEmpty(draft.glyph).isBlank() ? String.valueOf(selected) : draft.glyph));
        } else {
            cmd.set("#IconPreview.AssetPath", icon);
        }
        cmd.set("#IconName.TextSpans", uiText("#IconName.TextSpans", iconDescription(draft, icon)));

        String chosen = nullToEmpty(draft.icon).isBlank() || "none".equalsIgnoreCase(draft.icon.trim())
                ? "" : VanillaIcons.resolve(draft.icon);
        // The server's own icons (mods/MysticEssentials/icons) first, then the game's.
        List<VanillaIcons.Entry> picker = capRows(VanillaIcons.pickerWithCustom());
        for (int i = 0; i < picker.size(); i++) {
            VanillaIcons.Entry entry = picker.get(i);
            String tile = "#IconGrid[" + i + "]";
            cmd.append("#IconGrid", ICON_TILE_UI);
            cmd.set(tile + " #TileIcon.AssetPath", entry.path());
            cmd.set(tile + " #TileSelected.Visible", entry.path().equals(chosen));
            event.addEventBinding(CustomUIEventBindingType.Activating, tile,
                    form(new EventData().put("action", "icon").put("icon", entry.path())));
        }

        cmd.set("#EnabledButton.TextSpans", uiText("#EnabledButton.TextSpans",
                draft.enabled ? "&aEnabled" : "&7Disabled"));

        // Not locking, and answered with an update that touches only the hints.
        // The page sets this dropdown's Value as it builds; if that raises
        // ValueChanged before the client has acknowledged the page, the engine
        // drops the event, and a locking binding would then sit on "Loading..."
        // for good. Rebuilding here would set Value again and could loop.
        event.addEventBinding(CustomUIEventBindingType.ValueChanged, "#KindInput",
                new EventData().put("action", "kind").append("@kind", "#KindInput.Value"), false);
        event.addEventBinding(CustomUIEventBindingType.Activating, "#IconDefaultButton",
                form(new EventData().put("action", "icon").put("icon", "")));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#IconLetterButton",
                form(new EventData().put("action", "icon").put("icon", "none")));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#EnabledButton",
                form(new EventData().put("action", "enabled")));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#ResetButton",
                new EventData().put("action", "reset"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#SaveButton",
                form(new EventData().put("action", "save")));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton",
                new EventData().put("action", "back"));
    }

    private void renderSlotRow(UICommandBuilder cmd, UIEventBuilder event, int index) {
        String row = "#EditorSlots[" + (index - 1) + "]";
        cmd.append("#EditorSlots", SLOT_ROW_UI);
        ShellConfig.Slot slot = shell.configuredSlot(index);
        String icon = slot == null ? "" : ShellConfig.iconFor(slot);
        cmd.set(row + " #RowIcon.Visible", !icon.isEmpty());
        cmd.set(row + " #RowGlyph.Visible", icon.isEmpty());
        if (icon.isEmpty()) {
            cmd.set(row + " #RowGlyph.TextSpans", uiText(row + " #RowGlyph.TextSpans", String.valueOf(index)));
        } else {
            cmd.set(row + " #RowIcon.AssetPath", icon);
        }
        boolean empty = slot == null || !slot.enabled || ShortcutKind.parse(slot.kind) == ShortcutKind.NONE;
        cmd.set(row + " #RowName.TextSpans", uiText(row + " #RowName.TextSpans",
                index + "  " + (slot == null || nullToEmpty(slot.label).isBlank() ? "Empty" : slot.label)));
        cmd.set(row + " #RowState.TextSpans", uiText(row + " #RowState.TextSpans",
                empty ? "&8Off" : rowState(slot)));
        cmd.set(row + " #RowSelected.Visible", index == selected);
        event.addEventBinding(CustomUIEventBindingType.Activating, row,
                new EventData().put("action", "select").put("slot", String.valueOf(index)));
    }

    /** Appends the live form values, so a rebuild keeps what was typed. */
    private static EventData form(EventData data) {
        return data
                .append("@kind", "#KindInput.Value")
                .append("@label", "#LabelInput.Value")
                .append("@target", "#TargetInput.Value")
                .append("@glyph", "#GlyphInput.Value")
                .append("@permission", "#PermissionInput.Value");
    }

    /** The draft with the typed fields applied; icon and enabled carry over. */
    private ShellConfig.Slot withForm(JsonObject payload) {
        ShellConfig.Slot next = ShellConfig.copy(draft, selected);
        if (payload.has("@kind")) {
            next.kind = ShortcutKind.parse(field(payload, "kind")).name();
        }
        if (payload.has("@label")) {
            next.label = clip(field(payload, "label"), MAX_LABEL);
        }
        if (payload.has("@target")) {
            next.target = clip(field(payload, "target"), MAX_TARGET);
        }
        if (payload.has("@glyph")) {
            next.glyph = clipCodePoints(field(payload, "glyph"), MAX_GLYPH);
        }
        if (payload.has("@permission")) {
            next.permission = field(payload, "permission").replace(" ", "");
        }
        return next;
    }

    @Override
    protected void onEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
        JsonObject payload = parse(data);
        if (!player.hasPermission(Permissions.SHELL_ADMIN)) {
            shell.tell(player, "You can no longer edit the shortcut rail.");
            close(ref, store);
            return;
        }
        switch (string(payload, "action")) {
            case "select" -> {
                int index = (int) parseDouble(field(payload, "slot"), selected);
                reopen(ref, store, new RailEditorPage(core, shell, player, clampSlot(index)));
            }
            case "kind" -> {
                ShortcutKind kind = ShortcutKind.parse(field(payload, "kind"));
                UICommandBuilder hints = new UICommandBuilder();
                hints.set("#KindHint.TextSpans", uiText("#KindHint.TextSpans", kindHint(kind)));
                hints.set("#TargetHint.TextSpans", uiText("#TargetHint.TextSpans", targetHint(kind)));
                sendUpdate(hints);
            }
            case "icon" -> {
                ShellConfig.Slot next = withForm(payload);
                next.icon = field(payload, "icon");
                reopen(ref, store, new RailEditorPage(core, shell, player, selected, next));
            }
            case "enabled" -> {
                ShellConfig.Slot next = withForm(payload);
                next.enabled = !next.enabled;
                reopen(ref, store, new RailEditorPage(core, shell, player, selected, next));
            }
            case "reset" -> reopen(ref, store, new RailEditorPage(core, shell, player, selected,
                    ShellConfig.copy(ShellConfig.defaultSlot(selected), selected)));
            case "save" -> {
                ShellConfig.Slot next = withForm(payload);
                shell.saveSlot(next);
                shell.tell(player, "Slot " + selected + " saved" + saveNote(next));
                reopen(ref, store, new RailEditorPage(core, shell, player, selected));
            }
            case "back" -> reopen(ref, store, new ShellPages.ShortcutBarPage(core, shell, player));
            default -> {
                // Unknown action: leave the page as it is.
            }
        }
    }

    /** A nudge after saving a slot that will show as unavailable. */
    private static String saveNote(ShellConfig.Slot slot) {
        ShortcutKind kind = ShortcutKind.parse(slot.kind);
        boolean needsTarget = kind == ShortcutKind.CHANNEL || kind == ShortcutKind.COMMAND
                || kind == ShortcutKind.STORE;
        if (needsTarget && nullToEmpty(slot.target).isBlank()) {
            return ", but it has nothing to run yet, so it shows as unavailable.";
        }
        return ".";
    }

    static String kindName(ShortcutKind kind) {
        return switch (kind) {
            case CHANNEL -> "Chat channel";
            case STORE -> "Store link";
            case WIKI -> "Server wiki";
            case WARPS -> "Warps";
            case SERVERS -> "Server browser";
            case MENU -> "Main menu";
            case COMMAND -> "Command";
            case NONE -> "Nothing";
        };
    }

    private static String kindHint(ShortcutKind kind) {
        return switch (kind) {
            case CHANNEL -> "Switches where the player speaks.";
            case STORE -> "Sends the store link, or runs a command.";
            case WIKI -> "Opens the server wiki.";
            case WARPS -> "Opens warps (or the command below).";
            case SERVERS -> "Opens the server browser.";
            case MENU -> "Opens the Adventurer's Menu.";
            case COMMAND -> "Runs a command as the player.";
            case NONE -> "Shows an empty, unavailable tile.";
        };
    }

    private static String targetHint(ShortcutKind kind) {
        return switch (kind) {
            case CHANNEL -> "A channel id from the chat config: global, trade, guild, party...";
            case STORE -> "An https:// link (sent as a clickable line) or a command.";
            case WARPS -> "Optional: a command instead of /warps.";
            case SERVERS -> "Optional: your own selector command or link instead of the browser.";
            case COMMAND -> "Without the slash. It runs as the player, under their permissions.";
            case WIKI, MENU, NONE -> "Not used by this kind.";
        };
    }

    private static String rowState(ShellConfig.Slot slot) {
        ShortcutKind kind = ShortcutKind.parse(slot.kind);
        String target = nullToEmpty(slot.target).trim();
        return kindName(kind) + (target.isEmpty() ? "" : "  ·  " + target);
    }

    private static String iconDescription(ShellConfig.Slot slot, String resolved) {
        String configured = nullToEmpty(slot.icon).trim();
        if ("none".equalsIgnoreCase(configured)) {
            return "Letter only";
        }
        String name = VanillaIcons.nameOf(resolved);
        if (configured.isEmpty()) {
            return "Default for this kind" + (name.isEmpty() ? "" : " (" + name + ")");
        }
        return name.isEmpty() ? resolved : name;
    }

    private static boolean same(ShellConfig.Slot a, ShellConfig.Slot b) {
        if (a == null || b == null) {
            return a == b;
        }
        return ShortcutKind.parse(a.kind) == ShortcutKind.parse(b.kind)
                && Objects.equals(nullToEmpty(a.label), nullToEmpty(b.label))
                && Objects.equals(nullToEmpty(a.target), nullToEmpty(b.target))
                && Objects.equals(nullToEmpty(a.glyph), nullToEmpty(b.glyph))
                && Objects.equals(nullToEmpty(a.icon), nullToEmpty(b.icon))
                && Objects.equals(nullToEmpty(a.permission), nullToEmpty(b.permission))
                && a.enabled == b.enabled;
    }

    static int clampSlot(int index) {
        return Math.max(1, Math.min(9, index));
    }

    static String clip(String value, int max) {
        String trimmed = nullToEmpty(value).trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    static String clipCodePoints(String value, int max) {
        String trimmed = nullToEmpty(value).trim();
        int count = trimmed.codePointCount(0, trimmed.length());
        return count <= max ? trimmed : trimmed.substring(0, trimmed.offsetByCodePoints(0, max));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
