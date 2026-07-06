package org.hyzionstudios.mysticessentials.modules.nick;

import org.hyzionstudios.mysticessentials.api.Permissions;
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
 * The Nickname UI ({@code /nick} with no args): a name field, a colour
 * picker (only offered with {@code mysticessentials.nick.color}), and Apply /
 * Remove buttons.
 */
final class NickPages {

    static final String NICK_UI = "MysticEssentials/Nick.ui";

    private NickPages() {
    }

    static final class NickPage extends MysticPage {
        private final NickModule nick;

        NickPage(MysticCore core, NickModule nick, PlayerRef player) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.nick = nick;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(NICK_UI);
            String current = nick.nickname(player.getUuid());
            cmd.set("#CurrentNick.Text", current == null ? "none (using your real name)" : current);
            cmd.set("#NickInput.Value", current == null ? "" : nick.editableNickname(player.getUuid()));

            boolean colorsAllowed = player.hasPermission(Permissions.NICK_COLOR);
            cmd.set("#ColorRow.Visible", colorsAllowed);
            if (colorsAllowed) {
                cmd.set("#NickColor.Color", nick.nicknameColor(player.getUuid()).orElse("#FFFFFF"));
            }

            EventData apply = new EventData().put("action", "apply")
                    .append("@nickname", "#NickInput.Value");
            if (colorsAllowed) {
                apply.append("@color", "#NickColor.Color");
            }
            event.addEventBinding(CustomUIEventBindingType.Activating, "#ApplyButton", apply);
            event.addEventBinding(CustomUIEventBindingType.Activating, "#RemoveButton",
                    new EventData().put("action", "remove"));
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            switch (string(payload, "action")) {
                case "apply" -> {
                    String name = field(payload, "nickname");
                    if (name.isBlank()) {
                        core.getMessageService().sendKey(player, "nick-enter-first");
                        reopen(ref, store, new NickPage(core, nick, player));
                        return;
                    }
                    boolean colorsAllowed = player.hasPermission(Permissions.NICK_COLOR);
                    String color = colorsAllowed ? field(payload, "color") : "";
                    String raw = colorsAllowed ? colorTag(color) + name.trim() : name.trim();
                    NickModule.NickError error = nick.setNickname(player, raw, colorsAllowed);
                    if (error != null) {
                        core.getMessageService().sendKey(player, error.key(), error.params());
                    } else {
                        core.getMessageService().sendKey(player, "nick-set",
                                java.util.Map.of("nickname", nick.nickname(player.getUuid())));
                    }
                    reopen(ref, store, new NickPage(core, nick, player));
                }
                case "remove" -> {
                    nick.clearNickname(player.getUuid());
                    core.getMessageService().sendKey(player, "nick-cleared");
                    reopen(ref, store, new NickPage(core, nick, player));
                }
                default -> {
                }
            }
        }

        private static String colorTag(String value) {
            String color = value == null ? "" : value.trim();
            return color.matches("#[0-9a-fA-F]{6}") ? "<" + color.toUpperCase(java.util.Locale.ROOT) + ">" : "";
        }
    }
}
