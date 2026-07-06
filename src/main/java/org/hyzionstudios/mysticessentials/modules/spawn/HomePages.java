package org.hyzionstudios.mysticessentials.modules.spawn;

import java.util.List;
import java.util.Map;

import org.hyzionstudios.mysticessentials.api.model.Home;
import org.hyzionstudios.mysticessentials.api.model.MysticLocation;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.platform.Conversions;
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
 * The Homes UI ({@code /home} or {@code /homes} with no name): lists the
 * player's homes with teleport / move-here / rename / delete for the selected
 * home, shows the used/allowed home count, and offers a set-home-here form.
 * The list is built from {@code HomeRow.ui} template appends.
 */
final class HomePages {

    static final String HOMES_UI = "MysticEssentials/Homes.ui";
    static final String HOME_ROW_UI = "MysticEssentials/HomeRow.ui";

    private HomePages() {
    }

    static final class HomesPage extends MysticPage {
        private final SpawnModule spawn;
        private final String selectedName;

        HomesPage(MysticCore core, SpawnModule spawn, PlayerRef player, String selectedName) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.spawn = spawn;
            this.selectedName = selectedName;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(HOMES_UI);
            List<Home> homes = spawn.getHomes(player.getUuid());
            cmd.set("#HomeCount.Text", homes.size() + " / " + spawn.homeLimit(player.getUuid()));
            cmd.set("#HomesEmpty.Visible", homes.isEmpty());
            for (int i = 0; i < homes.size(); i++) {
                Home home = homes.get(i);
                String row = "#HomeList[" + i + "]";
                cmd.append("#HomeList", HOME_ROW_UI);
                cmd.set(row + " #Name.Text", home.getName());
                cmd.set(row + " #Meta.Text", locationText(home.getLocation()));
                event.addEventBinding(CustomUIEventBindingType.Activating, row,
                        new EventData().put("action", "select").put("home", home.getName()));
            }

            Home selected = selectedHome(homes, selectedName);
            cmd.set("#SelectedHome.Text", selected == null ? "No Home" : selected.getName());
            cmd.set("#HomeWorld.Text", selected == null ? "-" : locationText(selected.getLocation()));
            cmd.set("#RenameInput.Value", "");
            cmd.set("#NewHomeName.Value", "");

            if (selected != null) {
                String name = selected.getName();
                event.addEventBinding(CustomUIEventBindingType.Activating, "#TeleportButton",
                        new EventData().put("action", "teleport").put("home", name));
                event.addEventBinding(CustomUIEventBindingType.Activating, "#MoveHereButton",
                        new EventData().put("action", "movehere").put("home", name));
                event.addEventBinding(CustomUIEventBindingType.Activating, "#DeleteButton",
                        new EventData().put("action", "delete").put("home", name));
                event.addEventBinding(CustomUIEventBindingType.Activating, "#RenameButton",
                        new EventData().put("action", "rename").put("home", name)
                                .append("@newname", "#RenameInput.Value"));
            }
            event.addEventBinding(CustomUIEventBindingType.Activating, "#CreateHomeButton",
                    new EventData().put("action", "create").append("@name", "#NewHomeName.Value"));
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            String action = string(payload, "action");
            String homeName = field(payload, "home");
            switch (action) {
                case "select" -> reopen(ref, store, new HomesPage(core, spawn, player, homeName));
                case "teleport" -> {
                    spawn.getHome(player.getUuid(), homeName)
                            .ifPresent(home -> spawn.teleportHome(player, home));
                    reopen(ref, store, new HomesPage(core, spawn, player, homeName));
                }
                case "movehere" -> {
                    boolean moved = spawn.relocateHome(player, homeName);
                    core.getMessageService().sendKey(player, moved
                            ? "home-moved"
                            : "home-move-failed", Map.of("home", homeName));
                    reopen(ref, store, new HomesPage(core, spawn, player, homeName));
                }
                case "delete" -> {
                    boolean deleted = spawn.deleteHome(player.getUuid(), homeName);
                    core.getMessageService().sendKey(player, deleted
                            ? "home-deleted"
                            : "home-missing", Map.of("home", homeName));
                    reopen(ref, store, new HomesPage(core, spawn, player, null));
                }
                case "rename" -> {
                    String newName = field(payload, "newname");
                    boolean renamed = spawn.renameHome(player.getUuid(), homeName, newName);
                    core.getMessageService().sendKey(player, renamed
                            ? "home-renamed-ui"
                            : "home-rename-failed", Map.of("home", newName));
                    reopen(ref, store, new HomesPage(core, spawn, player, renamed ? newName : homeName));
                }
                case "create" -> {
                    String name = field(payload, "name");
                    if (name.isBlank()) {
                        core.getMessageService().sendKey(player, "home-name-required");
                        reopen(ref, store, new HomesPage(core, spawn, player, selectedName));
                        return;
                    }
                    boolean created = spawn.setHome(player.getUuid(), name, Conversions.capture(player));
                    core.getMessageService().sendKey(player, created
                            ? "home-set"
                            : "home-limit", created
                                    ? Map.of("home", name)
                                    : Map.of("limit", Integer.toString(spawn.homeLimit(player.getUuid()))));
                    reopen(ref, store, new HomesPage(core, spawn, player, created ? name : selectedName));
                }
                default -> {
                }
            }
        }

        private static Home selectedHome(List<Home> homes, String selectedName) {
            if (homes.isEmpty()) {
                return null;
            }
            if (selectedName != null) {
                for (Home home : homes) {
                    if (home.getName().equalsIgnoreCase(selectedName)) {
                        return home;
                    }
                }
            }
            return homes.get(0);
        }

        private static String locationText(MysticLocation location) {
            if (location == null) {
                return "-";
            }
            return location.getWorld() + " (" + Math.round(location.getX()) + ", "
                    + Math.round(location.getY()) + ", " + Math.round(location.getZ()) + ")";
        }
    }
}
