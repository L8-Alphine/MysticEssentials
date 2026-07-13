package org.hyzionstudios.mysticessentials.modules.afk;

import java.util.List;

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

/** Custom UI pages for the AFK module. */
final class AfkPages {

    static final String ZONES_UI = "MysticEssentials/AfkZones.ui";
    static final String ZONE_ROW_UI = "MysticEssentials/AfkZoneRow.ui";

    private AfkPages() {
    }

    /**
     * Zone picker opened by {@code /afk} when more than one zone is available:
     * one clickable row per permitted zone, plus a "Just AFK Here" button that
     * skips the teleport. Selecting either option marks the player AFK.
     */
    static final class ZoneSelectPage extends MysticPage {
        private final AfkModule afk;
        private final String reason;

        ZoneSelectPage(MysticCore core, AfkModule afk, PlayerRef player, String reason) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.afk = afk;
            this.reason = reason;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(ZONES_UI);

            List<AfkConfig.Zone> zones = afk.permittedZones(player);
            cmd.set("#ZonesEmpty.Visible", zones.isEmpty());
            for (int i = 0; i < zones.size(); i++) {
                AfkConfig.Zone zone = zones.get(i);
                String row = "#ZoneList[" + i + "]";
                cmd.append("#ZoneList", ZONE_ROW_UI);
                cmd.set(row + " #Name.Text", zone.name);
                cmd.set(row + " #Meta.Text",
                        (zone.cornerA.getWorld() == null ? "?" : zone.cornerA.getWorld())
                                + "  ·  " + AfkModule.formatSize(zone));
                event.addEventBinding(CustomUIEventBindingType.Activating, row,
                        new EventData().put("action", "select").put("zone", zone.name));
            }

            event.addEventBinding(CustomUIEventBindingType.Activating, "#AfkHereButton",
                    new EventData().put("action", "here"));
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            switch (string(payload, "action")) {
                case "select" -> {
                    AfkConfig.Zone zone = afk.findZone(field(payload, "zone")).orElse(null);
                    close(ref, store);
                    if (zone != null) {
                        afk.goAfk(player, reason, zone);
                    }
                }
                case "here" -> {
                    close(ref, store);
                    afk.goAfk(player, reason, null);
                }
                default -> {
                }
            }
        }
    }
}
