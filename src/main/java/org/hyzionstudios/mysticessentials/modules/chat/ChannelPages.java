package org.hyzionstudios.mysticessentials.modules.chat;

import static org.hyzionstudios.mysticessentials.platform.ui.MysticPage.uiText;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.chat.roster.ChannelActivity;
import org.hyzionstudios.mysticessentials.modules.chat.roster.ChannelMemberView;
import org.hyzionstudios.mysticessentials.modules.chat.roster.ChannelParticipation;
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
    static final String ROSTER_UI = "MysticEssentials/ChannelRoster.ui";
    static final String MEMBERS_UI = "MysticEssentials/ChannelMembers.ui";
    static final String MEMBER_ROW_UI = "MysticEssentials/ChannelMemberRow.ui";
    static final String MEMBER_ACTIONS_UI = "MysticEssentials/ChannelMemberActions.ui";

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
            cmd.set("#CurrentChannelLabel.TextSpans", uiText("#CurrentChannelLabel.TextSpans", "Speaking: " + channels.currentDisplayName(player)));

            boolean canViewRoster = channels.canViewRoster(player);
            cmd.set("#ViewMembersButton.Visible", canViewRoster);
            if (canViewRoster) {
                event.addEventBinding(CustomUIEventBindingType.Activating, "#ViewMembersButton",
                        new EventData().put("action", "members"));
            }

            List<ChannelRow> rows = channels.channelRowsFor(player);
            cmd.set("#ChannelEmpty.Visible", rows.isEmpty());
            for (int i = 0; i < rows.size(); i++) {
                ChannelRow row = rows.get(i);
                String sel = "#ChannelList[" + i + "]";
                cmd.append("#ChannelList", CHANNEL_ROW_UI);
                cmd.set(sel + " #Name.TextSpans", uiText(sel + " #Name.TextSpans", row.name()));
                cmd.set(sel + " #Meta.TextSpans", uiText(sel + " #Meta.TextSpans", rowSubtitle(row)));
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
                case "members" -> reopen(ref, store,
                        new ChannelMembersPage(core, channels, player, channels.currentChannelId(player), ""));
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
                cmd.set("#ChannelName.TextSpans", uiText("#ChannelName.TextSpans", "No Channel"));
                cmd.set("#ChannelType.TextSpans", uiText("#ChannelType.TextSpans", "-"));
                cmd.set("#ChannelPrefix.TextSpans", uiText("#ChannelPrefix.TextSpans", "-"));
                cmd.set("#ChannelStatus.TextSpans", uiText("#ChannelStatus.TextSpans", "-"));
                return;
            }
            cmd.set("#ChannelName.TextSpans", uiText("#ChannelName.TextSpans", selected.name()));
            cmd.set("#ChannelDetailSwatch.Background", safeColor(selected.color()));
            cmd.set("#ChannelType.TextSpans", uiText("#ChannelType.TextSpans", selected.type()));
            cmd.set("#ChannelPrefix.TextSpans", uiText("#ChannelPrefix.TextSpans", selected.prefix().isBlank()
                    ? "none" : stripColorCodes(selected.prefix())));
            cmd.set("#ChannelStatus.TextSpans", uiText("#ChannelStatus.TextSpans", selected.access().isBlank() ? "available" : selected.access()));
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
                cmd.set("#ManageChannelName.TextSpans", uiText("#ManageChannelName.TextSpans", "No temporary channel"));
                cmd.set("#ManageExpiry.TextSpans", uiText("#ManageExpiry.TextSpans", "Create one with /channel temp <id>."));
            } else {
                cmd.set("#ManageChannelName.TextSpans", uiText("#ManageChannelName.TextSpans", channel.id));
                cmd.set("#ManageExpiry.TextSpans", uiText("#ManageExpiry.TextSpans", expiryText(
                        channels.ownedTemporaryChannelExpiry(player.getUuid()).orElse(null))));
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

    // ----- Compact roster page -----------------------------------------------

    static final class ChannelRosterPage extends MysticPage {
        private final ChannelsSubModule channels;
        private final String channelId;

        ChannelRosterPage(MysticCore core, ChannelsSubModule channels, PlayerRef player, String channelId) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.channels = channels;
            this.channelId = channelId;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(ROSTER_UI);
            cmd.set("#RosterChannel.TextSpans", uiText("#RosterChannel.TextSpans", channels.displayNameOfId(channelId)));

            List<ChannelMemberView> members = channels.rosterFor(channelId);
            cmd.set("#RosterCounts.TextSpans", uiText("#RosterCounts.TextSpans", countsSummary(members)));
            cmd.set("#RosterEmpty.Visible", members.isEmpty());

            int shown = renderMemberRows(cmd, members, channels.rosterMaxVisible());
            if (shown < members.size()) {
                cmd.set("#RosterMore.Visible", true);
                cmd.set("#RosterMore.TextSpans", uiText("#RosterMore.TextSpans", "Showing " + shown + " of " + members.size()
                        + " — open the full member list."));
            }

            event.addEventBinding(CustomUIEventBindingType.Activating, "#FullListButton",
                    new EventData().put("action", "full"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                    new EventData().put("action", "close"));
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            String action = string(parse(data), "action");
            switch (action) {
                case "full" -> reopen(ref, store, new ChannelMembersPage(core, channels, player, channelId, ""));
                case "close" -> close(ref, store);
                default -> {
                }
            }
        }
    }

    // ----- Expanded member-management page ------------------------------------

    static final class ChannelMembersPage extends MysticPage {
        private final ChannelsSubModule channels;
        private final String channelId;
        private final String search;

        ChannelMembersPage(MysticCore core, ChannelsSubModule channels, PlayerRef player, String channelId,
                String search) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.channels = channels;
            this.channelId = channelId;
            this.search = search == null ? "" : search;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(MEMBERS_UI);

            List<ChannelMemberView> all = channels.rosterFor(channelId);
            cmd.set("#InfoName.TextSpans", uiText("#InfoName.TextSpans", channels.displayNameOfId(channelId)));
            cmd.set("#InfoType.TextSpans", uiText("#InfoType.TextSpans", channels.isTemporaryChannel(channelId)
                    ? "Temporary Channel" : "Server Channel"));
            cmd.set("#InfoOwner.TextSpans", uiText("#InfoOwner.TextSpans", ownerName()));
            cmd.set("#InfoAccess.TextSpans", uiText("#InfoAccess.TextSpans", channels.isTemporaryChannel(channelId) ? "Player-Owned" : "Server"));
            cmd.set("#InfoMembers.TextSpans", uiText("#InfoMembers.TextSpans", String.valueOf(all.size())));

            List<ChannelMemberView> shown = new ArrayList<>();
            for (ChannelMemberView view : all) {
                if (matchesSearch(search, view.name(), view.primaryTag(), view.secondaryTag(),
                        view.serverRank(), view.participation().label(), view.role().name())) {
                    shown.add(view);
                }
            }
            cmd.set("#SearchInput.Value", search);
            cmd.set("#MembersEmpty.Visible", shown.isEmpty());
            renderMemberRows(cmd, shown, Integer.MAX_VALUE);
            for (int i = 0; i < shown.size(); i++) {
                event.addEventBinding(CustomUIEventBindingType.Activating, "#MemberList[" + i + "]",
                        new EventData().put("action", "select").put("uuid", shown.get(i).playerId().toString()));
            }
            cmd.set("#FooterCounts.TextSpans", uiText("#FooterCounts.TextSpans", countsSummary(all)));

            event.addEventBinding(CustomUIEventBindingType.Activating, "#SearchButton",
                    new EventData().put("action", "search").append("@search", "#SearchInput.Value"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#RefreshButton",
                    new EventData().put("action", "refresh"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseMembersButton",
                    new EventData().put("action", "close"));
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            String action = string(payload, "action");
            switch (action) {
                case "search" -> reopen(ref, store,
                        new ChannelMembersPage(core, channels, player, channelId, field(payload, "search")));
                case "refresh" -> reopen(ref, store,
                        new ChannelMembersPage(core, channels, player, channelId, search));
                case "select" -> {
                    UUID target = tryUuid(string(payload, "uuid"));
                    if (target != null) {
                        reopen(ref, store,
                                new ChannelMemberActionsPage(core, channels, player, channelId, target, search));
                    }
                }
                case "close" -> close(ref, store);
                default -> {
                }
            }
        }

        private String ownerName() {
            UUID owner = channels.channelOwner(channelId).orElse(null);
            if (owner == null) {
                return "Server";
            }
            return core.platform().findPlayer(owner)
                    .map(PlayerRef::getUsername)
                    .orElse("(offline)");
        }
    }

    // ----- Member actions page (§12/§13) --------------------------------------

    static final class ChannelMemberActionsPage extends MysticPage {
        private final ChannelsSubModule channels;
        private final String channelId;
        private final UUID target;
        private final String search;

        ChannelMemberActionsPage(MysticCore core, ChannelsSubModule channels, PlayerRef player, String channelId,
                UUID target, String search) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.channels = channels;
            this.channelId = channelId;
            this.target = target;
            this.search = search == null ? "" : search;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(MEMBER_ACTIONS_UI);
            event.addEventBinding(CustomUIEventBindingType.Activating, "#BackBtn",
                    new EventData().put("action", "back"));

            ChannelMemberView view = channels.rosterMember(channelId, target).orElse(null);
            if (view == null) {
                cmd.set("#MName.TextSpans", uiText("#MName.TextSpans", "Member left the channel"));
                cmd.set("#MTags.TextSpans", uiText("#MTags.TextSpans", ""));
                cmd.set("#MParticipation.TextSpans", uiText("#MParticipation.TextSpans", "-"));
                cmd.set("#MModeration.TextSpans", uiText("#MModeration.TextSpans", "-"));
                return;
            }

            boolean canMod = channels.canModerateChannel(player, channelId);
            boolean canOwner = channels.canOwnerManageChannel(player, channelId);
            boolean targetOwner = channels.isOwnerOf(channelId, target);
            boolean targetMod = channels.isModeratorOf(channelId, target);
            boolean protectedTarget = targetOwner || view.staff();
            boolean muted = view.participation() == ChannelParticipation.MUTED;
            boolean listener = view.participation() == ChannelParticipation.LISTENER;

            cmd.set("#MName.TextSpans", uiText("#MName.TextSpans", view.name()));
            cmd.set("#MTags.TextSpans", uiText("#MTags.TextSpans", view.hasSecondaryTag()
                    ? view.primaryTag() + " · " + view.secondaryTag() : view.primaryTag()));
            cmd.set("#MParticipation.TextSpans", uiText("#MParticipation.TextSpans", participationText(view)));
            cmd.set("#MRank.TextSpans", uiText("#MRank.TextSpans", view.serverRank() == null || view.serverRank().isBlank()
                    ? "-" : view.serverRank()));
            cmd.set("#MJoined.TextSpans", uiText("#MJoined.TextSpans", joinedText(channels.memberJoinedAt(channelId, target).orElse(null))));
            cmd.set("#MModeration.TextSpans", uiText("#MModeration.TextSpans", moderationText(canMod, muted)));

            // Actions are gated server-side; the client only reflects that gating (§24).
            boolean actionable = canMod && !protectedTarget;
            cmd.set("#MuteInputs.Visible", actionable);
            bindIf(cmd, event, "#SpeakerBtn", actionable && (muted || listener), "speaker");
            bindIf(cmd, event, "#ListenerBtn", actionable && !muted && !listener, "listener");
            bindMute(cmd, event, actionable && !muted);
            bindIf(cmd, event, "#UnmuteBtn", actionable && muted, "unmute");
            bindIf(cmd, event, "#RemoveBtn", actionable, "remove");

            boolean ownerActionable = canOwner && !protectedTarget;
            bindIf(cmd, event, "#ModAddBtn", ownerActionable && !targetMod, "modadd");
            bindIf(cmd, event, "#ModRemoveBtn", ownerActionable && targetMod, "modremove");
            bindIf(cmd, event, "#BanBtn", ownerActionable, "ban");
            bindIf(cmd, event, "#TransferBtn", ownerActionable, "transfer");
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            String action = string(payload, "action");
            if (action.equals("back")) {
                reopen(ref, store, new ChannelMembersPage(core, channels, player, channelId, search));
                return;
            }
            boolean returnToList = switch (action) {
                case "speaker" -> resultOk(channels.setListenerMode(player, channelId, target, false));
                case "listener" -> {
                    channels.setListenerMode(player, channelId, target, true);
                    yield false;
                }
                case "mute" -> {
                    long duration = parseDurationSeconds(field(payload, "duration"));
                    channels.muteMember(player, channelId, target, duration, field(payload, "reason"));
                    yield false;
                }
                case "unmute" -> {
                    channels.unmuteMember(player, channelId, target);
                    yield false;
                }
                case "modadd" -> {
                    channels.assignModerator(player, channelId, target);
                    yield false;
                }
                case "modremove" -> {
                    channels.removeModerator(player, channelId, target);
                    yield false;
                }
                case "remove" -> resultOk(channels.removeMember(player, channelId, target));
                case "ban" -> resultOk(channels.banMember(player, channelId, target));
                case "transfer" -> resultOk(channels.requestTransfer(player, channelId, target));
                default -> false;
            };
            if (returnToList) {
                reopen(ref, store, new ChannelMembersPage(core, channels, player, channelId, search));
            } else {
                reopen(ref, store,
                        new ChannelMemberActionsPage(core, channels, player, channelId, target, search));
            }
        }

        private static boolean resultOk(ChannelsSubModule.ManageResult result) {
            return result == ChannelsSubModule.ManageResult.OK;
        }

        private void bindIf(UICommandBuilder cmd, UIEventBuilder event, String selector, boolean visible,
                String action) {
            cmd.set(selector + ".Visible", visible);
            if (visible) {
                event.addEventBinding(CustomUIEventBindingType.Activating, selector,
                        new EventData().put("action", action));
            }
        }

        private void bindMute(UICommandBuilder cmd, UIEventBuilder event, boolean visible) {
            cmd.set("#MuteBtn.Visible", visible);
            if (visible) {
                event.addEventBinding(CustomUIEventBindingType.Activating, "#MuteBtn",
                        new EventData().put("action", "mute")
                                .append("@duration", "#MuteDuration.Value")
                                .append("@reason", "#MuteReason.Value"));
            }
        }

        private static String participationText(ChannelMemberView view) {
            ChannelActivity activity = view.activity();
            if (activity == ChannelActivity.SPEAKING || activity == ChannelActivity.RECENTLY_ACTIVE) {
                return view.participation().label() + " · " + activity.label();
            }
            return view.participation().label();
        }

        private static String moderationText(boolean canMod, boolean muted) {
            if (!muted) {
                return "No active restrictions";
            }
            return canMod ? "Channel muted" : "Restricted";
        }

        private static String joinedText(Instant joinedAt) {
            if (joinedAt == null) {
                return "-";
            }
            long minutes = Math.max(0, Duration.between(joinedAt, Instant.now()).toMinutes());
            if (minutes < 1) {
                return "just now";
            }
            if (minutes < 60) {
                return minutes + " minute" + (minutes == 1 ? "" : "s") + " ago";
            }
            long hours = minutes / 60;
            return hours + " hour" + (hours == 1 ? "" : "s") + " ago";
        }

        private static long parseDurationSeconds(String raw) {
            if (raw == null || raw.isBlank()) {
                return 0;
            }
            String value = raw.trim().toLowerCase();
            long multiplier = 1;
            char unit = value.charAt(value.length() - 1);
            if (!Character.isDigit(unit)) {
                multiplier = switch (unit) {
                    case 's' -> 1;
                    case 'm' -> 60;
                    case 'h' -> 3600;
                    case 'd' -> 86400;
                    default -> 0;
                };
                value = value.substring(0, value.length() - 1);
            }
            if (multiplier == 0) {
                return 0;
            }
            try {
                long number = Long.parseLong(value.trim());
                return number > 0 ? number * multiplier : 0;
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    // ----- Roster row rendering (shared by both roster pages) ------------------

    /**
     * Appends up to {@code cap} member rows to {@code #MemberList}, returning the
     * number rendered. Both roster {@code .ui} files declare the same list id and use
     * the {@link #MEMBER_ROW_UI} template, so this drives both views.
     */
    private static int renderMemberRows(UICommandBuilder cmd, List<ChannelMemberView> members, int cap) {
        int shown = Math.min(cap, members.size());
        for (int i = 0; i < shown; i++) {
            ChannelMemberView view = members.get(i);
            String sel = "#MemberList[" + i + "]";
            cmd.append("#MemberList", MEMBER_ROW_UI);
            cmd.set(sel + " #Name.TextSpans", uiText(sel + " #Name.TextSpans", view.name()));
            cmd.set(sel + " #Sub.TextSpans", uiText(sel + " #Sub.TextSpans", subtitle(view)));
            cmd.set(sel + " #Tag.TextSpans", uiText(sel + " #Tag.TextSpans", view.primaryTag()));
            cmd.set(sel + " #TagChip.Background", safeColor(view.tagColor()));
            cmd.set(sel + " #Swatch.Background", safeColor(view.tagColor()));
            if (view.hasSecondaryTag()) {
                cmd.set(sel + " #Secondary.Visible", true);
                cmd.set(sel + " #Secondary.TextSpans", uiText(sel + " #Secondary.TextSpans", view.secondaryTag()));
            }
        }
        return shown;
    }

    private static String subtitle(ChannelMemberView view) {
        StringBuilder subtitle = new StringBuilder(view.participation().label());
        ChannelActivity activity = view.activity();
        // Only surface activity when it adds information beyond the participation label.
        if (activity == ChannelActivity.SPEAKING || activity == ChannelActivity.RECENTLY_ACTIVE) {
            subtitle.append(" · ").append(activity.label());
        }
        if (view.serverRank() != null && !view.serverRank().isBlank()) {
            subtitle.append(" · ").append(view.serverRank());
        }
        return subtitle.toString();
    }

    private static String countsSummary(List<ChannelMemberView> members) {
        long speaking = members.stream()
                .filter(view -> view.participation() == ChannelParticipation.SPEAKER)
                .count();
        long listening = members.size() - speaking;
        return members.size() + (members.size() == 1 ? " Member · " : " Members · ")
                + speaking + " Speaking · " + listening + " Listening";
    }

    // ----- Shared helpers ----------------------------------------------------

    /** @return {@code color} if it is a {@code #RRGGBB} hex string, else a neutral blue. */
    private static String safeColor(String color) {
        return color == null || !color.matches("#[0-9a-fA-F]{6}") ? "#7a9cc6" : color;
    }

    private static UUID tryUuid(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String stripColorCodes(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text
                .replaceAll("(?i)&[0-9a-fk-or]", "")
                .replaceAll("(?i)<(?:#[0-9a-f]{3,6}|/?(?:gradient|rainbow|color|c|bold|b|italic|i))[^>]*>", "");
    }

}
