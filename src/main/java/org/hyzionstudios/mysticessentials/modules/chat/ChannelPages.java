package org.hyzionstudios.mysticessentials.modules.chat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 * Custom UI pages for the chat channel system: the channel browser, the
 * temp-channel creation form, and the temp-channel manager. The browser list is
 * built from the {@code ChannelRow.ui} template appended per channel (builtin
 * {@code WarpListPage} pattern), and actions are routed back through the
 * permission-checked {@code /channel ...} commands.
 */
final class ChannelPages {

    static final String CHANNELS_UI = "MysticEssentials/ChatChannels.ui";
    static final String CHANNEL_ROW_UI = "MysticEssentials/ChannelRow.ui";
    static final String TEMP_UI = "MysticEssentials/TempChannel.ui";
    static final String TEMP_MANAGE_UI = "MysticEssentials/TempChannelManage.ui";

    private ChannelPages() {
    }

    /** One display row in the channel list. */
    record ChannelRow(String id, String name, String prefix, String access, String color, String type) {
    }

    // ----- Channel browser page ----------------------------------------------

    static final class ChannelsPage extends MysticPage {
        private final ChannelsSubModule channels;
        private final String selectedChannelId;

        ChannelsPage(MysticCore core, ChannelsSubModule channels, PlayerRef player) {
            this(core, channels, player, channels.currentChannelId(player));
        }

        ChannelsPage(MysticCore core, ChannelsSubModule channels, PlayerRef player, String selectedChannelId) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.channels = channels;
            this.selectedChannelId = selectedChannelId;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(CHANNELS_UI);
            cmd.set("#CurrentChannelLabel.Text", "Speaking: " + channels.currentDisplayName(player));

            List<ChannelRow> rows = channels.channelRowsFor(player);
            cmd.set("#ChannelEmpty.Visible", rows.isEmpty());
            for (int i = 0; i < rows.size(); i++) {
                ChannelRow row = rows.get(i);
                String sel = "#ChannelList[" + i + "]";
                cmd.append("#ChannelList", CHANNEL_ROW_UI);
                cmd.set(sel + " #Name.Text", row.name());
                cmd.set(sel + " #Meta.Text", rowSubtitle(row));
                cmd.set(sel + " #Swatch.Background", safeColor(row.color()));
                event.addEventBinding(CustomUIEventBindingType.Activating, sel,
                        new EventData().put("action", "select").put("channel", row.id()));
            }

            ChannelRow selected = selectedRow(rows, selectedChannelId);
            applySelectedChannel(cmd, selected);

            if (selected != null) {
                EventData withTarget = new EventData()
                        .put("channel", selected.id())
                        .append("@password", "#PasswordInput.Value");
                event.addEventBinding(CustomUIEventBindingType.Activating, "#SwitchButton",
                        new EventData(withTarget.events()).put("action", "switch"));
                event.addEventBinding(CustomUIEventBindingType.Activating, "#ListenButton",
                        new EventData(withTarget.events()).put("action", "join"));
                event.addEventBinding(CustomUIEventBindingType.Activating, "#LeaveButton",
                        new EventData(withTarget.events()).put("action", "leave"));
            }
            event.addEventBinding(CustomUIEventBindingType.Activating, "#OpenTempButton",
                    new EventData().put("action", "opentemp"));
            boolean ownsTemp = channels.ownedTemporaryChannel(player.getUuid()).isPresent();
            cmd.set("#ManageTempButton.Visible", ownsTemp);
            if (ownsTemp) {
                event.addEventBinding(CustomUIEventBindingType.Activating, "#ManageTempButton",
                        new EventData().put("action", "managetemp"));
            }
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            String action = string(payload, "action");
            String channel = field(payload, "channel");
            String password = field(payload, "password");
            switch (action) {
                case "select" -> reopen(ref, store, new ChannelsPage(core, channels, player, channel));
                case "switch" -> switchAndRefresh(ref, store, channel, password);
                case "join" -> joinAndRefresh(ref, store, channel, password);
                case "leave" -> leaveAndRefresh(ref, store, channel);
                case "opentemp" -> channels.openTempChannelUi(player);
                case "managetemp" -> channels.openTempManageUi(player);
                default -> {
                }
            }
        }

        private void switchAndRefresh(Ref<EntityStore> ref, Store<EntityStore> store, String channel,
                String password) {
            channels.switchChannelWithFeedback(player, channel, password);
            reopen(ref, store, new ChannelsPage(core, channels, player, channel));
        }

        private void joinAndRefresh(Ref<EntityStore> ref, Store<EntityStore> store, String channel,
                String password) {
            channels.joinChannelWithFeedback(player, channel, password);
            reopen(ref, store, new ChannelsPage(core, channels, player, channel));
        }

        private void leaveAndRefresh(Ref<EntityStore> ref, Store<EntityStore> store, String channel) {
            channels.leaveChannelWithFeedback(player, channel);
            reopen(ref, store, new ChannelsPage(core, channels, player, channel));
        }

        private static ChannelRow selectedRow(List<ChannelRow> rows, String channelId) {
            if (rows.isEmpty()) {
                return null;
            }
            for (ChannelRow row : rows) {
                if (row.id().equals(channelId)) {
                    return row;
                }
            }
            return rows.get(0);
        }

        private static void applySelectedChannel(UICommandBuilder cmd, ChannelRow selected) {
            if (selected == null) {
                cmd.set("#ChannelName.Text", "No Channel");
                cmd.set("#ChannelType.Text", "-");
                cmd.set("#ChannelPrefix.Text", "-");
                cmd.set("#ChannelStatus.Text", "-");
                return;
            }
            cmd.set("#ChannelName.Text", selected.name());
            cmd.set("#ChannelDetailSwatch.Background", safeColor(selected.color()));
            cmd.set("#ChannelType.Text", selected.type());
            cmd.set("#ChannelPrefix.Text", selected.prefix().isBlank()
                    ? "none" : stripColorCodes(selected.prefix()));
            cmd.set("#ChannelStatus.Text", selected.access().isBlank() ? "available" : selected.access());
        }

        private static String rowSubtitle(ChannelRow row) {
            String access = row.access().isBlank() ? "available" : row.access();
            return row.type() + " | " + access;
        }
    }

    // ----- Temp channel creation page ----------------------------------------

    static final class TempChannelPage extends MysticPage {
        private final ChannelsSubModule channels;

        TempChannelPage(MysticCore core, ChannelsSubModule channels, PlayerRef player) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.channels = channels;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(TEMP_UI);
            EventData createData = new EventData()
                    .append("@id", "#TempNameInput.Value")
                    .append("@password", "#TempPasswordInput.Value")
                    .append("@prefix", "#TempPrefixInput.Value")
                    .append("@aliases", "#TempAliasesInput.Value");
            event.addEventBinding(CustomUIEventBindingType.Activating, "#CreateTempButton",
                    new EventData(createData.events()).put("action", "create"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#CancelTempButton",
                    new EventData().put("action", "cancel"));
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            String action = string(payload, "action");
            if (action.equals("create")) {
                String id = field(payload, "id");
                if (id.isBlank()) {
                    core.getMessageService().sendKey(player, "chat-channel-temp-name-required");
                    return;
                }
                if (!channels.canCreateTemporaryChannel(player)) {
                    core.getMessageService().sendKey(player, "chat-channel-temp-no-permission");
                    reopen(ref, store, new ChannelsPage(core, channels, player));
                    return;
                }
                boolean created = channels.createTemporaryChannel(player.getUuid(), id, null,
                        blankToNull(field(payload, "password")),
                        blankToNull(field(payload, "prefix")),
                        parseAliases(field(payload, "aliases")));
                core.getMessageService().sendKey(player, created
                        ? "chat-channel-temp-created"
                        : "chat-channel-temp-failed", Map.of("channel", id.toLowerCase()));
                if (created) {
                    reopen(ref, store, new TempChannelManagePage(core, channels, player));
                } else {
                    reopen(ref, store, new ChannelsPage(core, channels, player));
                }
                return;
            }
            reopen(ref, store, new ChannelsPage(core, channels, player));
        }

        private static List<String> parseAliases(String raw) {
            if (raw == null || raw.isBlank()) {
                return List.of();
            }
            List<String> aliases = new ArrayList<>();
            for (String alias : raw.split(",")) {
                String clean = alias.trim();
                if (!clean.isBlank()) {
                    aliases.add(clean);
                }
            }
            return aliases;
        }
    }

    // ----- Temp channel manager page ------------------------------------------

    static final class TempChannelManagePage extends MysticPage {
        private final ChannelsSubModule channels;

        TempChannelManagePage(MysticCore core, ChannelsSubModule channels, PlayerRef player) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.channels = channels;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(TEMP_MANAGE_UI);
            ChatConfig.Channel channel = channels.ownedTemporaryChannel(player.getUuid()).orElse(null);
            if (channel == null) {
                cmd.set("#ManageChannelName.Text", "No temporary channel");
                cmd.set("#ManageExpiry.Text", "Create one with /channel temp <id>.");
            } else {
                cmd.set("#ManageChannelName.Text", channel.id);
                cmd.set("#ManageExpiry.Text", expiryText(
                        channels.ownedTemporaryChannelExpiry(player.getUuid()).orElse(null)));
                cmd.set("#ManagePasswordInput.Value", channel.password == null ? "" : channel.password);
                cmd.set("#ManagePrefixInput.Value", channel.prefix == null ? "" : channel.prefix);
                event.addEventBinding(CustomUIEventBindingType.Activating, "#SaveChannelButton",
                        new EventData().put("action", "save")
                                .append("@password", "#ManagePasswordInput.Value")
                                .append("@prefix", "#ManagePrefixInput.Value"));
                event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseChannelButton",
                        new EventData().put("action", "close"));
            }
            event.addEventBinding(CustomUIEventBindingType.Activating, "#BackToChannelsButton",
                    new EventData().put("action", "back"));
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            String action = string(payload, "action");
            switch (action) {
                case "save" -> {
                    boolean saved = channels.updateTemporaryChannel(player.getUuid(),
                            field(payload, "password"), field(payload, "prefix"));
                    core.getMessageService().sendKey(player, saved
                            ? "chat-channel-temp-updated"
                            : "chat-channel-temp-not-owned");
                    reopen(ref, store, new TempChannelManagePage(core, channels, player));
                }
                case "close" -> {
                    boolean closed = channels.closeTemporaryChannel(player.getUuid());
                    core.getMessageService().sendKey(player, closed
                            ? "chat-channel-temp-closed"
                            : "chat-channel-temp-not-owned");
                    reopen(ref, store, new ChannelsPage(core, channels, player));
                }
                default -> reopen(ref, store, new ChannelsPage(core, channels, player));
            }
        }

        private static String expiryText(Instant expiresAt) {
            if (expiresAt == null || Instant.MAX.equals(expiresAt)) {
                return "Expires when the last player leaves the server.";
            }
            long minutes = Math.max(0, Duration.between(Instant.now(), expiresAt).toMinutes());
            return "Expires in about " + minutes + " minute" + (minutes == 1 ? "" : "s") + ".";
        }
    }

    // ----- Shared helpers ----------------------------------------------------

    static String stripColorCodes(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text
                .replaceAll("(?i)&[0-9a-fk-or]", "")
                .replaceAll("(?i)<(?:#[0-9a-f]{3,6}|/?(?:gradient|rainbow|color|c|bold|b|italic|i))[^>]*>", "");
    }

}
