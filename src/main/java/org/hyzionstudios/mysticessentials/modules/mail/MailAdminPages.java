package org.hyzionstudios.mysticessentials.modules.mail;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.mail.MailModule.Audience;
import org.hyzionstudios.mysticessentials.modules.mail.MailModule.ItemPick;
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
 * The standalone mail admin center ({@code /mailadmin}): a dedicated admin UI for
 * composing and broadcasting mail announcements. The left column is the composer
 * — subject, body, an {@link Audience} selector (online / all known / permission
 * node / a single player), reward commands, and an item-reward picker over the
 * whole item registry — and the right column is the sent-announcement history
 * with per-entry re-send. State is carried across the page reopens that refresh
 * the picker via an {@link AdminDraft}.
 */
final class MailAdminPages {

    static final String ADMIN_UI = "MysticEssentials/MailAdmin.ui";
    static final String HISTORY_ROW_UI = "MysticEssentials/MailHistoryRow.ui";
    static final String PICKER_ROW_UI = "MysticEssentials/MailPickerRow.ui";
    static final String DRAFT_ROW_UI = "MysticEssentials/MailDraftRow.ui";

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault());

    private MailAdminPages() {
    }

    /** In-progress admin-compose state, preserved across the reopens that refresh the picker. */
    record AdminDraft(String audience, String param, String subject, String body,
            List<ItemPick> picks, List<String> commands, String pickerSearch) {

        AdminDraft {
            picks = picks == null ? List.of() : picks;
            commands = commands == null ? List.of() : commands;
        }

        static AdminDraft empty() {
            return new AdminDraft(Audience.ONLINE.name(), "", "", "", List.of(), List.of(), "");
        }

        Audience audienceEnum() {
            return Audience.parse(audience);
        }
    }

    static final class MailAdminPage extends MysticPage {
        private final MailModule mail;
        private final List<SentAnnouncement> log;
        private final List<ItemPick> pickerItems;
        private final AdminDraft draft;

        MailAdminPage(MysticCore core, MailModule mail, PlayerRef player, List<SentAnnouncement> log,
                List<ItemPick> pickerItems, AdminDraft draft) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.mail = mail;
            this.log = log == null ? List.of() : log;
            this.pickerItems = pickerItems == null ? List.of() : pickerItems;
            this.draft = draft == null ? AdminDraft.empty() : draft;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(ADMIN_UI);

            Audience audience = draft.audienceEnum();
            int online = core.platform().onlinePlayers().size();
            cmd.set("#HeaderInfo.Text", online + " online");

            cmd.set("#SubjectInput.Value", draft.subject());
            cmd.set("#BodyInput.Value", draft.body());

            cmd.set("#AudienceLabel.Text", "Delivering to: " + audience.label
                    + (audience.needsParam() && !draft.param().isBlank() ? " (" + draft.param() + ")" : ""));
            cmd.set("#ParamInput.Visible", audience.needsParam());
            cmd.set("#ParamInput.Value", draft.param());

            boolean commandsEnabled = mail.allowAnnouncementCommands();
            cmd.set("#CommandLabel.Visible", commandsEnabled);
            cmd.set("#CommandInput.Visible", commandsEnabled);
            cmd.set("#CommandInput.Value", String.join("; ", draft.commands()));

            cmd.set("#RewardHeader.Text", "Item rewards (max " + mail.maxAttachments() + ")");
            cmd.set("#PickerSearch.Value", draft.pickerSearch());

            buildPicker(cmd, event);
            buildDraftList(cmd, event);
            buildHistory(cmd, event);

            // Audience selector.
            bindAudience(event, "#AudOnline", Audience.ONLINE);
            bindAudience(event, "#AudKnown", Audience.KNOWN);
            bindAudience(event, "#AudPermission", Audience.PERMISSION);
            bindAudience(event, "#AudPlayer", Audience.PLAYER);

            // Explicit Search button (not ValueChanged) so typing in the registry search
            // doesn't rebuild the page per keystroke and steal focus after each letter.
            event.addEventBinding(CustomUIEventBindingType.Activating, "#SearchButton",
                    fields(new EventData().put("action", "search")));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#ClearRewardsButton",
                    fields(new EventData().put("action", "clearrewards")));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#SendButton",
                    fields(new EventData().put("action", "send")));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#CancelButton",
                    new EventData().put("action", "close"));
        }

        private void buildPicker(UICommandBuilder cmd, UIEventBuilder event) {
            // Registry items (search-filtered) as a scrollable clickable list; clicking a row
            // attaches that item (quantity from the Qty field). Empty results → a hint label.
            cmd.set("#NoItemsLabel.Visible", pickerItems.isEmpty());
            for (int i = 0; i < pickerItems.size(); i++) {
                ItemPick pick = pickerItems.get(i);
                String row = "#PickerList[" + i + "]";
                cmd.append("#PickerList", PICKER_ROW_UI);
                cmd.set(row + " #Icon.ItemId", pick.itemId());
                cmd.set(row + " #Name.Text", pick.itemId());
                cmd.set(row + " #Have.Text", "");
                event.addEventBinding(CustomUIEventBindingType.Activating, row,
                        fields(new EventData().put("action", "addreward")
                                .put("item", pick.itemId()).append("@qty", "#QtyInput.Value")));
            }
        }

        private void buildDraftList(UICommandBuilder cmd, UIEventBuilder event) {
            cmd.set("#DraftLabel.Text", "Attached (" + draft.picks().size() + ")");
            for (int i = 0; i < draft.picks().size(); i++) {
                ItemPick pick = draft.picks().get(i);
                String row = "#DraftList[" + i + "]";
                cmd.append("#DraftList", DRAFT_ROW_UI);
                cmd.set(row + " #Icon.ItemId", pick.itemId());
                cmd.set(row + " #Name.Text", pick.itemId());
                cmd.set(row + " #Qty.Text", "x" + pick.quantity());
                event.addEventBinding(CustomUIEventBindingType.Activating, row + " #Remove",
                        fields(new EventData().put("action", "removereward").put("index", Integer.toString(i))));
            }
        }

        private void buildHistory(UICommandBuilder cmd, UIEventBuilder event) {
            cmd.set("#HistoryEmpty.Visible", log.isEmpty());
            // Newest first (the log is appended oldest-first).
            for (int i = 0; i < log.size(); i++) {
                SentAnnouncement entry = log.get(log.size() - 1 - i);
                String row = "#HistoryList[" + i + "]";
                cmd.append("#HistoryList", HISTORY_ROW_UI);
                cmd.set(row + " #Subject.Text", historyTitle(entry));
                cmd.set(row + " #Meta.Text", historyMeta(entry));
                cmd.set(row + " #Preview.Text", preview(entry.body));
                event.addEventBinding(CustomUIEventBindingType.Activating, row + " #Resend",
                        new EventData().put("action", "resend").put("id", entry.id == null ? "" : entry.id));
            }
        }

        private void bindAudience(UIEventBuilder event, String button, Audience audience) {
            event.addEventBinding(CustomUIEventBindingType.Activating, button,
                    fields(new EventData().put("action", "audience").put("audience", audience.name())));
        }

        /** Appends the live composer field values so a refresh (or send) preserves what was typed. */
        private EventData fields(EventData data) {
            return data
                    .append("@subject", "#SubjectInput.Value")
                    .append("@body", "#BodyInput.Value")
                    .append("@param", "#ParamInput.Value")
                    .append("@command", "#CommandInput.Value")
                    .append("@pickerSearch", "#PickerSearch.Value");
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            switch (string(payload, "action")) {
                case "audience" -> reopen(draftFrom(payload, field(payload, "audience"), draft.picks()));
                case "search" -> reopen(draftFrom(payload, draft.audience(), draft.picks()));
                case "clearrewards" -> reopen(draftFrom(payload, draft.audience(), List.of()));
                case "addreward" -> {
                    List<ItemPick> picks = new ArrayList<>(draft.picks());
                    String itemId = field(payload, "item");
                    if (!itemId.isBlank() && picks.size() < mail.maxAttachments()) {
                        picks.add(new ItemPick(itemId, Math.max(1, parseInt(field(payload, "qty"), 1))));
                    }
                    reopen(draftFrom(payload, draft.audience(), picks));
                }
                case "removereward" -> {
                    List<ItemPick> picks = new ArrayList<>(draft.picks());
                    int index = parseInt(string(payload, "index"), -1);
                    if (index >= 0 && index < picks.size()) {
                        picks.remove(index);
                    }
                    reopen(draftFrom(payload, draft.audience(), picks));
                }
                case "send" -> {
                    AdminDraft submitted = draftFrom(payload, draft.audience(), draft.picks());
                    Runnable refresh = () -> mail.openMailAdminUi(player, AdminDraft.empty());
                    mail.sendAdminBroadcast(player, submitted.audienceEnum(), submitted.param(),
                            submitted.subject(), submitted.body(), submitted.picks(),
                            submitted.commands(), refresh);
                }
                case "resend" -> mail.resendAnnouncement(player, field(payload, "id"),
                        () -> mail.openMailAdminUi(player, AdminDraft.empty()));
                case "close" -> close(ref, store);
                default -> {
                }
            }
        }

        private void reopen(AdminDraft next) {
            mail.openMailAdminUi(player, next);
        }

        private AdminDraft draftFrom(JsonObject payload, String audience, List<ItemPick> picks) {
            return new AdminDraft(audience, field(payload, "param"), field(payload, "subject"),
                    field(payload, "body"), picks, parseCommands(field(payload, "command")),
                    field(payload, "pickerSearch"));
        }

        private static String historyTitle(SentAnnouncement entry) {
            return entry.subject == null || entry.subject.isBlank()
                    ? "(no subject)" : entry.subject;
        }

        private String historyMeta(SentAnnouncement entry) {
            Audience audience = Audience.parse(entry.audience);
            String who = audience.label + (audience.needsParam() && entry.param != null && !entry.param.isBlank()
                    ? " (" + entry.param + ")" : "");
            String rewards = entry.items().isEmpty() && entry.commands().isEmpty()
                    ? "" : " • " + entry.items().size() + " items, " + entry.commands().size() + " cmds";
            return who + " • " + entry.recipientCount + " sent" + rewards + " • " + shortDate(entry.date);
        }

        private static String preview(String body) {
            if (body == null) {
                return "";
            }
            String plain = org.hyzionstudios.mysticessentials.core.message.MysticText.stripMarkup(body);
            return plain.length() <= 48 ? plain : plain.substring(0, 48) + "…";
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
