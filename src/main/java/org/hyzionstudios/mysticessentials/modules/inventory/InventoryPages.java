package org.hyzionstudios.mysticessentials.modules.inventory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.hyzionstudios.mysticessentials.core.MysticCore;
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
 * The Inventory Restore UI ({@code /inventory restore <player>}): a scrollable
 * list of the target's inventory snapshots with timestamp, cause, and item
 * count, plus a Restore button per row. Restoring requires the target to be
 * online; the module takes a PreRestore backup automatically.
 */
final class InventoryPages {

    static final String RESTORE_UI = "MysticEssentials/InventoryRestore.ui";
    static final String SNAPSHOT_ROW_UI = "MysticEssentials/InventorySnapshotRow.ui";

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("MMM d yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    private InventoryPages() {
    }

    static final class RestorePage extends MysticPage {
        private final InventoryModule inventory;
        private final UUID targetUuid;
        private final String targetName;
        private final List<InventorySnapshot> snapshots;

        RestorePage(MysticCore core, InventoryModule inventory, PlayerRef viewer,
                UUID targetUuid, String targetName, List<InventorySnapshot> snapshots) {
            super(core, viewer, CustomPageLifetime.CanDismiss);
            this.inventory = inventory;
            this.targetUuid = targetUuid;
            this.targetName = targetName;
            this.snapshots = snapshots;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(RESTORE_UI);
            boolean online = core.platform().findPlayer(targetUuid).isPresent();
            cmd.set("#TargetName.Text", targetName + (online ? "" : " (offline)"));
            cmd.set("#SnapshotsEmpty.Visible", snapshots.isEmpty());
            for (int i = 0; i < snapshots.size(); i++) {
                InventorySnapshot snapshot = snapshots.get(i);
                String row = "#SnapshotList[" + i + "]";
                cmd.append("#SnapshotList", SNAPSHOT_ROW_UI);
                cmd.set(row + " #Cause.Text", snapshot.cause == null ? "?" : snapshot.cause);
                cmd.set(row + " #Time.Text", formatTime(snapshot.timestamp));
                cmd.set(row + " #Items.Text", snapshot.itemCount() + " items");
                event.addEventBinding(CustomUIEventBindingType.Activating, row + " #RestoreButton",
                        new EventData().put("action", "restore").put("id", snapshot.id));
            }
            event.addEventBinding(CustomUIEventBindingType.Activating, "#SnapshotNowButton",
                    new EventData().put("action", "snapshot"));
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            String action = string(payload, "action");
            switch (action) {
                case "restore" -> {
                    String id = field(payload, "id");
                    InventorySnapshot snapshot = snapshots.stream()
                            .filter(s -> s.id != null && s.id.equals(id)).findFirst().orElse(null);
                    PlayerRef target = core.platform().findPlayer(targetUuid).orElse(null);
                    if (snapshot == null) {
                        core.getMessageService().sendKey(player, "inventory-snapshot-missing");
                        inventory.openRestoreUi(player, targetUuid, targetName);
                        return;
                    }
                    if (target == null) {
                        core.getMessageService().send(player,
                                "&c" + targetName + " must be online to restore their inventory.");
                        inventory.openRestoreUi(player, targetUuid, targetName);
                        return;
                    }
                    inventory.restore(target, snapshot).thenAccept(ok -> {
                        core.getMessageService().send(player, ok
                                ? "&aRestored &f" + targetName + "&a's inventory from " + formatTime(snapshot.timestamp) + "."
                                : "&cRestore failed — see the server log.");
                        if (ok) {
                            core.getMessageService().send(target,
                                    "&7Your inventory was restored from a snapshot by an admin.");
                        }
                        inventory.openRestoreUi(player, targetUuid, targetName);
                    });
                }
                case "snapshot" -> {
                    PlayerRef target = core.platform().findPlayer(targetUuid).orElse(null);
                    if (target == null) {
                        core.getMessageService().send(player,
                                "&c" + targetName + " must be online to snapshot their inventory.");
                        inventory.openRestoreUi(player, targetUuid, targetName);
                        return;
                    }
                    inventory.snapshot(target, "Manual").thenAccept(ok ->
                            inventory.openRestoreUi(player, targetUuid, targetName));
                }
                default -> {
                }
            }
        }

        private static String formatTime(String isoDate) {
            try {
                return DATE_FORMAT.format(Instant.parse(isoDate));
            } catch (RuntimeException e) {
                return isoDate == null ? "?" : isoDate;
            }
        }
    }
}
