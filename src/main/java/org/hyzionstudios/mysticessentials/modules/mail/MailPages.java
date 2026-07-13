package org.hyzionstudios.mysticessentials.modules.mail;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.api.model.MailAttachment;
import org.hyzionstudios.mysticessentials.api.model.MailMessage;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.message.MysticText;
import org.hyzionstudios.mysticessentials.modules.mail.MailModule.ItemPick;
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
 * The Mail UI ({@code /mail}): a folder-based mail client. A sidebar switches
 * between folders (Inbox / Compose / Sent / Archived / Deleted / Announcements /
 * Attachments, plus reserved "coming soon" tabs); the middle column is the
 * searchable, paged folder list; the right column is the reading pane (with item
 * reward icons and a claim-once flow) or the compose pane (item attachments
 * picked from the player's inventory, plus item+command rewards for admin
 * announcements). Data the view needs is loaded before the page opens (storage is
 * async, page building is not) and the page is re-opened to refresh after every
 * action.
 */
final class MailPages {

    static final String MAIL_UI = "MysticEssentials/Mail.ui";
    static final String MAIL_ROW_UI = "MysticEssentials/MailRow.ui";
    static final String NAV_ROW_UI = "MysticEssentials/MailNavRow.ui";
    static final String REWARD_UI = "MysticEssentials/MailReward.ui";
    static final String PICKER_ROW_UI = "MysticEssentials/MailPickerRow.ui";
    static final String DRAFT_ROW_UI = "MysticEssentials/MailDraftRow.ui";
    static final String PLAYER_ROW_UI = "MysticEssentials/MailPlayerRow.ui";

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault());

    private static final View[] NAV_TOP =
            {View.INBOX, View.COMPOSE, View.SENT, View.ARCHIVED, View.DELETED};
    /** Bottom sidebar entries shown to every player; the Admin Center is appended for admins. */
    private static final View[] NAV_BOTTOM =
            {View.ANNOUNCEMENTS, View.ATTACHMENTS};

    private MailPages() {
    }

    /** The selectable areas of the Mail UI. */
    enum View {
        INBOX("Inbox"),
        COMPOSE("Compose"),
        SENT("Sent"),
        ARCHIVED("Archived"),
        DELETED("Deleted"),
        ANNOUNCEMENTS("Announcements"),
        ATTACHMENTS("Attachments");

        final String title;

        View(String title) {
            this.title = title;
        }

        boolean isFolder() {
            return this == INBOX || this == SENT || this == ARCHIVED || this == DELETED
                    || this == ANNOUNCEMENTS || this == ATTACHMENTS;
        }

        boolean isCompose() {
            return this == COMPOSE;
        }

        static View parse(String name) {
            try {
                return View.valueOf(name);
            } catch (RuntimeException e) {
                return INBOX;
            }
        }
    }

    /** In-progress compose state, preserved across the page reopens that refresh the picker/draft. */
    record ComposeDraft(String recipient, String subject, String body,
            List<ItemPick> picks, List<String> commands, String pickerSearch) {

        ComposeDraft {
            picks = picks == null ? List.of() : picks;
            commands = commands == null ? List.of() : commands;
        }

        static ComposeDraft empty() {
            return new ComposeDraft("", "", "", List.of(), List.of(), "");
        }
    }

    static final class MailPage extends MysticPage {
        private final MailModule mail;
        private final List<MailMessage> inbox;
        private final List<MailMessage> sent;
        private final List<ItemPick> pickerItems;
        private final View view;
        private final String selectedId;
        private final String search;
        private final int page;
        private final ComposeDraft draft;

        MailPage(MysticCore core, MailModule mail, PlayerRef player, List<MailMessage> inbox,
                List<MailMessage> sent, List<ItemPick> pickerItems, View view, String selectedId,
                String search, int page, ComposeDraft draft) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.mail = mail;
            this.inbox = inbox == null ? List.of() : inbox;
            this.sent = sent == null ? List.of() : sent;
            this.pickerItems = pickerItems == null ? List.of() : pickerItems;
            this.view = view;
            this.selectedId = selectedId;
            this.search = search == null ? "" : search;
            this.page = Math.max(0, page);
            this.draft = draft == null ? ComposeDraft.empty() : draft;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(MAIL_UI);

            long unread = inbox.stream().filter(m -> !m.isRead() && !m.isDeleted()).count();
            cmd.set("#HeaderInfo.Text", unread > 0 ? unread + " unread" : "");

            buildSidebar(cmd, event);

            boolean folder = view.isFolder();
            boolean compose = view.isCompose();

            cmd.set("#ListTitle.Text", compose ? "Online Players" : view.title);
            cmd.set("#SearchInput.Visible", folder);
            cmd.set("#SearchButton.Visible", folder);
            cmd.set("#MarkAllButton.Visible", view == View.INBOX || view == View.ANNOUNCEMENTS);
            cmd.set("#PrevButton.Visible", folder);
            cmd.set("#NextButton.Visible", folder);
            cmd.set("#PageLabel.Visible", folder);
            cmd.set("#PageInfo.Visible", folder);

            cmd.set("#ReadPane.Visible", folder);
            cmd.set("#ComposePane.Visible", compose);

            if (folder) {
                buildList(cmd, event);
                buildReadPane(cmd, event);
            } else {
                // Compose view: the middle column becomes a clickable list of online players
                // that auto-fills the "To" field (mirrors the TPA UI).
                buildOnlinePlayers(cmd, event);
                buildCompose(cmd, event);
            }
        }

        /** Fills the middle column with online players; clicking one sets the compose recipient. */
        private void buildOnlinePlayers(UICommandBuilder cmd, UIEventBuilder event) {
            List<PlayerRef> players = new ArrayList<>();
            for (PlayerRef ref : core.platform().onlinePlayers()) {
                if (!ref.getUuid().equals(player.getUuid())
                        && core.vanish().canSee(player.getUuid(), ref.getUuid())) {
                    players.add(ref);
                }
            }
            players.sort(java.util.Comparator.comparing(PlayerRef::getUsername, String.CASE_INSENSITIVE_ORDER));
            cmd.set("#ListEmpty.Visible", players.isEmpty());
            cmd.set("#ListEmpty.Text", players.isEmpty() ? "No other players online." : "");
            for (int i = 0; i < players.size(); i++) {
                String name = players.get(i).getUsername();
                String row = "#MailList[" + i + "]";
                cmd.append("#MailList", PLAYER_ROW_UI);
                cmd.set(row + " #Name.Text", name);
                event.addEventBinding(CustomUIEventBindingType.Activating, row,
                        new EventData().put("action", "pickrecipient").put("name", name)
                                .append("@body", "#BodyInput.Value"));
            }
        }

        // ----- Sidebar -------------------------------------------------------

        private void buildSidebar(UICommandBuilder cmd, UIEventBuilder event) {
            buildNav(cmd, event, "#NavTop", NAV_TOP);
            buildNav(cmd, event, "#NavBottom", NAV_BOTTOM);
        }

        private void buildNav(UICommandBuilder cmd, UIEventBuilder event, String container, View[] entries) {
            for (int i = 0; i < entries.length; i++) {
                View entry = entries[i];
                String row = container + "[" + i + "]";
                cmd.append(container, NAV_ROW_UI);
                cmd.set(row + " #Label.Text", navLabel(entry));
                cmd.set(row + " #Accent.Visible", isActive(entry));
                event.addEventBinding(CustomUIEventBindingType.Activating, row,
                        new EventData().put("action", "view").put("view", entry.name()));
            }
        }

        private boolean isActive(View entry) {
            return entry == view;
        }

        private String navLabel(View entry) {
            int count = switch (entry) {
                case INBOX -> (int) inbox.stream()
                        .filter(m -> !m.isAnnouncement() && !m.isArchived() && !m.isDeleted() && !m.isRead())
                        .count();
                case ANNOUNCEMENTS -> (int) inbox.stream()
                        .filter(m -> m.isAnnouncement() && !m.isArchived() && !m.isDeleted() && !m.isRead())
                        .count();
                case ATTACHMENTS -> (int) inbox.stream()
                        .filter(m -> !m.isDeleted() && m.isClaimable()).count();
                default -> 0;
            };
            return count > 0 ? entry.title + " (" + count + ")" : entry.title;
        }

        // ----- Folder list ---------------------------------------------------

        private List<MailMessage> folderMessages() {
            List<MailMessage> source = view == View.SENT ? sent : inbox;
            List<MailMessage> matched = new ArrayList<>();
            for (MailMessage message : source) {
                if (inFolder(message) && matchesSearch(search, senderLabel(message), message.getSubject(),
                        message.getBody(), message.getRecipientName())) {
                    matched.add(message);
                }
            }
            // Newest first (inboxes are stored oldest-first).
            java.util.Collections.reverse(matched);
            return matched;
        }

        private boolean inFolder(MailMessage m) {
            return switch (view) {
                case INBOX -> !m.isAnnouncement() && !m.isArchived() && !m.isDeleted();
                case ANNOUNCEMENTS -> m.isAnnouncement() && !m.isArchived() && !m.isDeleted();
                case ATTACHMENTS -> !m.isDeleted() && m.isClaimable();
                case ARCHIVED -> m.isArchived() && !m.isDeleted();
                case DELETED -> m.isDeleted();
                case SENT -> true;
                default -> false;
            };
        }

        private void buildList(UICommandBuilder cmd, UIEventBuilder event) {
            List<MailMessage> messages = folderMessages();
            int pageSize = mail.pageSize();
            int totalPages = Math.max(1, (messages.size() + pageSize - 1) / pageSize);
            int current = Math.min(page, totalPages - 1);
            int from = current * pageSize;
            int to = Math.min(from + pageSize, messages.size());

            cmd.set("#ListEmpty.Visible", messages.isEmpty());
            cmd.set("#ListEmpty.Text", emptyText());
            cmd.set("#PageLabel.Text", Integer.toString(current + 1));
            cmd.set("#PageInfo.Text", messages.isEmpty()
                    ? "0 of 0" : (from + 1) + "-" + to + " of " + messages.size());

            for (int i = from; i < to; i++) {
                MailMessage message = messages.get(i);
                String row = "#MailList[" + (i - from) + "]";
                cmd.append("#MailList", MAIL_ROW_UI);
                cmd.set(row + " #Unread.Text", message.isRead() ? "" : "●");
                cmd.set(row + " #From.Text", listName(message));
                cmd.set(row + " #Preview.Text", preview(message));
                cmd.set(row + " #Date.Text", shortDate(message.getSentDate()));
                boolean rewards = message.hasRewards();
                cmd.set(row + " #Reward.Text", rewards ? (message.isClaimed() ? "claimed" : "reward") : "");
                if (rewards && !message.items().isEmpty()) {
                    cmd.set(row + " #RewardIcon.Visible", true);
                    cmd.set(row + " #RewardIcon.ItemId", message.items().get(0).itemId);
                }
                event.addEventBinding(CustomUIEventBindingType.Activating, row,
                        new EventData().put("action", "select").put("id", message.getId()));
            }

            if (view.isFolder()) {
                // Explicit Search button (not ValueChanged) so typing doesn't rebuild the page
                // per keystroke and steal focus after each letter.
                cmd.set("#SearchInput.Value", search);
                event.addEventBinding(CustomUIEventBindingType.Activating, "#SearchButton",
                        new EventData().put("action", "search").append("@search", "#SearchInput.Value"));
                event.addEventBinding(CustomUIEventBindingType.Activating, "#PrevButton",
                        new EventData().put("action", "page").put("dir", "-1"));
                event.addEventBinding(CustomUIEventBindingType.Activating, "#NextButton",
                        new EventData().put("action", "page").put("dir", "1"));
                if (view == View.INBOX || view == View.ANNOUNCEMENTS) {
                    event.addEventBinding(CustomUIEventBindingType.Activating, "#MarkAllButton",
                            new EventData().put("action", "markall"));
                }
            }
        }

        private String emptyText() {
            return switch (view) {
                case SENT -> "You haven't sent any mail yet.";
                case ARCHIVED -> "No archived mail.";
                case DELETED -> "No deleted mail.";
                case ANNOUNCEMENTS -> "No announcements.";
                case ATTACHMENTS -> "No unclaimed rewards.";
                default -> "Your inbox is empty.";
            };
        }

        // ----- Reading pane --------------------------------------------------

        private MailMessage selected() {
            if (selectedId == null) {
                return null;
            }
            for (MailMessage message : folderMessages()) {
                if (message.getId().equals(selectedId)) {
                    return message;
                }
            }
            return null;
        }

        private void buildReadPane(UICommandBuilder cmd, UIEventBuilder event) {
            MailMessage message = selected();
            if (message == null) {
                cmd.set("#ReadSubject.Text", "Select a message");
                cmd.set("#ReadFrom.Text", "");
                cmd.set("#ReadDate.Text", "");
                cmd.set("#ReadBody.Text", "");
                cmd.set("#RewardsLabel.Visible", false);
                cmd.set("#ClaimButton.Visible", false);
                cmd.set("#ArchiveButton.Visible", false);
                cmd.set("#RestoreButton.Visible", false);
                cmd.set("#DeleteButton.Visible", false);
                return;
            }

            // Hytale 0.5.6 Custom UI Labels render PLAIN TEXT ONLY — setting a colour/format
            // Message tree on .Text disconnects the client ("couldn't set value"). So we strip
            // markup for display: the reader sees clean text, never raw &-codes, never a crash.
            cmd.set("#ReadSubject.Text", MysticText.stripMarkup(readTitle(message)));
            cmd.set("#ReadFrom.Text", view == View.SENT
                    ? "To: " + (message.getRecipientName() == null ? "?" : message.getRecipientName())
                    : "From: " + senderLabel(message));
            cmd.set("#ReadDate.Text", "Date: " + shortDate(message.getSentDate()));
            cmd.set("#ReadBody.Text", MysticText.stripMarkup(message.getBody() == null ? "" : message.getBody()));

            boolean rewards = message.hasRewards();
            cmd.set("#RewardsLabel.Visible", rewards);
            for (int i = 0; i < message.items().size(); i++) {
                MailAttachment attachment = message.items().get(i);
                String cell = "#RewardList[" + i + "]";
                cmd.append("#RewardList", REWARD_UI);
                cmd.set(cell + " #Slot.ItemId", attachment.itemId);
                cmd.set(cell + " #Qty.Text", "x" + Math.max(1, attachment.quantity));
            }

            boolean sentView = view == View.SENT;
            boolean claimable = !sentView && message.isClaimable();
            cmd.set("#ClaimButton.Visible", claimable);
            cmd.set("#ArchiveButton.Visible", !sentView && view != View.ARCHIVED && view != View.DELETED);
            cmd.set("#RestoreButton.Visible", view == View.ARCHIVED || view == View.DELETED);
            cmd.set("#RestoreButton.Text", view == View.DELETED ? "Restore" : "Unarchive");
            cmd.set("#DeleteButton.Visible", !sentView);
            cmd.set("#DeleteButton.Text", view == View.DELETED ? "Delete Forever" : "Delete");

            String id = message.getId();
            if (claimable) {
                event.addEventBinding(CustomUIEventBindingType.Activating, "#ClaimButton",
                        new EventData().put("action", "claim").put("id", id));
            }
            if (!sentView && view != View.ARCHIVED && view != View.DELETED) {
                event.addEventBinding(CustomUIEventBindingType.Activating, "#ArchiveButton",
                        new EventData().put("action", "archive").put("id", id));
            }
            if (view == View.ARCHIVED || view == View.DELETED) {
                event.addEventBinding(CustomUIEventBindingType.Activating, "#RestoreButton",
                        new EventData().put("action", "restore").put("id", id));
            }
            if (!sentView) {
                event.addEventBinding(CustomUIEventBindingType.Activating, "#DeleteButton",
                        new EventData().put("action", "delete").put("id", id));
            }
        }

        // ----- Compose pane --------------------------------------------------

        private void buildCompose(UICommandBuilder cmd, UIEventBuilder event) {
            boolean canAttach = mail.allowPlayerItemAttachments()
                    && player.hasPermission(Permissions.MAIL_ATTACH_ITEMS);

            cmd.set("#ComposeTitle.Text", "New Message");

            cmd.set("#ToLabel.Visible", true);
            cmd.set("#ToInput.Visible", true);
            cmd.set("#ToInput.Value", draft.recipient());

            // Subject/command fields belong to the admin center, not player compose.
            cmd.set("#SubjectLabel.Visible", false);
            cmd.set("#SubjectInput.Visible", false);
            cmd.set("#CommandLabel.Visible", false);
            cmd.set("#CommandInput.Visible", false);

            cmd.set("#BodyInput.Value", draft.body());

            cmd.set("#RewardHeader.Text", "Rewards (max " + mail.maxAttachments() + ")");
            cmd.set("#RewardHeader.Visible", canAttach);

            // Item picker: a scrollable dropdown of the player's own inventory items.
            boolean hasItems = canAttach && !pickerItems.isEmpty();
            cmd.set("#ItemDropdown.Visible", hasItems);
            cmd.set("#QtyInput.Visible", hasItems);
            cmd.set("#AddButton.Visible", hasItems);
            cmd.set("#ClearRewardsButton.Visible", canAttach);
            cmd.set("#NoItemsLabel.Visible", canAttach && pickerItems.isEmpty());
            cmd.set("#NoItemsLabel.Text", "No Items in Inventory");
            if (hasItems) {
                List<DropdownEntryInfo> entries = new ArrayList<>();
                for (ItemPick pick : pickerItems) {
                    entries.add(new DropdownEntryInfo(
                            LocalizableString.fromString(pick.itemId() + "  (x" + pick.quantity() + ")"),
                            pick.itemId()));
                }
                cmd.set("#ItemDropdown.Entries", entries);
                cmd.set("#ItemDropdown.Value", pickerItems.get(0).itemId());
                event.addEventBinding(CustomUIEventBindingType.Activating, "#AddButton",
                        composeFields(new EventData().put("action", "addreward")
                                .append("@item", "#ItemDropdown.Value").append("@qty", "#QtyInput.Value")));
            }

            // Chosen attachments.
            cmd.set("#DraftLabel.Text", "Attached (" + draft.picks().size() + ")");
            for (int i = 0; i < draft.picks().size(); i++) {
                ItemPick pick = draft.picks().get(i);
                String row = "#DraftList[" + i + "]";
                cmd.append("#DraftList", DRAFT_ROW_UI);
                cmd.set(row + " #Icon.ItemId", pick.itemId());
                cmd.set(row + " #Name.Text", pick.itemId());
                cmd.set(row + " #Qty.Text", "x" + pick.quantity());
                event.addEventBinding(CustomUIEventBindingType.Activating, row + " #Remove",
                        composeFields(new EventData().put("action", "removereward")
                                .put("index", Integer.toString(i))));
            }

            event.addEventBinding(CustomUIEventBindingType.Activating, "#ClearRewardsButton",
                    composeFields(new EventData().put("action", "clearrewards")));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#ComposeCancel",
                    new EventData().put("action", "view").put("view", View.INBOX.name()));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#ComposeSend",
                    composeFields(new EventData().put("action", "send")));
        }

        /** Appends the live compose field values so a refresh (or send) preserves what was typed. */
        private EventData composeFields(EventData data) {
            return data
                    .append("@to", "#ToInput.Value")
                    .append("@body", "#BodyInput.Value");
        }

        // ----- Events --------------------------------------------------------

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            switch (string(payload, "action")) {
                case "view" -> {
                    View next = View.parse(field(payload, "view"));
                    mail.openMailUi(player, next, null, "", 0, ComposeDraft.empty());
                }
                case "select" -> {
                    String id = field(payload, "id");
                    mail.markRead(player.getUuid(), id)
                            .thenRun(() -> reopenFolder(id));
                }
                case "search" -> mail.openMailUi(player, view, selectedId, field(payload, "search"), 0, draft);
                case "page" -> {
                    int dir = "1".equals(string(payload, "dir")) ? 1 : -1;
                    mail.openMailUi(player, view, selectedId, search, Math.max(0, page + dir), draft);
                }
                case "markall" -> mail.markAllRead(player.getUuid()).thenAccept(count -> {
                    if (count > 0) {
                        core.getMessageService().sendKey(player, "mail-marked-all-read",
                                java.util.Map.of("count", Integer.toString(count)));
                    }
                    reopenFolder(selectedId);
                });
                case "claim" -> mail.claimRewards(player, field(payload, "id"), () -> reopenFolder(field(payload, "id")));
                case "archive" -> mail.setArchived(player.getUuid(), field(payload, "id"), true).thenRun(() -> {
                    core.getMessageService().sendKey(player, "mail-archived");
                    reopenFolder(null);
                });
                case "restore" -> {
                    String id = field(payload, "id");
                    if (view == View.DELETED) {
                        mail.setDeleted(player.getUuid(), id, false).thenRun(() -> {
                            core.getMessageService().sendKey(player, "mail-restored");
                            reopenFolder(null);
                        });
                    } else {
                        mail.setArchived(player.getUuid(), id, false).thenRun(() -> {
                            core.getMessageService().sendKey(player, "mail-restored");
                            reopenFolder(null);
                        });
                    }
                }
                case "delete" -> {
                    String id = field(payload, "id");
                    if (view == View.DELETED) {
                        mail.delete(player.getUuid(), id).thenRun(() -> {
                            core.getMessageService().sendKey(player, "mail-deleted");
                            reopenFolder(null);
                        });
                    } else {
                        mail.setDeleted(player.getUuid(), id, true).thenRun(() -> {
                            core.getMessageService().sendKey(player, "mail-deleted");
                            reopenFolder(null);
                        });
                    }
                }
                case "addreward" -> {
                    List<ItemPick> picks = new ArrayList<>(draft.picks());
                    String itemId = field(payload, "item");
                    if (!itemId.isBlank() && picks.size() < mail.maxAttachments()) {
                        picks.add(new ItemPick(itemId, Math.max(1, parseInt(field(payload, "qty"), 1))));
                    }
                    reopenCompose(draftFrom(payload, picks));
                }
                case "removereward" -> {
                    List<ItemPick> picks = new ArrayList<>(draft.picks());
                    int index = parseInt(string(payload, "index"), -1);
                    if (index >= 0 && index < picks.size()) {
                        picks.remove(index);
                    }
                    reopenCompose(draftFrom(payload, picks));
                }
                case "clearrewards" -> reopenCompose(draftFrom(payload, List.of()));
                case "pickrecipient" -> reopenCompose(new ComposeDraft(field(payload, "name"), "",
                        field(payload, "body"), draft.picks(), List.of(), ""));
                case "send" -> {
                    ComposeDraft submitted = draftFrom(payload, draft.picks());
                    Runnable refresh = () -> mail.openMailUi(player, View.COMPOSE, null, "", 0, ComposeDraft.empty());
                    mail.sendComposedMail(player, submitted, refresh);
                }
                default -> {
                }
            }
        }

        private void reopenFolder(String selection) {
            mail.openMailUi(player, view, selection, search, page, draft);
        }

        private void reopenCompose(ComposeDraft next) {
            mail.openMailUi(player, view, null, "", 0, next);
        }

        /** Builds a compose draft from the submitted field values plus the given picks. */
        private ComposeDraft draftFrom(JsonObject payload, List<ItemPick> picks) {
            return new ComposeDraft(field(payload, "to"), field(payload, "subject"), field(payload, "body"),
                    picks, parseCommands(field(payload, "command")), field(payload, "pickerSearch"));
        }

        // ----- Rendering helpers ---------------------------------------------

        private String readTitle(MailMessage message) {
            if (message.isAnnouncement() && message.getSubject() != null && !message.getSubject().isBlank()) {
                return message.getSubject();
            }
            return "Mail from " + senderLabel(message);
        }

        private String listName(MailMessage message) {
            return view == View.SENT
                    ? "To " + (message.getRecipientName() == null ? "?" : message.getRecipientName())
                    : "From " + senderLabel(message);
        }

        private static String senderLabel(MailMessage message) {
            String name = message.getSenderName();
            return name == null || name.isBlank() ? "Server" : name;
        }

        private static String preview(MailMessage message) {
            String body = message.getBody();
            if (body == null) {
                return "";
            }
            // Strip markup so colour/format codes don't show as raw text in the compact preview.
            String plain = MysticText.stripMarkup(body);
            return plain.length() <= 40 ? plain : plain.substring(0, 40) + "…";
        }

        private static String shortDate(String isoDate) {
            try {
                return DATE_FORMAT.format(Instant.parse(isoDate));
            } catch (RuntimeException e) {
                return "";
            }
        }

        private static List<String> parseCommands(String raw) {
            if (raw == null || raw.isBlank()) {
                return List.of();
            }
            List<String> commands = new ArrayList<>();
            for (String part : raw.split("[;\\n]")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    commands.add(trimmed);
                }
            }
            return commands;
        }

        private static int parseInt(String raw, int fallback) {
            try {
                return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }
}
