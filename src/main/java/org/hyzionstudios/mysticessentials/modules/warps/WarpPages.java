package org.hyzionstudios.mysticessentials.modules.warps;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.hyzionstudios.mysticessentials.api.model.MysticLocation;
import org.hyzionstudios.mysticessentials.api.model.Warp;
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

/**
 * Custom UI pages for warps. Lists are built from row-template {@code .ui}
 * files appended per entry (the builtin {@code WarpListPage} pattern):
 * {@code cmd.append("#WarpList", "MysticEssentials/WarpRow.ui")} then
 * {@code cmd.set("#WarpList[i] #Name.Text", ...)} and a per-row Activating
 * binding on {@code #WarpList[i]}.
 */
final class WarpPages {

    static final String WARPS_UI = "MysticEssentials/Warps.ui";
    static final String WARP_ROW_UI = "MysticEssentials/WarpRow.ui";
    static final String WARP_ADMIN_UI = "MysticEssentials/WarpAdmin.ui";
    static final String PLAYER_WARPS_UI = "MysticEssentials/PlayerWarps.ui";
    static final String PLAYER_WARP_ROW_UI = "MysticEssentials/PlayerWarpRow.ui";
    static final String PLAYER_WARP_MANAGER_UI = "MysticEssentials/PlayerWarpManager.ui";

    private WarpPages() {
    }

    // ----- Server warps: browse + admin ---------------------------------------

    static final class WarpsPage extends MysticPage {
        private final WarpModule warps;
        private final String selectedName;
        private final String search;

        WarpsPage(MysticCore core, WarpModule warps, PlayerRef player) {
            this(core, warps, player, null, "");
        }

        WarpsPage(MysticCore core, WarpModule warps, PlayerRef player, String selectedName, String search) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.warps = warps;
            this.selectedName = selectedName;
            this.search = search == null ? "" : search;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(WARPS_UI);
            List<Warp> visible = warps.listServerWarps(player.getUuid()).stream()
                    .filter(w -> matchesSearch(search, w.getName(), w.getDescription()))
                    .toList();
            cmd.set("#WarpEmpty.Visible", visible.isEmpty());
            for (int i = 0; i < visible.size(); i++) {
                Warp warp = visible.get(i);
                String row = "#WarpList[" + i + "]";
                cmd.append("#WarpList", WARP_ROW_UI);
                cmd.set(row + " #Name.Text", warp.getName());
                cmd.set(row + " #Meta.Text", serverWarpMeta(warp));
                cmd.set(row + " #Swatch.Background", serverWarpColor(warp));
                event.addEventBinding(CustomUIEventBindingType.Activating, row,
                        new EventData().put("action", "select").put("warp", warp.getName()));
            }

            Warp selected = selectedWarp(visible, selectedName);
            applyDetails(cmd, selected);

            boolean showManage = warps.canSetWarps(player) && selected != null;
            cmd.set("#ManageWarpButton.Visible", showManage);
            if (showManage) {
                event.addEventBinding(CustomUIEventBindingType.Activating, "#ManageWarpButton",
                        new EventData().put("action", "manage").put("warp", selected.getName()));
            }

            if (selected != null) {
                event.addEventBinding(CustomUIEventBindingType.Activating, "#TeleportButton",
                        new EventData().put("action", "teleport").put("warp", selected.getName()));
            }
            event.addEventBinding(CustomUIEventBindingType.ValueChanged, "#SearchInput",
                    new EventData().put("action", "search").append("@search", "#SearchInput.Value"));
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            String action = string(payload, "action");
            String warpName = field(payload, "warp");
            switch (action) {
                case "select" -> reopen(ref, store, new WarpsPage(core, warps, player, warpName, search));
                case "teleport" -> {
                    warps.getWarp(warpName).ifPresent(warp -> warps.warpPlayer(player, warp));
                    reopen(ref, store, new WarpsPage(core, warps, player, warpName, search));
                }
                case "manage" -> {
                    if (warps.canSetWarps(player)) {
                        reopen(ref, store, new WarpAdminPage(core, warps, player, warpName, search));
                    }
                }
                case "search" -> reopen(ref, store,
                        new WarpsPage(core, warps, player, selectedName, field(payload, "search")));
                default -> {
                }
            }
        }

        private void applyDetails(UICommandBuilder cmd, Warp warp) {
            if (warp == null) {
                cmd.set("#WarpName.Text", "No Warp");
                cmd.set("#WarpDescription.Text", "Create one or check your permissions.");
                cmd.set("#WarpCost.Text", "-");
                cmd.set("#WarpVisibility.Text", "-");
                cmd.set("#WarpPermission.Text", "-");
                cmd.set("#WarpWorld.Text", "-");
                return;
            }
            cmd.set("#WarpName.Text", warp.getName());
            cmd.set("#WarpDescription.Text", warp.getDescription() == null || warp.getDescription().isBlank()
                    ? "No description." : warp.getDescription());
            cmd.set("#WarpCost.Text", costText(core, warp));
            cmd.set("#WarpVisibility.Text", warp.getVisibility().name());
            cmd.set("#WarpPermission.Text", warp.getPermission() == null || warp.getPermission().isBlank()
                    ? "none" : warp.getPermission());
            cmd.set("#WarpWorld.Text", worldText(warp.getLocation()));
        }
    }

    // ----- Server warps: admin editor (separate page) --------------------------

    static final class WarpAdminPage extends MysticPage {
        private final WarpModule warps;
        private final String warpName;
        private final String search;

        WarpAdminPage(MysticCore core, WarpModule warps, PlayerRef player, String warpName, String search) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.warps = warps;
            this.warpName = warpName;
            this.search = search == null ? "" : search;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(WARP_ADMIN_UI);
            Warp warp = warpName == null ? null : warps.getWarp(warpName).orElse(null);
            cmd.set("#AdminHeading.Text", warp == null ? "New Warp" : warp.getName());
            cmd.set("#AdminName.Value", warp == null ? "" : warp.getName());
            cmd.set("#AdminDescription.Value",
                    warp == null || warp.getDescription() == null ? "" : warp.getDescription());
            cmd.set("#AdminCost.Value",
                    warp == null || warp.getCost() <= 0 ? "" : Double.toString(warp.getCost()));
            cmd.set("#AdminPermission.Value",
                    warp == null || warp.getPermission() == null ? "" : warp.getPermission());
            // Dropdown entries MUST be DropdownEntryInfo — UICommandBuilder.set(List)
            // resolves a protocol codec from the element class and rejects Strings.
            cmd.set("#AdminVisibility.Entries", List.of(
                    new DropdownEntryInfo(LocalizableString.fromString("Public"), "PUBLIC"),
                    new DropdownEntryInfo(LocalizableString.fromString("Permission"), "PERMISSION"),
                    new DropdownEntryInfo(LocalizableString.fromString("Hidden"), "HIDDEN")));
            cmd.set("#AdminVisibility.Value",
                    warp == null ? "PUBLIC" : warp.getVisibility().name());

            EventData save = new EventData()
                    .append("@name", "#AdminName.Value")
                    .append("@description", "#AdminDescription.Value")
                    .append("@cost", "#AdminCost.Value")
                    .append("@permission", "#AdminPermission.Value")
                    .append("@visibility", "#AdminVisibility.Value");
            event.addEventBinding(CustomUIEventBindingType.Activating, "#SaveHereButton",
                    new EventData(save.events()).put("action", "savehere"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#SaveDetailsButton",
                    new EventData(save.events()).put("action", "savedetails"));
            if (warp != null) {
                event.addEventBinding(CustomUIEventBindingType.Activating, "#DeleteWarpButton",
                        new EventData().put("action", "delete").put("warp", warp.getName()));
            }
            event.addEventBinding(CustomUIEventBindingType.Activating, "#BackToWarpsButton",
                    new EventData().put("action", "back"));
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            String action = string(payload, "action");
            switch (action) {
                case "savehere", "savedetails" -> {
                    String name = field(payload, "name");
                    WarpModule.SaveResult result = warps.saveServerWarpFromUi(player, warpName, name,
                            field(payload, "description"), field(payload, "permission"),
                            parseDouble(field(payload, "cost"), 0.0),
                            parseVisibility(field(payload, "visibility")),
                            "savehere".equals(action));
                    switch (result) {
                        case SAVED -> core.getMessageService().sendKey(player,
                                "warp-saved", Map.of("warp", name));
                        case RENAMED -> core.getMessageService().sendKey(player,
                                "warp-renamed", Map.of("old", warpName, "new", name));
                        case NAME_TAKEN -> core.getMessageService().sendKey(player,
                                "warp-name-taken", Map.of("warp", name));
                        case INVALID -> core.getMessageService().sendKey(player,
                                "warp-save-failed");
                    }
                    boolean saved = result == WarpModule.SaveResult.SAVED
                            || result == WarpModule.SaveResult.RENAMED;
                    reopen(ref, store, new WarpAdminPage(core, warps, player,
                            saved ? name : warpName, search));
                }
                case "delete" -> {
                    String target = field(payload, "warp");
                    boolean deleted = warps.canSetWarps(player) && warps.deleteServerWarp(target);
                    core.getMessageService().sendKey(player, deleted
                            ? "warp-deleted"
                            : "warp-delete-failed", Map.of("warp", target));
                    reopen(ref, store, new WarpsPage(core, warps, player, null, search));
                }
                case "back" -> reopen(ref, store, new WarpsPage(core, warps, player, warpName, search));
                default -> {
                }
            }
        }
    }

    // ----- Player warps: browse all -------------------------------------------

    static final class PlayerWarpsPage extends MysticPage {
        private final WarpModule warps;
        private final String selectedName;
        private final String search;

        PlayerWarpsPage(MysticCore core, WarpModule warps, PlayerRef player) {
            this(core, warps, player, null, "");
        }

        PlayerWarpsPage(MysticCore core, WarpModule warps, PlayerRef player, String selectedName, String search) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.warps = warps;
            this.selectedName = selectedName;
            this.search = search == null ? "" : search;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(PLAYER_WARPS_UI);
            List<Warp> all = warps.listAllPlayerWarps().stream()
                    .filter(w -> matchesSearch(search, w.getName(), w.getDescription(), w.getOwnerName()))
                    .toList();
            cmd.set("#PwarpEmpty.Visible", all.isEmpty());
            for (int i = 0; i < all.size(); i++) {
                Warp warp = all.get(i);
                String row = "#PwarpList[" + i + "]";
                cmd.append("#PwarpList", PLAYER_WARP_ROW_UI);
                cmd.set(row + " #Name.Text", warp.getName());
                cmd.set(row + " #Meta.Text", playerWarpMeta(warp));
                cmd.set(row + " #Swatch.Background", playerWarpColor(warp));
                event.addEventBinding(CustomUIEventBindingType.Activating, row,
                        new EventData().put("action", "select").put("warp", warp.getName()));
            }

            Warp selected = selectedWarp(all, selectedName);
            applyDetails(cmd, selected);
            cmd.set("#PwarpLimit.Text", "You own " + warps.getPlayerWarps(player.getUuid()).size()
                    + " / " + warps.playerWarpLimit(player.getUuid()) + " player warps.");

            if (selected != null) {
                event.addEventBinding(CustomUIEventBindingType.Activating, "#TeleportButton",
                        new EventData().put("action", "teleport").put("warp", selected.getName()));
            }
            event.addEventBinding(CustomUIEventBindingType.Activating, "#MyWarpsButton",
                    new EventData().put("action", "manage"));
            event.addEventBinding(CustomUIEventBindingType.ValueChanged, "#SearchInput",
                    new EventData().put("action", "search").append("@search", "#SearchInput.Value"));
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            String action = string(payload, "action");
            String warpName = field(payload, "warp");
            switch (action) {
                case "select" -> reopen(ref, store, new PlayerWarpsPage(core, warps, player, warpName, search));
                case "teleport" -> {
                    warps.getPlayerWarp(warpName).ifPresent(warp -> warps.warpPlayer(player, warp));
                    reopen(ref, store, new PlayerWarpsPage(core, warps, player, warpName, search));
                }
                case "manage" -> reopen(ref, store, new PlayerWarpManagerPage(core, warps, player, null));
                case "search" -> reopen(ref, store,
                        new PlayerWarpsPage(core, warps, player, selectedName, field(payload, "search")));
                default -> {
                }
            }
        }

        private void applyDetails(UICommandBuilder cmd, Warp warp) {
            if (warp == null) {
                cmd.set("#PwarpName.Text", "No Warp");
                cmd.set("#PwarpDescription.Text", "Use /pwarp create <name> to add your own.");
                cmd.set("#PwarpOwner.Text", "-");
                cmd.set("#PwarpCost.Text", "-");
                cmd.set("#PwarpWorld.Text", "-");
                return;
            }
            cmd.set("#PwarpName.Text", warp.getName());
            cmd.set("#PwarpDescription.Text", warp.getDescription() == null || warp.getDescription().isBlank()
                    ? "No description." : warp.getDescription());
            cmd.set("#PwarpOwner.Text", ownerText(warp));
            cmd.set("#PwarpCost.Text", costText(core, warp));
            cmd.set("#PwarpWorld.Text", worldText(warp.getLocation()));
        }
    }

    // ----- Player warps: own manager ------------------------------------------

    static final class PlayerWarpManagerPage extends MysticPage {
        private final WarpModule warps;
        private final String selectedName;

        PlayerWarpManagerPage(MysticCore core, WarpModule warps, PlayerRef player, String selectedName) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.warps = warps;
            this.selectedName = selectedName;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(PLAYER_WARP_MANAGER_UI);
            List<Warp> own = warps.getPlayerWarps(player.getUuid());
            cmd.set("#ManagerLimit.Text", own.size() + " / " + warps.playerWarpLimit(player.getUuid()));
            cmd.set("#OwnEmpty.Visible", own.isEmpty());
            for (int i = 0; i < own.size(); i++) {
                Warp warp = own.get(i);
                String row = "#OwnList[" + i + "]";
                cmd.append("#OwnList", PLAYER_WARP_ROW_UI);
                cmd.set(row + " #Name.Text", warp.getName());
                cmd.set(row + " #Meta.Text", playerWarpMeta(warp));
                cmd.set(row + " #Swatch.Background", playerWarpColor(warp));
                event.addEventBinding(CustomUIEventBindingType.Activating, row,
                        new EventData().put("action", "select").put("warp", warp.getName()));
            }

            Warp selected = selectedWarp(own, selectedName);
            cmd.set("#SelectedName.Text", selected == null ? "No Warp" : selected.getName());
            cmd.set("#EditDescription.Value",
                    selected == null || selected.getDescription() == null ? "" : selected.getDescription());
            cmd.set("#EditCost.Value",
                    selected == null || selected.getCost() <= 0 ? "" : Double.toString(selected.getCost()));
            cmd.set("#RenameInput.Value", "");

            if (selected != null) {
                String name = selected.getName();
                event.addEventBinding(CustomUIEventBindingType.Activating, "#SaveDetailsButton",
                        new EventData().put("action", "save").put("warp", name)
                                .append("@description", "#EditDescription.Value")
                                .append("@cost", "#EditCost.Value"));
                event.addEventBinding(CustomUIEventBindingType.Activating, "#RenameButton",
                        new EventData().put("action", "rename").put("warp", name)
                                .append("@newname", "#RenameInput.Value"));
                event.addEventBinding(CustomUIEventBindingType.Activating, "#TeleportButton",
                        new EventData().put("action", "teleport").put("warp", name));
                event.addEventBinding(CustomUIEventBindingType.Activating, "#MoveHereButton",
                        new EventData().put("action", "movehere").put("warp", name));
                event.addEventBinding(CustomUIEventBindingType.Activating, "#DeleteButton",
                        new EventData().put("action", "delete").put("warp", name));
            }
            event.addEventBinding(CustomUIEventBindingType.Activating, "#BrowseAllButton",
                    new EventData().put("action", "browse"));
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            String action = string(payload, "action");
            String warpName = field(payload, "warp");
            switch (action) {
                case "select" -> reopen(ref, store, new PlayerWarpManagerPage(core, warps, player, warpName));
                case "teleport" -> {
                    warps.getOwnPlayerWarp(player.getUuid(), warpName)
                            .ifPresent(warp -> warps.warpPlayer(player, warp));
                    reopen(ref, store, new PlayerWarpManagerPage(core, warps, player, warpName));
                }
                case "save" -> {
                    boolean saved = warps.updatePlayerWarpDetails(player.getUuid(), warpName,
                            field(payload, "description"), parseDouble(field(payload, "cost"), 0.0));
                    core.getMessageService().sendKey(player, saved
                            ? "pwarp-updated"
                            : "pwarp-update-failed", Map.of("warp", warpName));
                    reopen(ref, store, new PlayerWarpManagerPage(core, warps, player, warpName));
                }
                case "rename" -> {
                    String newName = field(payload, "newname");
                    boolean renamed = warps.renamePlayerWarp(player.getUuid(), warpName, newName);
                    core.getMessageService().sendKey(player, renamed
                            ? "pwarp-renamed"
                            : "pwarp-rename-failed", Map.of("warp", newName));
                    reopen(ref, store, new PlayerWarpManagerPage(core, warps, player,
                            renamed ? newName : warpName));
                }
                case "movehere" -> {
                    boolean moved = warps.relocatePlayerWarp(player, warpName);
                    core.getMessageService().sendKey(player, moved
                            ? "pwarp-moved"
                            : "pwarp-move-failed", Map.of("warp", warpName));
                    reopen(ref, store, new PlayerWarpManagerPage(core, warps, player, warpName));
                }
                case "delete" -> {
                    boolean deleted = warps.deletePlayerWarp(player.getUuid(), warpName);
                    core.getMessageService().sendKey(player, deleted
                            ? "pwarp-deleted"
                            : "pwarp-missing-owned", Map.of("warp", warpName));
                    reopen(ref, store, new PlayerWarpManagerPage(core, warps, player, null));
                }
                case "browse" -> reopen(ref, store, new PlayerWarpsPage(core, warps, player));
                default -> {
                }
            }
        }
    }

    // ----- Shared helpers ------------------------------------------------------

    private static Warp selectedWarp(List<Warp> warps, String selectedName) {
        if (warps.isEmpty()) {
            return null;
        }
        if (selectedName != null) {
            for (Warp warp : warps) {
                if (warp.getName().equalsIgnoreCase(selectedName)) {
                    return warp;
                }
            }
        }
        return warps.get(0);
    }

    private static String costText(MysticCore core, Warp warp) {
        return warp.getCost() <= 0 ? "Free" : core.getEconomyService().format(warp.getCost());
    }

    private static String ownerText(Warp warp) {
        return warp.getOwnerName() == null || warp.getOwnerName().isBlank() ? "Unknown" : warp.getOwnerName();
    }

    private static String worldText(MysticLocation location) {
        if (location == null) {
            return "-";
        }
        return location.getWorld() + " (" + Math.round(location.getX()) + ", "
                + Math.round(location.getY()) + ", " + Math.round(location.getZ()) + ")";
    }

    private static String serverWarpMeta(Warp warp) {
        String cost = warp.getCost() <= 0 ? "Free" : "Cost: " + warp.getCost();
        String desc = warp.getDescription() == null || warp.getDescription().isBlank()
                ? warp.getVisibility().name() : warp.getDescription();
        return desc + " | " + cost;
    }

    private static String playerWarpMeta(Warp warp) {
        String cost = warp.getCost() <= 0 ? "Free" : "Cost: " + warp.getCost();
        return "by " + ownerText(warp) + " | " + cost;
    }

    private static String serverWarpColor(Warp warp) {
        if (warp.getCost() > 0) {
            return "#FFAA00";
        }
        return switch (warp.getVisibility()) {
            case PUBLIC -> "#55FF55";
            case PERMISSION -> "#55FFFF";
            case HIDDEN -> "#AAAAAA";
        };
    }

    private static String playerWarpColor(Warp warp) {
        return warp.getCost() > 0 ? "#FFAA00" : "#55FFFF";
    }

    /** Accepts an enum name or a dropdown index; {@code null} when unrecognised. */
    private static Warp.Visibility parseVisibility(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return Warp.Visibility.valueOf(value);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            Warp.Visibility[] values = {Warp.Visibility.PUBLIC, Warp.Visibility.PERMISSION, Warp.Visibility.HIDDEN};
            int index = Integer.parseInt(value);
            if (index >= 0 && index < values.length) {
                return values[index];
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }
}
