package org.hyzionstudios.mysticessentials.modules.teleportation.rtp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.api.rtp.RtpDestinationRequest;
import org.hyzionstudios.mysticessentials.api.rtp.RtpProfile;
import org.hyzionstudios.mysticessentials.api.rtp.RtpRequest;
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
 * Random Teleport custom UI pages (spec §10): a player-facing profile selector
 * with a confirmation screen, and an administrator overview that toggles,
 * tests, and clears the cache for each profile. Lists use the verified
 * row-template append pattern (see {@link MysticPage}).
 */
final class RtpPages {

    static final String SELECT_UI = "MysticEssentials/Rtp.ui";
    static final String PROFILE_ROW_UI = "MysticEssentials/RtpProfileRow.ui";
    static final String CONFIRM_UI = "MysticEssentials/RtpConfirm.ui";
    static final String ADMIN_UI = "MysticEssentials/RtpAdmin.ui";
    static final String ADMIN_ROW_UI = "MysticEssentials/RtpAdminRow.ui";

    private RtpPages() {
    }

    private static String costText(MysticCore core, RtpProfile p) {
        return p.cost != null && p.cost.enabled ? core.getEconomyService().format(p.cost.amount) : "Free";
    }

    // ----- Player selection --------------------------------------------------

    static final class SelectPage extends MysticPage {
        private final RtpSubsystem rtp;

        SelectPage(MysticCore core, RtpSubsystem rtp, PlayerRef player) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.rtp = rtp;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(SELECT_UI);
            List<RtpProfile> profiles = new ArrayList<>(rtp.service().getAvailableProfiles(player));
            cmd.set("#Empty.Visible", profiles.isEmpty());
            for (int i = 0; i < profiles.size(); i++) {
                RtpProfile p = profiles.get(i);
                String row = "#ProfileList[" + i + "]";
                cmd.append("#ProfileList", PROFILE_ROW_UI);
                cmd.set(row + " #Name.Text", p.displayNameOrId());
                cmd.set(row + " #World.Text", "World: " + p.world);
                cmd.set(row + " #Details.Text", p.minimumRadius + "-" + p.maximumRadius
                        + " blocks   Cost: " + costText(core, p) + "   Cooldown: " + p.cooldownSeconds + "s");
                long cd = rtp.service().cooldownRemaining(player.getUuid(), p.id);
                cmd.set(row + " #Status.Text", cd > 0 ? "On cooldown: " + cd + "s" : "Ready");
                boolean ready = cd <= 0;
                cmd.set(row + " #TeleportButton.Visible", ready);
                if (ready) {
                    event.addEventBinding(CustomUIEventBindingType.Activating, row + " #TeleportButton",
                            new EventData().put("action", "select").put("profile", p.id));
                }
            }
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            if ("select".equals(string(payload, "action"))) {
                String profileId = field(payload, "profile");
                if (rtp.service().getProfile(profileId).isPresent()) {
                    reopen(ref, store, new ConfirmPage(core, rtp, player, profileId));
                }
            }
        }
    }

    // ----- Player confirmation -----------------------------------------------

    static final class ConfirmPage extends MysticPage {
        private final RtpSubsystem rtp;
        private final String profileId;

        ConfirmPage(MysticCore core, RtpSubsystem rtp, PlayerRef player, String profileId) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.rtp = rtp;
            this.profileId = profileId;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(CONFIRM_UI);
            RtpProfile p = rtp.service().getProfile(profileId).orElse(null);
            if (p == null) {
                reopen(ref, store, new SelectPage(core, rtp, player));
                return;
            }
            cmd.set("#ProfileName.Text", p.displayNameOrId());
            cmd.set("#World.Text", "World: " + p.world);
            cmd.set("#Range.Text", "Range: " + p.minimumRadius + " - " + p.maximumRadius + " blocks");
            cmd.set("#Cost.Text", "Cost: " + costText(core, p));
            cmd.set("#Timing.Text", "Warmup: " + p.warmupSeconds + "s   Cooldown: " + p.cooldownSeconds + "s");
            event.addEventBinding(CustomUIEventBindingType.Activating, "#CancelButton",
                    new EventData().put("action", "cancel"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#ConfirmButton",
                    new EventData().put("action", "confirm"));
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            switch (string(payload, "action")) {
                case "confirm" -> {
                    close(ref, store);
                    rtp.service().teleport(RtpRequest.builder(player.getUuid())
                            .profileId(profileId).actor(player.getUuid()).build());
                }
                case "cancel" -> reopen(ref, store, new SelectPage(core, rtp, player));
                default -> {
                }
            }
        }
    }

    // ----- Administrator overview --------------------------------------------

    static final class AdminPage extends MysticPage {
        private final RtpSubsystem rtp;
        private final Map<String, String> results;

        AdminPage(MysticCore core, RtpSubsystem rtp, PlayerRef player) {
            this(core, rtp, player, new HashMap<>());
        }

        AdminPage(MysticCore core, RtpSubsystem rtp, PlayerRef player, Map<String, String> results) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.rtp = rtp;
            this.results = results;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(ADMIN_UI);
            cmd.set("#Summary.Text", "Queue: " + rtp.engine().activeCount() + " active, "
                    + rtp.engine().queuedCount() + " queued");
            List<RtpProfile> profiles = new ArrayList<>(rtp.config().profiles.values());
            cmd.set("#Empty.Visible", profiles.isEmpty());
            for (int i = 0; i < profiles.size(); i++) {
                RtpProfile p = profiles.get(i);
                String row = "#ProfileList[" + i + "]";
                cmd.append("#ProfileList", ADMIN_ROW_UI);
                cmd.set(row + " #Name.Text", p.displayNameOrId() + (p.enabled ? "" : " (disabled)"));
                cmd.set(row + " #World.Text", "World: " + p.world);
                cmd.set(row + " #Details.Text", p.shape.name().toLowerCase(Locale.ROOT)
                        + "   " + p.minimumRadius + "-" + p.maximumRadius + " blocks");
                cmd.set(row + " #Result.Text", results.getOrDefault(p.id, ""));
                event.addEventBinding(CustomUIEventBindingType.Activating, row + " #ToggleButton",
                        new EventData().put("action", "toggle").put("profile", p.id));
                event.addEventBinding(CustomUIEventBindingType.Activating, row + " #TestButton",
                        new EventData().put("action", "test").put("profile", p.id));
                event.addEventBinding(CustomUIEventBindingType.Activating, row + " #CacheButton",
                        new EventData().put("action", "cache").put("profile", p.id));
            }
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            if (!player.hasPermission(Permissions.RTP_ADMIN)) {
                return;
            }
            JsonObject payload = parse(data);
            String action = string(payload, "action");
            String profileId = field(payload, "profile");
            RtpProfile profile = rtp.config().profiles.get(profileId);
            if (profile == null) {
                return;
            }
            switch (action) {
                case "toggle" -> {
                    profile.enabled = !profile.enabled;
                    rtp.saveConfig();
                    reopen(ref, store, new AdminPage(core, rtp, player, results));
                }
                case "cache" -> {
                    rtp.engine().clearCache(profileId);
                    results.put(profileId, "Cache cleared.");
                    reopen(ref, store, new AdminPage(core, rtp, player, results));
                }
                case "test" -> rtp.service().findDestination(RtpDestinationRequest.of(profileId))
                        .thenAccept(result -> {
                            results.put(profileId, result.found()
                                    ? "Found at " + (int) Math.floor(result.location().getX()) + ", "
                                            + (int) Math.floor(result.location().getY()) + ", "
                                            + (int) Math.floor(result.location().getZ())
                                            + " (" + result.attempts() + " tries)"
                                    : "No destination (" + result.attempts() + " tries, "
                                            + result.failureReason() + ")");
                            reopen(ref, store, new AdminPage(core, rtp, player, results));
                        });
                default -> {
                }
            }
        }
    }
}
