package org.hyzionstudios.mysticessentials.modules.portals;

import java.util.List;
import java.util.Map;

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
 * Custom UI pages for portals. The single admin page edits every portal
 * setting; structural changes (type switch, toggles, position capture) apply
 * the pending field values and reopen the page so the visible sections always
 * match the selected type.
 */
final class PortalPages {

    static final String PORTAL_ADMIN_UI = "MysticEssentials/PortalAdmin.ui";

    private PortalPages() {
    }

    static final class PortalAdminPage extends MysticPage {
        private final PortalsModule portals;
        private final String portalKey;

        PortalAdminPage(MysticCore core, PortalsModule portals, PlayerRef player, String portalKey) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.portals = portals;
            this.portalKey = portalKey;
        }

        private Portal portal() {
            return portals.snapshot().stream()
                    .filter(p -> p.key().equals(portalKey))
                    .findFirst().orElse(null);
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(PORTAL_ADMIN_UI);
            Portal portal = portal();
            if (portal == null) {
                cmd.set("#PortalHeading.Text", "Portal not found");
                return;
            }
            Portal.Type type = portal.getType();
            cmd.set("#PortalHeading.Text",
                    (portal.getName().isBlank() ? portal.getId() : portal.getName()));
            cmd.set("#PortalMeta.Text", portals.worldLabel(portal.getWorld())
                    + "  @  " + portal.getX() + ", " + portal.getY() + ", " + portal.getZ()
                    + "   (" + portal.getId() + ")");
            cmd.set("#PortalName.Value", portal.getName());
            // Dropdown entries MUST be DropdownEntryInfo — UICommandBuilder.set(List)
            // resolves a protocol codec from the element class and rejects Strings.
            cmd.set("#PortalType.Entries", List.of(
                    new DropdownEntryInfo(LocalizableString.fromString("Teleport to world"), "WORLD"),
                    new DropdownEntryInfo(LocalizableString.fromString("Send to server"), "SERVER"),
                    new DropdownEntryInfo(LocalizableString.fromString("Run command"), "COMMAND")));
            cmd.set("#PortalType.Value", type.name());

            cmd.set("#WorldSection.Visible", type == Portal.Type.WORLD);
            cmd.set("#ServerSection.Visible", type == Portal.Type.SERVER);
            cmd.set("#CommandSection.Visible", type == Portal.Type.COMMAND);

            cmd.set("#TargetWorld.Value", portal.getTargetWorld());
            cmd.set("#UseLocationToggle.Text",
                    portal.isUseLocation() ? "Exact location: ON" : "Exact location: OFF");
            cmd.set("#LocationRow.Visible", portal.isUseLocation());
            cmd.set("#PosX.Value", portal.isUseLocation() ? trimmed(portal.getPosX()) : "");
            cmd.set("#PosY.Value", portal.isUseLocation() ? trimmed(portal.getPosY()) : "");
            cmd.set("#PosZ.Value", portal.isUseLocation() ? trimmed(portal.getPosZ()) : "");
            cmd.set("#Facing.Entries", List.of(
                    new DropdownEntryInfo(LocalizableString.fromString("Keep rotation"), ""),
                    new DropdownEntryInfo(LocalizableString.fromString("North"), "N"),
                    new DropdownEntryInfo(LocalizableString.fromString("East"), "E"),
                    new DropdownEntryInfo(LocalizableString.fromString("South"), "S"),
                    new DropdownEntryInfo(LocalizableString.fromString("West"), "W")));
            cmd.set("#Facing.Value", portal.getFacing());

            cmd.set("#Host.Value", portal.getHost());
            cmd.set("#Port.Value", portal.getPort() > 0 ? Integer.toString(portal.getPort()) : "");

            cmd.set("#Command.Value", portal.getCommand());
            cmd.set("#CommandSender.Entries", List.of(
                    new DropdownEntryInfo(LocalizableString.fromString("Console"), "server"),
                    new DropdownEntryInfo(LocalizableString.fromString("Player"), "player")));
            cmd.set("#CommandSender.Value", portal.getCommandSender());

            cmd.set("#Permission.Value", portal.getPermission());
            cmd.set("#MarkerToggle.Text",
                    portal.isMarkerEnabled() ? "Map marker: ON" : "Map marker: OFF");
            cmd.set("#MarkerRow.Visible", portal.isMarkerEnabled());
            cmd.set("#MarkerText.Value", portal.getMarkerText());
            cmd.set("#MarkerIcon.Value", portal.getMarkerIcon());

            EventData fields = new EventData()
                    .append("@name", "#PortalName.Value")
                    .append("@type", "#PortalType.Value")
                    .append("@targetworld", "#TargetWorld.Value")
                    .append("@posx", "#PosX.Value")
                    .append("@posy", "#PosY.Value")
                    .append("@posz", "#PosZ.Value")
                    .append("@facing", "#Facing.Value")
                    .append("@host", "#Host.Value")
                    .append("@port", "#Port.Value")
                    .append("@command", "#Command.Value")
                    .append("@commandsender", "#CommandSender.Value")
                    .append("@permission", "#Permission.Value")
                    .append("@markertext", "#MarkerText.Value")
                    .append("@markericon", "#MarkerIcon.Value");

            event.addEventBinding(CustomUIEventBindingType.ValueChanged, "#PortalType",
                    new EventData(fields.events()).put("action", "apply"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#UseLocationToggle",
                    new EventData(fields.events()).put("action", "togglelocation"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#HereButton",
                    new EventData(fields.events()).put("action", "poshere"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#MarkerToggle",
                    new EventData(fields.events()).put("action", "togglemarker"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#SaveButton",
                    new EventData(fields.events()).put("action", "save"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#DeletePortalButton",
                    new EventData().put("action", "delete"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                    new EventData().put("action", "close"));
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            if (!portals.canConfigure(player.getUuid())) {
                close(ref, store);
                return;
            }
            JsonObject payload = parse(data);
            String action = string(payload, "action");
            Portal portal = portal();
            if (portal == null) {
                close(ref, store);
                return;
            }
            switch (action) {
                case "apply", "togglelocation", "poshere", "togglemarker", "save" -> {
                    applyFields(portal, payload);
                    switch (action) {
                        case "togglelocation" -> portal.setUseLocation(!portal.isUseLocation());
                        case "togglemarker" -> portal.setMarkerEnabled(!portal.isMarkerEnabled());
                        case "poshere" -> {
                            var position = player.getTransform().getPosition();
                            portal.setUseLocation(true);
                            portal.setPos(position.x(), position.y(), position.z());
                        }
                        default -> {
                        }
                    }
                    portals.save(portal);
                    if ("save".equals(action)) {
                        core.getMessageService().sendKey(player, "portal-saved",
                                Map.of("portal", portal.getName().isBlank() ? portal.getId() : portal.getName()));
                    }
                    reopen(ref, store, new PortalAdminPage(core, portals, player, portalKey));
                }
                case "delete" -> {
                    portals.delete(portal);
                    core.getMessageService().sendKey(player, "portal-removed",
                            Map.of("portal", portal.getName().isBlank() ? portal.getId() : portal.getName()));
                    close(ref, store);
                }
                case "close" -> close(ref, store);
                default -> {
                }
            }
        }

        private void applyFields(Portal portal, JsonObject payload) {
            portal.setName(field(payload, "name"));
            portal.setType(Portal.Type.parse(field(payload, "type")));
            portal.setTargetWorld(field(payload, "targetworld"));
            if (portal.isUseLocation()) {
                portal.setPos(parseDouble(field(payload, "posx"), portal.getPosX()),
                        parseDouble(field(payload, "posy"), portal.getPosY()),
                        parseDouble(field(payload, "posz"), portal.getPosZ()));
            }
            portal.setFacing(field(payload, "facing"));
            portal.setHost(field(payload, "host"));
            portal.setPort((int) parseDouble(field(payload, "port"), 0));
            portal.setCommand(field(payload, "command"));
            portal.setCommandSender(field(payload, "commandsender"));
            portal.setPermission(field(payload, "permission"));
            portal.setMarkerText(field(payload, "markertext"));
            portal.setMarkerIcon(field(payload, "markericon"));
        }

        private static String trimmed(double value) {
            return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
        }
    }
}
