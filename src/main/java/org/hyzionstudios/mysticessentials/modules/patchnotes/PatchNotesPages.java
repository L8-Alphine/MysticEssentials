package org.hyzionstudios.mysticessentials.modules.patchnotes;

import java.util.ArrayList;
import java.util.List;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.patchnotes.PatchNotesConfig.Category;
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
 * The Patch Notes UI ({@code /patchnotes}): a two-pane changelog. The left column
 * is a fixed search box over an independently scrolling patch list (pinned first,
 * unread dot, selected highlight); the right column shows the selected patch's
 * title/version/date/author, a row of category filter buttons, and the
 * independently scrolling, colour-coded body rendered from the Markdown subset
 * ({@link PatchMarkup}). A footer offers "Mark All As Read" and "Close".
 *
 * <p>Data (the player's read state) is loaded before the page opens (storage is
 * async, page building is not); the page is re-opened to refresh after every
 * action (search, filter, select, mark-all).</p>
 */
final class PatchNotesPages {

    static final String PAGE_UI = "MysticEssentials/PatchNotes.ui";
    static final String NOTE_ROW_UI = "MysticEssentials/PatchNoteRow.ui";
    static final String FILTER_BTN_UI = "MysticEssentials/PatchFilterButton.ui";
    static final String LINE_HEADER_UI = "MysticEssentials/PatchLineHeader.ui";
    static final String LINE_TEXT_UI = "MysticEssentials/PatchLineText.ui";
    static final String LINE_ADD_UI = "MysticEssentials/PatchLineAdd.ui";
    static final String LINE_REMOVE_UI = "MysticEssentials/PatchLineRemove.ui";
    static final String LINE_SPACER_UI = "MysticEssentials/PatchLineSpacer.ui";

    static final String FILTER_ALL = "all";

    private PatchNotesPages() {
    }

    static final class PatchNotesPage extends MysticPage {
        private final PatchNotesModule module;
        private final PatchReadState state;
        private final String selectedId;
        private final String search;
        private final String filter;

        PatchNotesPage(MysticCore core, PatchNotesModule module, PlayerRef player, PatchReadState state,
                String selectedId, String search, String filter) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.module = module;
            this.state = state;
            this.selectedId = selectedId;
            this.search = search == null ? "" : search;
            this.filter = filter == null || filter.isBlank() ? FILTER_ALL : filter;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(PAGE_UI);

            int unread = unreadCount();
            cmd.set("#HeaderInfo.Text", unread > 0 ? unread + " unread" : "");

            buildList(cmd, event);
            buildViewer(cmd, event);
            buildFooter(cmd, event);
        }

        private int unreadCount() {
            int unread = 0;
            for (PatchNote note : module.allNotes()) {
                if (!state.isRead(note.safeId())) {
                    unread++;
                }
            }
            return unread;
        }

        // ----- Left: patch list ---------------------------------------------

        private void buildList(UICommandBuilder cmd, UIEventBuilder event) {
            List<PatchNote> notes = module.sortedNotes(search);

            cmd.set("#SearchInput.Value", search);
            cmd.set("#ListEmpty.Visible", notes.isEmpty());
            cmd.set("#ListEmpty.Text", search.isBlank() ? "No patch notes yet." : "No matching patch notes.");

            for (int i = 0; i < notes.size(); i++) {
                PatchNote note = notes.get(i);
                String row = "#NoteList[" + i + "]";
                cmd.append("#NoteList", NOTE_ROW_UI);
                cmd.set(row + " #Title.Text", note.title == null ? note.safeId() : note.title);
                cmd.set(row + " #Meta.Text", metaShort(note));
                cmd.set(row + " #Unread.Text", state.isRead(note.safeId()) ? "" : "●");
                cmd.set(row + " #Pinned.Visible", note.pinned);
                cmd.set(row + " #Accent.Visible", note.safeId().equals(selectedId));
                event.addEventBinding(CustomUIEventBindingType.Activating, row,
                        new EventData().put("action", "select").put("id", note.safeId()));
            }

            cmd.set("#SearchInput.Value", search);
            event.addEventBinding(CustomUIEventBindingType.Activating, "#SearchButton",
                    new EventData().put("action", "search").append("@search", "#SearchInput.Value"));
        }

        private static String metaShort(PatchNote note) {
            StringBuilder meta = new StringBuilder();
            if (note.version != null && !note.version.isBlank()) {
                meta.append('v').append(note.version);
            }
            if (note.date != null && !note.date.isBlank()) {
                if (meta.length() > 0) {
                    meta.append("  •  ");
                }
                meta.append(note.date);
            }
            return meta.toString();
        }

        // ----- Right: viewer -------------------------------------------------

        private PatchNote selected() {
            PatchNote note = module.noteById(selectedId);
            if (note != null) {
                return note;
            }
            List<PatchNote> notes = module.sortedNotes(search);
            return notes.isEmpty() ? null : notes.get(0);
        }

        private void buildViewer(UICommandBuilder cmd, UIEventBuilder event) {
            PatchNote note = selected();
            if (note == null) {
                cmd.set("#PatchTitle.Text", "Patch Notes");
                cmd.set("#PatchMeta.Text", "");
                cmd.set("#PatchSummary.Text", "There are no patch notes to show yet.");
                cmd.set("#PatchSummary.Visible", true);
                cmd.set("#FilterBar.Visible", false);
                cmd.set("#ContentEmpty.Visible", false);
                return;
            }

            cmd.set("#PatchTitle.Text", note.title == null ? note.safeId() : note.title);
            cmd.set("#PatchMeta.Text", metaLong(note));
            boolean hasSummary = note.summary != null && !note.summary.isBlank();
            cmd.set("#PatchSummary.Visible", hasSummary);
            cmd.set("#PatchSummary.Text", hasSummary ? PatchMarkup.stripInline(note.summary) : "");

            buildFilterBar(cmd, event, note);
            buildContent(cmd, note);
        }

        private static String metaLong(PatchNote note) {
            List<String> parts = new ArrayList<>();
            if (note.version != null && !note.version.isBlank()) {
                parts.add("v" + note.version);
            }
            if (note.date != null && !note.date.isBlank()) {
                parts.add(note.date);
            }
            if (note.author != null && !note.author.isBlank()) {
                parts.add("by " + note.author);
            }
            return String.join("    •    ", parts);
        }

        private void buildFilterBar(UICommandBuilder cmd, UIEventBuilder event, PatchNote note) {
            cmd.set("#FilterBar.Visible", true);
            List<Category> categories = module.config().categories;
            List<String> ids = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            ids.add(FILTER_ALL);
            labels.add("All");
            if (categories != null) {
                for (Category category : categories) {
                    if (category != null && category.id != null && !category.id.isBlank()) {
                        ids.add(category.id);
                        labels.add(category.displayName == null || category.displayName.isBlank()
                                ? category.id : category.displayName);
                    }
                }
            }
            for (int i = 0; i < ids.size(); i++) {
                String id = ids.get(i);
                String btn = "#FilterBar[" + i + "]";
                cmd.append("#FilterBar", FILTER_BTN_UI);
                cmd.set(btn + " #Label.Text", labels.get(i));
                cmd.set(btn + " #Active.Visible", id.equalsIgnoreCase(filter));
                event.addEventBinding(CustomUIEventBindingType.Activating, btn,
                        new EventData().put("action", "filter").put("filter", id));
            }
        }

        private void buildContent(UICommandBuilder cmd, PatchNote note) {
            List<PatchMarkup.Line> lines = new ArrayList<>();
            if (note.hasSections()) {
                boolean first = true;
                for (PatchNote.Section section : note.sections) {
                    if (section == null || !sectionMatchesFilter(section)) {
                        continue;
                    }
                    if (!first) {
                        lines.add(new PatchMarkup.Line(PatchMarkup.Type.BLANK, ""));
                    }
                    first = false;
                    lines.addAll(PatchMarkup.renderSection(section.title, section.body, section.type));
                }
            }

            cmd.set("#ContentEmpty.Visible", lines.isEmpty());
            cmd.set("#ContentEmpty.Text", FILTER_ALL.equalsIgnoreCase(filter)
                    ? "This patch has no content." : "Nothing in this category.");

            int index = 0;
            for (PatchMarkup.Line line : lines) {
                String template = templateFor(line.type());
                String row = "#SectionContent[" + index + "]";
                cmd.append("#SectionContent", template);
                if (line.type() != PatchMarkup.Type.BLANK) {
                    cmd.set(row + " #Line.Text", line.text());
                }
                index++;
            }
        }

        private boolean sectionMatchesFilter(PatchNote.Section section) {
            if (FILTER_ALL.equalsIgnoreCase(filter)) {
                return true;
            }
            return section.type != null && section.type.equalsIgnoreCase(filter);
        }

        private static String templateFor(PatchMarkup.Type type) {
            return switch (type) {
                case HEADER -> LINE_HEADER_UI;
                case ADD -> LINE_ADD_UI;
                case REMOVE -> LINE_REMOVE_UI;
                case BLANK -> LINE_SPACER_UI;
                default -> LINE_TEXT_UI;
            };
        }

        // ----- Footer --------------------------------------------------------

        private void buildFooter(UICommandBuilder cmd, UIEventBuilder event) {
            event.addEventBinding(CustomUIEventBindingType.Activating, "#MarkAllButton",
                    new EventData().put("action", "markall"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                    new EventData().put("action", "close"));
        }

        // ----- Events --------------------------------------------------------

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            switch (string(payload, "action")) {
                case "select" -> module.openUi(player, field(payload, "id"), search, filter);
                case "search" -> module.openUi(player, null, field(payload, "search"), filter);
                case "filter" -> {
                    String next = field(payload, "filter");
                    module.openUi(player, selectedId, search, next.isBlank() ? FILTER_ALL : next);
                }
                case "markall" -> module.markAllRead(player.getUuid()).thenAccept(count -> {
                    if (count > 0) {
                        core.getMessageService().sendKey(player, "patchnotes-marked-read");
                    }
                    module.openUi(player, selectedId, search, filter);
                });
                case "close" -> close(ref, store);
                default -> {
                }
            }
        }
    }
}
