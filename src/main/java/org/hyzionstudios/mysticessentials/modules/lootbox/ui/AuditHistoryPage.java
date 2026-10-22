package org.hyzionstudios.mysticessentials.modules.lootbox.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.lootbox.LootBoxModule;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.AuditQuery;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.AuditService;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.FormFields;
import org.hyzionstudios.mysticessentials.platform.ui.MysticPage;

import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The Audit History screen (P2-H): the LootBox audit trail, newest first, with
 * filters for the pool or target, the actor or player, the action and the time
 * window, paged ten at a time. Export writes every matching record to a JSON or
 * CSV file under the module's data folder and says where.
 *
 * <p>Read-only, and every press needs {@code audit}, checked again when it
 * arrives. Storage reads run off the world thread through
 * {@link AuditService#page}; the page reopens with the results. The filter
 * used is always the one in the boxes: paging after an edit starts again at
 * the newest page, and an export lists the same records afterwards.</p>
 */
public final class AuditHistoryPage extends MysticPage {

    static final String AUDIT_UI = "MysticEssentials/LootBoxAudit.ui";
    static final String ROW_UI = "MysticEssentials/LootBoxAuditRow.ui";
    static final int PAGE_SIZE = 10;
    private static final String READING = "&7Reading the audit trail...";

    /** Time window values, in the order the filter lists them. */
    static final List<String> WINDOWS = List.of("1h", "24h", "7d", "30d", "all");
    private static final Map<String, Long> WINDOW_MILLIS = Map.of("1h", TimeUnit.HOURS.toMillis(1),
            "24h", TimeUnit.DAYS.toMillis(1), "7d", TimeUnit.DAYS.toMillis(7), "30d", TimeUnit.DAYS.toMillis(30));

    private static final FormFields.Field TARGET = FormFields.Field.text("target", "#TargetInput", "Pool or target");
    private static final FormFields.Field ACTOR = FormFields.Field.text("actor", "#ActorInput", "Actor or player");
    private static final FormFields.Field ACTION = FormFields.Field.text("auditAction", "#ActionFilter", "Action");
    private static final FormFields.Field WINDOW = FormFields.Field.text("window", "#WindowFilter", "Time window");
    private static final FormFields INPUTS = FormFields.of(TARGET, ACTOR, ACTION, WINDOW);

    /** The filter boxes' values. */
    public record Filters(String target, String actor, String action, String window) {

        public static Filters initial() {
            return new Filters("", "", AuditQuery.ALL, "7d");
        }

        Filters with(Map<String, String> inputs) {
            String nextAction = inputs.getOrDefault(ACTION.key(), action);
            String nextWindow = inputs.getOrDefault(WINDOW.key(), window);
            return new Filters(inputs.getOrDefault(TARGET.key(), target).trim(),
                    inputs.getOrDefault(ACTOR.key(), actor).trim(),
                    actionValues().contains(nextAction) ? nextAction : AuditQuery.ALL,
                    WINDOWS.contains(nextWindow) ? nextWindow : "7d");
        }

        /** The query filter as of {@code now}; {@code actorIds} come from resolving the actor text. */
        AuditQuery.Filter toQuery(long now, Set<String> actorIds) {
            Long span = WINDOW_MILLIS.get(window);
            return new AuditQuery.Filter(target, actor, actorIds, action, span == null ? 0 : now - span,
                    Long.MAX_VALUE);
        }
    }

    /**
     * What the page shows.
     *
     * @param inputs   the filter as typed
     * @param applied  the filter {@code result} was read with, or {@code null} before the first read
     * @param page     zero-based page of {@code applied}
     * @param selected index into the result's records
     * @param names    player UUID → last known name, for the records shown
     */
    public record State(Filters inputs, Filters applied, int page, int selected, AuditQuery.Page result,
            Map<String, String> names, String notice) {

        public static State initial() {
            return new State(Filters.initial(), null, 0, 0, null, Map.of(), null);
        }

        State withInputs(Map<String, String> values) {
            return new State(inputs.with(values), applied, page, selected, result, names, null);
        }

        State withSelected(int index) {
            return new State(inputs, applied, page, index, result, names, notice);
        }

        State withNotice(String text) {
            return new State(inputs, applied, page, selected, result, names, text);
        }

        State withResult(Filters used, int pageIndex, AuditQuery.Page next, Map<String, String> resolved) {
            return new State(inputs, used, pageIndex, 0, next, Map.copyOf(resolved), notice);
        }
    }

    private final LootBoxModule module;
    private final State state;

    public AuditHistoryPage(MysticCore core, PlayerRef player, LootBoxModule module, State state) {
        super(core, player, CustomPageLifetime.CanDismiss);
        this.module = module;
        this.state = state == null ? State.initial() : state;
    }

    /** Opens the screen on its default filter and reads the first page. Call after checking {@code audit}. */
    static void open(MysticCore core, PlayerRef player, LootBoxModule module, Ref<EntityStore> ref,
            Store<EntityStore> store) {
        AuditHistoryPage page = new AuditHistoryPage(core, player, module, State.initial());
        page.load(ref, store, State.initial(), 0);
    }

    /**
     * {@code /lootbox audit}: the same, from any thread, for auditors who cannot
     * open the Manager. Call after checking {@code audit}.
     */
    public static void openFor(MysticCore core, PlayerRef player, LootBoxModule module) {
        AuditHistoryPage page = new AuditHistoryPage(core, player, module, State.initial());
        core.platform().openPage(player, page.page(State.initial().withNotice(READING)));
        page.fetch(State.initial(), 0);
    }

    // ----- Build --------------------------------------------------------------

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event, Store<EntityStore> store) {
        cmd.append(AUDIT_UI);
        AuditQuery.Page result = state.result();
        cmd.set("#HeaderInfo.TextSpans", uiText("#HeaderInfo.TextSpans", "Read-only · times in UTC · kept "
                + module.config().audit.retentionDays + " days"));

        Filters inputs = state.inputs();
        cmd.set("#TargetInput.Value", inputs.target());
        cmd.set("#ActorInput.Value", inputs.actor());
        cmd.set("#ActionFilter.Entries", actionEntries());
        cmd.set("#ActionFilter.Value", inputs.action());
        cmd.set("#WindowFilter.Entries", List.of(entry("Last hour", "1h"), entry("Last 24 hours", "24h"),
                entry("Last 7 days", "7d"), entry("Last 30 days", "30d"), entry("Everything kept", "all")));
        cmd.set("#WindowFilter.Value", inputs.window());
        event.addEventBinding(CustomUIEventBindingType.ValueChanged, "#ActionFilter", INPUTS.event("apply"));
        event.addEventBinding(CustomUIEventBindingType.ValueChanged, "#WindowFilter", INPUTS.event("apply"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#ApplyButton", INPUTS.event("apply"));

        boolean hasNotice = state.notice() != null && !state.notice().isBlank();
        cmd.set("#Notice.Visible", hasNotice);
        if (hasNotice) {
            cmd.set("#Notice.TextSpans", uiText("#Notice.TextSpans", state.notice()));
        }

        List<AuditQuery.Record> records = result == null ? List.of() : result.records();
        cmd.set("#ListEmpty.Visible", result != null && records.isEmpty());
        int selected = records.isEmpty() ? -1 : Math.max(0, Math.min(state.selected(), records.size() - 1));
        for (int i = 0; i < records.size(); i++) {
            AuditQuery.Record record = records.get(i);
            String row = "#RecordList[" + i + "]";
            cmd.append("#RecordList", ROW_UI);
            cmd.set(row + " #Selected.Visible", i == selected);
            cmd.set(row + " #AuditWhen.TextSpans", uiText(row + " #AuditWhen.TextSpans", record.time()));
            cmd.set(row + " #AuditAction.TextSpans", uiText(row + " #AuditAction.TextSpans", action(record.action())));
            cmd.set(row + " #AuditTarget.TextSpans", uiText(row + " #AuditTarget.TextSpans",
                    Message.raw(who(record.target(), false))));
            cmd.set(row + " #AuditDetail.TextSpans", uiText(row + " #AuditDetail.TextSpans",
                    Message.raw(record.detail().isBlank() ? "-" : record.detail())));
            cmd.set(row + " #AuditWho.TextSpans", uiText(row + " #AuditWho.TextSpans",
                    Message.raw(who(record.actor(), true))));
            event.addEventBinding(CustomUIEventBindingType.Activating, row,
                    INPUTS.event("select").put("index", Integer.toString(i)));
        }
        buildSelection(cmd, selected < 0 ? null : records.get(selected));

        cmd.set("#AuditPageInfo.TextSpans", uiText("#AuditPageInfo.TextSpans", pageInfo(result)));
        cmd.set("#NewerButton.Visible", result != null && state.page() > 0);
        cmd.set("#OlderButton.Visible", result != null && result.more());
        event.addEventBinding(CustomUIEventBindingType.Activating, "#NewerButton", INPUTS.event("newer"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#OlderButton", INPUTS.event("older"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#ExportJsonButton",
                INPUTS.event("export").put("format", "JSON"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#ExportCsvButton",
                INPUTS.event("export").put("format", "CSV"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton", new EventData().put("action", "back"));
    }

    private void buildSelection(UICommandBuilder cmd, AuditQuery.Record record) {
        cmd.set("#RecordPanel.Visible", record != null);
        if (record == null) {
            return;
        }
        // Recorded text is data, not markup: it goes in as raw messages so an & or < prints as itself.
        cmd.set("#AuditFullHead.TextSpans", uiText("#AuditFullHead.TextSpans", action(record.action()) + " &8· &f"
                + record.time() + " UTC &8· &7server " + safeId(record.server())));
        cmd.set("#AuditFullWho.TextSpans", uiText("#AuditFullWho.TextSpans",
                Message.raw("Actor " + full(record.actor()) + "  ·  target " + full(record.target()))));
        cmd.set("#AuditFullDetail.TextSpans", uiText("#AuditFullDetail.TextSpans",
                Message.raw(record.detail().isBlank() ? "No detail." : record.detail())));
    }

    // ----- Events -------------------------------------------------------------

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
        JsonObject payload = parse(data);
        String action = string(payload, "action");
        if ("back".equals(action)) {
            // Auditors may lack admin, the Manager's node; for them Back just closes.
            if (player.hasPermission(Permissions.LOOTBOX_ADMIN)) {
                reopen(ref, store, new LootBoxManagerPage(core, player, module, LootBoxManagerPage.State.initial()));
            } else {
                close(ref, store);
            }
            return;
        }
        // Read-only, but the trail names players and staff: every press needs audit.
        if (!allowed(Permissions.LOOTBOX_AUDIT)) {
            close(ref, store);
            return;
        }
        State next = state.withInputs(INPUTS.read(payload));
        boolean edited = !next.inputs().equals(state.applied());
        switch (action) {
            case "select" -> reopen(ref, store, page(next.withSelected(index(field(payload, "index")))));
            case "apply" -> load(ref, store, next, 0);
            case "newer" -> load(ref, store, next, edited ? 0 : Math.max(0, state.page() - 1));
            case "older" -> load(ref, store, next, edited ? 0 : state.page() + 1);
            case "export" -> export(ref, store, next, "CSV".equalsIgnoreCase(field(payload, "format"))
                    ? AuditService.ExportFormat.CSV : AuditService.ExportFormat.JSON);
            default -> reopen(ref, store, page(next));
        }
    }

    /** Reopens at once with a notice, then reads page {@code index} of the typed filter and reopens with it. */
    private void load(Ref<EntityStore> ref, Store<EntityStore> store, State next, int index) {
        reopen(ref, store, page(next.withNotice(READING)));
        fetch(next, index);
    }

    /** Reads page {@code index} of the typed filter off the world thread and reopens the page with it. */
    private void fetch(State next, int index) {
        Filters used = next.inputs();
        query(used, index).whenComplete((loaded, error) -> {
            if (error != null) {
                core.log(Level.WARNING, "[lootbox] reading the audit trail failed: " + error);
                openLater(next.withNotice("&cCould not read the audit trail; see the server log."));
                return;
            }
            openLater(next.withResult(used, index, loaded.page(), loaded.names())
                    .withNotice(truncationNote(loaded.page())));
        });
    }

    private record Loaded(AuditQuery.Page page, Map<String, String> names) {
    }

    /** Resolves the actor text to a UUID when it names a known player, reads the page, then looks up names. */
    private CompletableFuture<Loaded> query(Filters filters, int index) {
        return actorIds(filters.actor())
                .thenCompose(ids -> module.audit().page(filters.toQuery(System.currentTimeMillis(), ids),
                        index * PAGE_SIZE, PAGE_SIZE))
                .thenCompose(result -> names(result).thenApply(names -> new Loaded(result, names)));
    }

    private void export(Ref<EntityStore> ref, Store<EntityStore> store, State next, AuditService.ExportFormat format) {
        Filters used = next.inputs();
        reopen(ref, store, page(next.withNotice("&7Exporting the matching records as " + format + "...")));
        actorIds(used.actor())
                .thenCompose(ids -> module.audit().export(used.toQuery(System.currentTimeMillis(), ids), format,
                        player.getUsername(), player.hasPermission(Permissions.LOOTBOX_AUDIT_IDENTIFIERS)))
                .whenComplete((export, error) -> {
                    if (error != null) {
                        core.log(Level.WARNING, "[lootbox] audit export failed: " + error);
                        openLater(next.withNotice("&cThe export failed; see the server log. Nothing was written."));
                        return;
                    }
                    String path = export.file().toAbsolutePath().toString();
                    core.getMessageService().sendKey(player, "lootbox-audit-exported", Map.of(
                            "count", Integer.toString(export.written().records()), "path", path));
                    String note = "&aExported &f" + export.written().records() + " &arecord(s) to &fexports/"
                            + export.file().getFileName() + "&a; the full path is in chat."
                            + (export.written().capped() ? " &eStopped at the limit of "
                                    + AuditService.EXPORT_MAX_RECORDS + "; narrow the filter for the rest." : "")
                            + (export.written().truncated() ? " &eOnly the newest "
                                    + AuditService.EXPORT_MAX_BUCKETS + " hourly buckets were read." : "");
                    // List the same records the file holds, from the newest page.
                    query(used, 0).whenComplete((loaded, failure) -> openLater(failure != null
                            ? next.withNotice(note)
                            : next.withResult(used, 0, loaded.page(), loaded.names()).withNotice(note)));
                });
    }

    // ----- Helpers ------------------------------------------------------------

    /** The UUID the actor text names, when it is a player's name or a UUID; empty otherwise. */
    private CompletableFuture<Set<String>> actorIds(String actor) {
        if (actor == null || actor.isBlank()) {
            return CompletableFuture.completedFuture(Set.of());
        }
        try {
            return CompletableFuture.completedFuture(Set.of(UUID.fromString(actor.trim()).toString()));
        } catch (IllegalArgumentException notUuid) {
            // A name: resolve it below.
        }
        return core.getPlayerProfileService().resolveUuid(actor.trim())
                .thenApply(found -> found.map(uuid -> Set.of(uuid.toString())).orElse(Set.<String>of()))
                .exceptionally(error -> Set.of());
    }

    /** Last known names of the players in a page's actor and target columns. Bounded by the page size. */
    private CompletableFuture<Map<String, String>> names(AuditQuery.Page page) {
        Set<String> ids = new LinkedHashSet<>();
        for (AuditQuery.Record record : page.records()) {
            ids.add(record.actor());
            ids.add(record.target());
        }
        Map<String, CompletableFuture<Optional<String>>> lookups = new HashMap<>();
        for (String id : ids) {
            UUID uuid = uuid(id);
            if (uuid != null) {
                lookups.put(id, core.getPlayerProfileService().lastKnownName(uuid)
                        .exceptionally(error -> Optional.empty()));
            }
        }
        return CompletableFuture.allOf(lookups.values().toArray(CompletableFuture[]::new)).thenApply(done -> {
            Map<String, String> names = new HashMap<>();
            lookups.forEach((id, lookup) -> lookup.join().ifPresent(name -> names.put(id, name)));
            return names;
        });
    }

    private static UUID uuid(String text) {
        if (text == null || text.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A player id as their name when known; short ids in the list, full ones in the detail. */
    private String who(String id, boolean list) {
        String name = state.names().get(id);
        if (name != null) {
            return name;
        }
        return list && uuid(id) != null ? id.substring(0, 8) + "…" : blank(id, "-");
    }

    private String full(String id) {
        String name = state.names().get(id);
        return name != null ? name + " (" + id + ")" : blank(id, "-");
    }

    /** The action in its group's colour; an action outside the known list is shown without markup. */
    private static String action(String action) {
        String group = AuditQuery.GROUPS.entrySet().stream().filter(e -> e.getValue().contains(action))
                .map(Map.Entry::getKey).findFirst().orElse("");
        String colour = switch (group) {
            case "OPENINGS" -> "&6";
            case "DELIVERY" -> action.contains("REVIEW") || action.contains("REFUSED") ? "&c" : "&a";
            case "KEYS" -> "&b";
            case "PUBLISHING" -> "&d";
            case "CRATES" -> "&e";
            default -> "&7";
        };
        return colour + blank(action, "?").replaceAll("[^A-Z0-9_]", "");
    }

    private String pageInfo(AuditQuery.Page result) {
        if (result == null) {
            return "Reading...";
        }
        if (result.records().isEmpty()) {
            return state.page() > 0 ? "Page " + (state.page() + 1) + " is empty" : "No records";
        }
        int first = result.offset() + 1;
        return "Page " + (state.page() + 1) + " · records " + first + "-" + (first + result.records().size() - 1)
                + (result.more() ? " · older ones exist" : " · the oldest match") + " · " + windowLabel();
    }

    private String windowLabel() {
        Filters applied = state.applied() == null ? state.inputs() : state.applied();
        return switch (applied.window()) {
            case "1h" -> "last hour";
            case "24h" -> "last 24 hours";
            case "30d" -> "last 30 days";
            case "all" -> "everything kept";
            default -> "last 7 days";
        };
    }

    private static String truncationNote(AuditQuery.Page page) {
        return page.truncated() ? "&eOnly the newest " + AuditService.PAGE_MAX_BUCKETS + " hourly buckets were read;"
                + " narrow the time window or the filter to see older records." : null;
    }

    private static List<DropdownEntryInfo> actionEntries() {
        List<DropdownEntryInfo> entries = new ArrayList<>();
        entries.add(entry("All actions", AuditQuery.ALL));
        AuditQuery.GROUPS.forEach((group, actions) -> entries.add(entry(groupLabel(group), AuditQuery.GROUP + group)));
        AuditQuery.GROUPS.values().forEach(actions -> actions.forEach(action -> entries.add(entry(action, action))));
        return entries;
    }

    private static List<String> actionValues() {
        List<String> values = new ArrayList<>();
        values.add(AuditQuery.ALL);
        AuditQuery.GROUPS.keySet().forEach(group -> values.add(AuditQuery.GROUP + group));
        AuditQuery.GROUPS.values().forEach(values::addAll);
        return values;
    }

    private static String groupLabel(String group) {
        return "All " + group.charAt(0) + group.substring(1).toLowerCase(Locale.ROOT);
    }

    /** A server id as {@code AuditService} writes it ({@code [A-Za-z0-9_-]}); anything else is dropped. */
    private static String safeId(String server) {
        String safe = server == null ? "" : server.replaceAll("[^A-Za-z0-9_-]", "");
        return safe.isEmpty() ? "?" : safe;
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int index(String raw) {
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static DropdownEntryInfo entry(String label, String value) {
        return new DropdownEntryInfo(LocalizableString.fromString(label), value);
    }

    private AuditHistoryPage page(State next) {
        return new AuditHistoryPage(core, player, module, next);
    }

    /** Reopens the page from a background completion; {@code openPage} hops to the player's world thread. */
    private void openLater(State next) {
        core.platform().openPage(player, page(next));
    }

    /** Re-checked on every press; the button being visible is not permission. */
    private boolean allowed(String node) {
        if (player.hasPermission(node)) {
            return true;
        }
        core.getMessageService().sendKey(player, "no-permission");
        return false;
    }
}
