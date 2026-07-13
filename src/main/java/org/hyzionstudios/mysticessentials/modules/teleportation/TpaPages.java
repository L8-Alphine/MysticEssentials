package org.hyzionstudios.mysticessentials.modules.teleportation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.teleportation.TeleportationModule.PendingRequest;
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
 * The Teleport Requests UI ({@code /tpa} with no target): the player's
 * favourites (with add/remove), a searchable list of online players with
 * per-row TPA / TPA-HERE buttons, and the player's incoming requests with
 * Accept / Deny. Lists use row-template appends (builtin {@code WarpListPage}
 * pattern). Online-player lists are vanish-filtered.
 */
final class TpaPages {

    static final String TPA_UI = "MysticEssentials/TeleportRequests.ui";
    static final String PLAYER_ROW_UI = "MysticEssentials/TpaPlayerRow.ui";
    static final String FAVORITE_ROW_UI = "MysticEssentials/TpaFavoriteRow.ui";
    static final String REQUEST_ROW_UI = "MysticEssentials/TpaRequestRow.ui";

    private TpaPages() {
    }

    static final class TpaPage extends MysticPage {
        private final TeleportationModule teleport;
        private final String search;

        TpaPage(MysticCore core, TeleportationModule teleport, PlayerRef player) {
            this(core, teleport, player, "");
        }

        TpaPage(MysticCore core, TeleportationModule teleport, PlayerRef player, String search) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.teleport = teleport;
            this.search = search == null ? "" : search;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(TPA_UI);

            // Favourites: online first, then offline (grayed, no TPA buttons).
            Map<UUID, String> favorites = teleport.favorites(player.getUuid());
            List<Map.Entry<UUID, String>> favoriteEntries = new ArrayList<>(favorites.entrySet());
            favoriteEntries.sort(Comparator
                    .comparing((Map.Entry<UUID, String> e) -> core.platform().findPlayer(e.getKey()).isEmpty())
                    .thenComparing(Map.Entry::getValue, String.CASE_INSENSITIVE_ORDER));
            cmd.set("#FavoritesEmpty.Visible", favoriteEntries.isEmpty());
            for (int i = 0; i < favoriteEntries.size(); i++) {
                Map.Entry<UUID, String> favorite = favoriteEntries.get(i);
                PlayerRef online = core.platform().findPlayer(favorite.getKey()).orElse(null);
                String name = online != null ? online.getUsername() : favorite.getValue();
                String row = "#FavoriteList[" + i + "]";
                cmd.append("#FavoriteList", FAVORITE_ROW_UI);
                cmd.set(row + " #Name.Text", name);
                cmd.set(row + " #Status.Text", online != null ? "" : "offline");
                boolean visible = online != null;
                cmd.set(row + " #TpaButton.Visible", visible);
                cmd.set(row + " #HereButton.Visible", visible);
                if (online != null) {
                    event.addEventBinding(CustomUIEventBindingType.Activating, row + " #TpaButton",
                            new EventData().put("action", "tpa").put("target", favorite.getKey().toString()));
                    event.addEventBinding(CustomUIEventBindingType.Activating, row + " #HereButton",
                            new EventData().put("action", "tpahere").put("target", favorite.getKey().toString()));
                }
                event.addEventBinding(CustomUIEventBindingType.Activating, row + " #UnfavButton",
                        new EventData().put("action", "unfavorite").put("target", favorite.getKey().toString()));
            }

            List<PlayerRef> others = new ArrayList<>();
            for (PlayerRef online : core.vanish().visiblePlayers(player.getUuid())) {
                if (!online.getUuid().equals(player.getUuid())
                        && matchesSearch(search, online.getUsername())) {
                    others.add(online);
                }
            }
            others.sort(Comparator.comparing(PlayerRef::getUsername, String.CASE_INSENSITIVE_ORDER));

            cmd.set("#PlayersEmpty.Visible", others.isEmpty());
            for (int i = 0; i < others.size(); i++) {
                PlayerRef other = others.get(i);
                String row = "#PlayerList[" + i + "]";
                cmd.append("#PlayerList", PLAYER_ROW_UI);
                cmd.set(row + " #Name.Text", other.getUsername());
                cmd.set(row + " #FavButton.Visible", !favorites.containsKey(other.getUuid()));
                event.addEventBinding(CustomUIEventBindingType.Activating, row + " #TpaButton",
                        new EventData().put("action", "tpa").put("target", other.getUuid().toString()));
                event.addEventBinding(CustomUIEventBindingType.Activating, row + " #HereButton",
                        new EventData().put("action", "tpahere").put("target", other.getUuid().toString()));
                event.addEventBinding(CustomUIEventBindingType.Activating, row + " #FavButton",
                        new EventData().put("action", "favorite").put("target", other.getUuid().toString()));
            }

            List<PendingRequest> requests = teleport.incomingRequests(player.getUuid());
            cmd.set("#RequestsEmpty.Visible", requests.isEmpty());
            for (int i = 0; i < requests.size(); i++) {
                PendingRequest request = requests.get(i);
                String row = "#RequestList[" + i + "]";
                cmd.append("#RequestList", REQUEST_ROW_UI);
                cmd.set(row + " #Text.Text", request.requesterTeleports()
                        ? request.requesterName() + " wants to teleport to you"
                        : request.requesterName() + " wants you to teleport to them");
                event.addEventBinding(CustomUIEventBindingType.Activating, row + " #AcceptButton",
                        new EventData().put("action", "accept").put("requester", request.requester().toString()));
                event.addEventBinding(CustomUIEventBindingType.Activating, row + " #DenyButton",
                        new EventData().put("action", "deny").put("requester", request.requester().toString()));
            }

            event.addEventBinding(CustomUIEventBindingType.ValueChanged, "#SearchInput",
                    new EventData().put("action", "search").append("@search", "#SearchInput.Value"));
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            String action = string(payload, "action");
            switch (action) {
                case "tpa", "tpahere" -> {
                    UUID target = parseUuid(field(payload, "target"));
                    PlayerRef targetRef = target == null ? null
                            : core.platform().findPlayer(target).orElse(null);
                    if (targetRef == null) {
                        core.getMessageService().sendKey(player, "teleport-target-offline");
                    } else if (teleport.sendRequest(player, targetRef, "tpa".equals(action))) {
                        core.getMessageService().send(player,
                                "&7Teleport request sent to &e" + targetRef.getUsername());
                    }
                    reopen(ref, store, new TpaPage(core, teleport, player, search));
                }
                case "favorite" -> {
                    UUID target = parseUuid(field(payload, "target"));
                    PlayerRef targetRef = target == null ? null
                            : core.platform().findPlayer(target).orElse(null);
                    if (targetRef != null) {
                        teleport.addFavorite(player.getUuid(), target, targetRef.getUsername());
                    }
                    reopen(ref, store, new TpaPage(core, teleport, player, search));
                }
                case "unfavorite" -> {
                    UUID target = parseUuid(field(payload, "target"));
                    if (target != null) {
                        teleport.removeFavorite(player.getUuid(), target);
                    }
                    reopen(ref, store, new TpaPage(core, teleport, player, search));
                }
                case "accept" -> {
                    teleport.acceptRequest(player, parseUuid(field(payload, "requester")));
                    reopen(ref, store, new TpaPage(core, teleport, player, search));
                }
                case "deny" -> {
                    teleport.denyRequest(player, parseUuid(field(payload, "requester")));
                    reopen(ref, store, new TpaPage(core, teleport, player, search));
                }
                case "search" -> reopen(ref, store,
                        new TpaPage(core, teleport, player, field(payload, "search")));
                default -> {
                }
            }
        }

        private static UUID parseUuid(String raw) {
            try {
                return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }
}
