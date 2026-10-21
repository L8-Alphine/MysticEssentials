package org.hyzionstudios.mysticessentials.modules.lootbox.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Pattern;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.message.MysticText;
import org.hyzionstudios.mysticessentials.modules.lootbox.LootBoxModule;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.GrantSource;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyBatch;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyLog;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeySerial;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.KeyService;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.ConfirmActionPage;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.FormFields;
import org.hyzionstudios.mysticessentials.platform.ui.MysticPage;

import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Grants for one published key (P2-F2), reached from the Key Manager's Grants
 * button or {@code /lootbox key grants <keyId>}: give it to a typed player or
 * to picked online players, look a serial up and revoke it, and the key's
 * recent grants, refusals, revocations and conversions.
 *
 * <p>Giving goes through {@link KeyService#grant} unchanged, so the count cap,
 * the policy profile (a PAID source is refused and the attempt audited), the
 * offline rule and the inbox overflow behave as {@code /lootbox key give}. More
 * than one key or more than one player asks first. Viewing needs {@code keys},
 * giving {@code keys.give} and revoking {@code keys.revoke}; every press checks
 * again. Handlers reopen synchronously; storage work reopens with a notice.</p>
 */
public final class KeyGrantsPage extends MysticPage {

    static final String GRANTS_UI = "MysticEssentials/LootBoxKeyGrants.ui";
    static final String PLAYER_ROW_UI = "MysticEssentials/LootBoxEditorToggleRow.ui";
    static final String LINE_UI = "MysticEssentials/LootBoxConfirmActionLine.ui";
    static final int PLAYERS_SHOWN = 60;
    static final int HISTORY_SHOWN = 40;
    /** Players named in a confirmation before "and n more". */
    static final int CONFIRM_NAMES = 8;

    private static final FormFields.Field PLAYER = FormFields.Field.text("player", "#PlayerInput", "Player name");
    private static final FormFields.Field COUNT = FormFields.Field.text("count", "#CountInput", "Count");
    private static final FormFields.Field SOURCE = FormFields.Field.text("source", "#SourceInput", "Source");
    private static final FormFields.Field SERIAL = FormFields.Field.text("serial", "#SerialInput", "Serial");
    private static final FormFields INPUTS = FormFields.of(PLAYER, COUNT, SOURCE, SERIAL);
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,32}");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    /**
     * What the page shows. {@code picked} maps the chosen players' UUIDs to their
     * names; {@code history} is {@code null} while it loads.
     */
    public record State(String keyId, Map<String, String> picked, String name, String count, boolean virtual,
            String source, String serial, Lookup lookup, List<KeyLog.Entry> history, String notice) {

        public static State of(String keyId) {
            return new State(keyId, Map.of(), "", "1", false, GrantSource.ADMIN_GRANT.name(), "", null, null, null);
        }

        State withPicked(Map<String, String> next) {
            return new State(keyId, Collections.unmodifiableMap(new LinkedHashMap<>(next)), name, count, virtual, source,
                    serial, lookup, history, notice);
        }

        State withVirtual(boolean next) {
            return new State(keyId, picked, name, count, next, source, serial, lookup, history, notice);
        }

        State withLookup(Lookup next) {
            return new State(keyId, picked, name, count, virtual, source, serial, next, history, notice);
        }

        State withHistory(List<KeyLog.Entry> next) {
            return new State(keyId, picked, name, count, virtual, source, serial, lookup, next, notice);
        }

        State withNotice(String text) {
            return new State(keyId, picked, name, count, virtual, source, serial, lookup, history, text);
        }

        /** After a successful give: nobody stays picked, so a second press does not give again. */
        State cleared() {
            return new State(keyId, Map.of(), "", count, virtual, source, serial, lookup, history, notice);
        }

        State withInputs(Map<String, String> inputs) {
            String nextSource = inputs.getOrDefault(SOURCE.key(), source);
            return new State(keyId, picked, inputs.getOrDefault(PLAYER.key(), name),
                    inputs.getOrDefault(COUNT.key(), count), virtual,
                    GrantSource.parse(nextSource, null) == null ? source : nextSource,
                    inputs.getOrDefault(SERIAL.key(), serial), lookup, history, null);
        }
    }

    /**
     * A looked-up serial, or a batch of stackable keys (P2-K) when the id starts
     * with {@code batch-}; {@code found} / {@code batch} are {@code null} when no
     * ledger entry has that id.
     */
    public record Lookup(String serial, KeySerial found, KeyBatch batch, String ownerName) {

        boolean missing() {
            return found == null && batch == null;
        }

        String keyId() {
            return found != null ? found.keyId : batch != null ? batch.keyId : null;
        }

        String owner() {
            return found != null ? found.owner : batch != null ? batch.owner : null;
        }
    }

    /** One player a grant goes to. */
    private record Target(UUID uuid, String name) {
    }

    private record Outcome(Target target, KeyService.GrantResult result, Throwable error) {
    }

    private final LootBoxModule module;
    private final State state;

    public KeyGrantsPage(MysticCore core, PlayerRef player, LootBoxModule module, State state) {
        super(core, player, CustomPageLifetime.CanDismiss);
        this.module = module;
        this.state = state;
    }

    /**
     * Opens the page for {@code keyId} from a handler (synchronously, with the
     * history loading) and again once the history has loaded.
     */
    static void open(MysticCore core, PlayerRef player, LootBoxModule module, Ref<EntityStore> ref,
            Store<EntityStore> store, String keyId) {
        KeyGrantsPage page = new KeyGrantsPage(core, player, module, State.of(keyId));
        reopen(ref, store, page);
        page.refreshLater(page.state);
    }

    /** Opens the page for {@code keyId} from any thread, such as a command. */
    public static void openLater(MysticCore core, PlayerRef player, LootBoxModule module, String keyId) {
        KeyGrantsPage page = new KeyGrantsPage(core, player, module, State.of(keyId));
        page.refreshLater(page.state);
    }

    // ----- Build --------------------------------------------------------------

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event, Store<EntityStore> store) {
        cmd.append(GRANTS_UI);
        KeyDraft.Published live = module.registry().key(state.keyId()).orElse(null);
        cmd.set("#HeaderTitle.TextSpans", uiText("#HeaderTitle.TextSpans", "Grants: " + keyName(live)));
        cmd.set("#HeaderInfo.TextSpans", uiText("#HeaderInfo.TextSpans", state.keyId()
                + (live == null ? " · not published" : " · v" + live.version + " · " + usesText(live))));
        boolean hasNotice = state.notice() != null && !state.notice().isBlank();
        cmd.set("#Notice.Visible", hasNotice);
        if (hasNotice) {
            cmd.set("#Notice.TextSpans", uiText("#Notice.TextSpans", state.notice()));
        }
        buildGive(cmd, event, live);
        buildLookup(cmd, event);
        buildHistory(cmd);
        event.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton", INPUTS.event("back"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#RefreshButton", INPUTS.event("refresh"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", new EventData().put("action", "close"));
    }

    private void buildGive(UICommandBuilder cmd, UIEventBuilder event, KeyDraft.Published live) {
        FormFields.fill(cmd, PLAYER, null, state.name());
        FormFields.fill(cmd, COUNT, null, state.count());
        List<DropdownEntryInfo> sources = new ArrayList<>();
        for (GrantSource source : GrantSource.values()) {
            sources.add(new DropdownEntryInfo(LocalizableString.fromString(sourceLabel(source)), source.name()));
        }
        cmd.set("#SourceInput.Entries", sources);
        FormFields.fill(cmd, SOURCE, null, state.source());
        event.addEventBinding(CustomUIEventBindingType.ValueChanged, "#SourceInput", INPUTS.event("source"));

        List<PlayerRef> online = new ArrayList<>(core.platform().onlinePlayers());
        online.sort(Comparator.comparing(p -> p.getUsername().toLowerCase(Locale.ROOT)));
        Map<String, String> rows = new LinkedHashMap<>(state.picked());
        for (PlayerRef ref : online) {
            if (rows.size() >= PLAYERS_SHOWN + state.picked().size()) {
                break;
            }
            rows.putIfAbsent(ref.getUuid().toString(), ref.getUsername());
        }
        cmd.set("#PlayersEmpty.Visible", rows.isEmpty());
        int i = 0;
        for (Map.Entry<String, String> row : rows.entrySet()) {
            String sel = "#PlayerList[" + i++ + "]";
            boolean picked = state.picked().containsKey(row.getKey());
            boolean isOnline = online.stream().anyMatch(p -> p.getUuid().toString().equals(row.getKey()));
            cmd.append("#PlayerList", PLAYER_ROW_UI);
            cmd.set(sel + " #Selected.Visible", picked);
            cmd.set(sel + " #Name.TextSpans", uiText(sel + " #Name.TextSpans", row.getValue()));
            cmd.set(sel + " #Meta.TextSpans", uiText(sel + " #Meta.TextSpans", isOnline ? "Online now"
                    : "&eLeft the server; " + offlineRule()));
            cmd.set(sel + " #State.TextSpans", uiText(sel + " #State.TextSpans", picked ? "&aPicked" : "&8Pick"));
            event.addEventBinding(CustomUIEventBindingType.Activating, sel,
                    INPUTS.event("pick").put("uuid", row.getKey()));
        }
        cmd.set("#PlayersNote.TextSpans", uiText("#PlayersNote.TextSpans", online.size() > PLAYERS_SHOWN
                ? "Showing " + PLAYERS_SHOWN + " of " + online.size() + " online. Type a name for anyone else."
                : state.picked().isEmpty() ? "Pick as many as you like; each gets the count below."
                : state.picked().size() + " picked. Each gets the count below."));

        cmd.set("#VirtualOnButton.Visible", !state.virtual());
        cmd.set("#VirtualOffButton.Visible", state.virtual());
        cmd.set("#KindHint.TextSpans", uiText("#KindHint.TextSpans", state.virtual()
                ? "Adds to their virtual balance; there is no item to carry."
                : "A signed item with its own serial. What does not fit goes to the pending inbox; offline players get "
                        + offlineRule() + "."));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#VirtualOnButton", INPUTS.event("virtualOn"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#VirtualOffButton", INPUTS.event("virtualOff"));

        boolean canGive = player.hasPermission(Permissions.LOOTBOX_KEYS_GIVE);
        cmd.set("#GiveButton.Visible", canGive && live != null);
        cmd.set("#GiveSummary.TextSpans", uiText("#GiveSummary.TextSpans", giveSummary(live, canGive)));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#GiveButton", INPUTS.event("give"));
    }

    private String giveSummary(KeyDraft.Published live, boolean canGive) {
        if (live == null) {
            return "&cThis key is not published, so it cannot be given out.";
        }
        if (!canGive) {
            return "&8Giving keys needs " + Permissions.LOOTBOX_KEYS_GIVE + ".";
        }
        GrantSource source = GrantSource.parse(state.source(), GrantSource.ADMIN_GRANT);
        String policy = module.keys().policyProblem(source);
        if (policy != null) {
            return "&cRefused here: " + policy + ".";
        }
        int max = maxCount();
        FormFields.Result<Integer> count = FormFields.intInRange(COUNT, state.count(), 1, max);
        int players = state.picked().size() + (state.name().isBlank() ? 0 : 1);
        if (!count.ok()) {
            return "&e" + count.problem().message();
        }
        if (players == 0) {
            return "Type a name or pick players. Up to " + max + " each.";
        }
        return "Gives " + count.value() + " " + (state.virtual() ? "virtual" : "key item") + (count.value() == 1 ? "" : "s")
                + " to " + players + (players == 1 ? " player" : " players") + " · " + sourceLabel(source);
    }

    private void buildLookup(UICommandBuilder cmd, UIEventBuilder event) {
        FormFields.fill(cmd, SERIAL, null, state.serial());
        event.addEventBinding(CustomUIEventBindingType.Activating, "#LookupButton", INPUTS.event("lookup"));
        List<String> lines = new ArrayList<>();
        Lookup lookup = state.lookup();
        boolean revocable = false;
        if (lookup == null) {
            lines.add("&8Type a serial (or a batch-… id) and press Look Up to see its state.");
        } else if (lookup.missing()) {
            lines.add("&cNo key has the serial &f" + lookup.serial() + "&c.");
        } else if (lookup.batch() != null) {
            KeyBatch batch = lookup.batch();
            boolean thisKey = state.keyId().equals(batch.keyId);
            lines.add("&7Batch &f" + batch.batch + (thisKey ? "" : " &e(a " + batch.keyId + " batch, not this one)"));
            lines.add("&7" + (batch.revoked ? "&cRevoked" : "&fActive") + " &8· &f" + batch.remaining + " &7of &f"
                    + batch.issued + " &7left" + (batch.reservations.isEmpty() ? "" : " &8· &7"
                            + batch.reservations.size() + " in use now")
                    + (batch.expiresAt > 0 ? " &8· &7expires &f" + WHEN.format(Instant.ofEpochMilli(batch.expiresAt)) : ""));
            lines.add("&7Issued to &f" + who(batch.owner, lookup.ownerName()) + " &8· &7source &f" + batch.source
                    + " &8· &7by &f" + batch.issuedBy + " &7on &f" + WHEN.format(Instant.ofEpochMilli(batch.issuedAt)));
            revocable = thisKey && revocable(batch);
            if (thisKey && !revocable) {
                lines.add("&8Nothing left to revoke.");
            }
        } else {
            KeySerial serial = lookup.found();
            boolean thisKey = state.keyId().equals(serial.keyId);
            lines.add("&7Serial &f" + serial.serial + (thisKey ? "" : " &e(a " + serial.keyId + " key, not this one)"));
            lines.add("&7State &f" + serial.state + " &8· &7uses left &f" + serial.usesLeft
                    + (serial.expiresAt > 0 ? " &8· &7expires &f" + WHEN.format(Instant.ofEpochMilli(serial.expiresAt)) : ""));
            lines.add("&7Owner &f" + who(serial.owner, lookup.ownerName()) + " &8· &7source &f" + serial.source);
            lines.add("&7Issued by &f" + serial.issuedBy + " &7on &f" + WHEN.format(Instant.ofEpochMilli(serial.issuedAt))
                    + (serial.redeemedBy == null ? "" : " &8· &7last opening &f" + shortId(serial.redeemedBy)));
            revocable = thisKey && revocable(serial);
            if (thisKey && !revocable) {
                lines.add("&8It is " + serial.state.toLowerCase(Locale.ROOT) + ", so there is nothing to revoke.");
            }
        }
        boolean canRevoke = player.hasPermission(Permissions.LOOTBOX_KEYS_REVOKE);
        if (revocable && !canRevoke) {
            lines.add("&8Revoking needs " + Permissions.LOOTBOX_KEYS_REVOKE + ".");
        }
        lines(cmd, "#SerialLines", lines);
        cmd.set("#RevokeRow.Visible", revocable && canRevoke);
        event.addEventBinding(CustomUIEventBindingType.Activating, "#RevokeButton", INPUTS.event("revoke"));
    }

    private void buildHistory(UICommandBuilder cmd) {
        List<KeyLog.Entry> history = state.history();
        List<String> lines = new ArrayList<>();
        if (history == null) {
            lines.add("&8Loading...");
        } else {
            history.forEach(entry -> lines.add(describe(entry)));
        }
        cmd.set("#HistoryEmpty.Visible", history != null && history.isEmpty());
        lines(cmd, "#HistoryList", lines);
    }

    /** One history line. */
    static String describe(KeyLog.Entry entry) {
        String when = "&8" + WHEN.format(Instant.ofEpochMilli(entry.at)) + " ";
        String owner = who(entry.owner, entry.ownerName);
        String kind = entry.virtual ? "virtual" : "item";
        return when + switch (entry.action == null ? "" : entry.action) {
            case KeyLog.GRANT -> "&f" + entry.actor + " &7gave &f" + entry.count + "x " + kind + " &7to &f" + owner
                    + " &8· " + entry.source + (entry.pending > 0 ? " · " + entry.pending + " to inbox" : "");
            case KeyLog.REFUSED -> "&c" + entry.actor + "'s grant of " + entry.count + " to " + owner + " was refused: "
                    + entry.detail;
            case KeyLog.REVOKE -> "&f" + entry.actor + " &7revoked &f" + shortId(entry.serial) + " &7(" + owner + "'s)"
                    + (entry.count > 1 ? " &8· " + entry.count + " unspent" : "");
            case KeyLog.DEPOSIT -> "&f" + owner + " &7deposited &f" + entry.count + " &7key item" + (entry.count == 1 ? "" : "s")
                    + " into their balance" + (entry.serial == null ? "" : " &8· " + shortId(entry.serial));
            case KeyLog.WITHDRAW -> "&f" + owner + " &7withdrew &f" + entry.count + " &7key item"
                    + (entry.count == 1 ? "" : "s") + " from their balance";
            default -> "&7" + entry.action + " " + owner;
        };
    }

    // ----- Events -------------------------------------------------------------

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
        JsonObject payload = parse(data);
        String action = string(payload, "action");
        if ("close".equals(action)) {
            close(ref, store);
            return;
        }
        if (!player.hasPermission(Permissions.LOOTBOX_KEYS)) {
            core.getMessageService().sendKey(player, "no-permission");
            close(ref, store);
            return;
        }
        State next = state.withInputs(INPUTS.read(payload));
        switch (action) {
            case "pick" -> reopen(ref, store, page(next.withPicked(toggle(next.picked(), field(payload, "uuid")))));
            case "virtualOn", "virtualOff" -> reopen(ref, store, page(next.withVirtual("virtualOn".equals(action))));
            case "give" -> give(ref, store, next);
            case "lookup" -> lookup(ref, store, next);
            case "revoke" -> confirmRevoke(ref, store, next);
            case "refresh" -> {
                reopen(ref, store, page(next.withNotice("&7Refreshing...")));
                refreshLater(next);
            }
            case "back" -> reopen(ref, store, new KeyManagerPage(core, player, module,
                    KeyManagerPage.State.selecting(state.keyId(), null)));
            default -> reopen(ref, store, page(next));
        }
    }

    private Map<String, String> toggle(Map<String, String> picked, String uuid) {
        Map<String, String> next = new LinkedHashMap<>(picked);
        if (next.remove(uuid) == null) {
            try {
                core.platform().findPlayer(UUID.fromString(uuid)).ifPresent(online ->
                        next.put(uuid, online.getUsername()));
            } catch (IllegalArgumentException ignored) {
                // Not a UUID: nothing to pick.
            }
        }
        return next;
    }

    // ----- Give ---------------------------------------------------------------

    private void give(Ref<EntityStore> ref, Store<EntityStore> store, State next) {
        if (!allowed(Permissions.LOOTBOX_KEYS_GIVE)) {
            reopen(ref, store, page(next));
            return;
        }
        if (module.registry().key(next.keyId()).isEmpty()) {
            reopen(ref, store, page(next.withNotice("&c" + next.keyId() + " is not published; publish it first.")));
            return;
        }
        FormFields.Result<Integer> count = FormFields.intInRange(COUNT, next.count(), 1, maxCount());
        if (!count.ok()) {
            reopen(ref, store, page(next.withNotice("&c" + count.problem().message())));
            return;
        }
        String typed = next.name().trim();
        if (typed.isEmpty() && next.picked().isEmpty()) {
            reopen(ref, store, page(next.withNotice("&cType a player name or pick online players first.")));
            return;
        }
        if (!typed.isEmpty() && !NAME.matcher(typed).matches()) {
            reopen(ref, store, page(next.withNotice("&c'" + typed + "' is not a player name.")));
            return;
        }
        List<Target> targets = new ArrayList<>();
        next.picked().forEach((uuid, name) -> targets.add(new Target(UUID.fromString(uuid), name)));
        Optional<PlayerRef> online = typed.isEmpty() ? Optional.empty() : core.platform().findPlayerByName(typed);
        online.ifPresent(found -> addTarget(targets, new Target(found.getUuid(), found.getUsername())));
        if (typed.isEmpty() || online.isPresent()) {
            prepare(next, targets, count.value(), page -> reopen(ref, store, page));
            return;
        }
        reopen(ref, store, page(next.withNotice("&7Looking up &f" + typed + "&7...")));
        core.getPlayerProfileService().resolveUuid(typed).whenComplete((resolved, error) -> {
            if (error != null || resolved == null || resolved.isEmpty()) {
                openLater(next.withNotice("&cNo player called &f" + typed + " &chas joined this server."));
                return;
            }
            addTarget(targets, new Target(resolved.get(), typed));
            prepare(next, targets, count.value(), page -> core.platform().openPage(player, page));
        });
    }

    private static void addTarget(List<Target> targets, Target target) {
        if (targets.stream().noneMatch(t -> t.uuid().equals(target.uuid()))) {
            targets.add(target);
        }
    }

    /** Asks first for more than one key or player; a refused source skips the question and is recorded as refused. */
    private void prepare(State next, List<Target> targets, int count, Consumer<CustomUIPage> open) {
        GrantSource source = GrantSource.parse(next.source(), GrantSource.ADMIN_GRANT);
        boolean refused = module.keys().policyProblem(source) != null;
        if (refused || (count <= 1 && targets.size() <= 1)) {
            open.accept(page(next.withNotice("&7Giving...")));
            runGrants(next, targets, count, source);
            return;
        }
        KeyDraft.Published live = module.registry().key(next.keyId()).orElse(null);
        List<String> lines = new ArrayList<>();
        lines.add("&7Key &f" + keyName(live) + " &8(" + next.keyId() + (live == null ? "" : " v" + live.version) + ")");
        lines.add("&7Gives &f" + count + " " + (next.virtual() ? "virtual key" : "key item") + (count == 1 ? "" : "s")
                + " &7to each of " + targets.size() + (targets.size() == 1 ? " player:" : " players:"));
        for (int i = 0; i < Math.min(CONFIRM_NAMES, targets.size()); i++) {
            lines.add("&8  • &f" + targets.get(i).name());
        }
        if (targets.size() > CONFIRM_NAMES) {
            lines.add("&8  … and " + (targets.size() - CONFIRM_NAMES) + " more");
        }
        lines.add("&7Source &f" + sourceLabel(source) + " &8· &7" + count * targets.size() + " keys in all.");
        long offline = targets.stream().filter(t -> core.platform().findPlayer(t.uuid()).isEmpty()).count();
        if (!next.virtual() && offline > 0) {
            lines.add("&e" + offline + " of them " + (offline == 1 ? "is" : "are") + " offline and get " + offlineRule() + ".");
        }
        lines.add("&7Every grant is audited and listed in this key's history.");
        ConfirmActionPage.Spec spec = new ConfirmActionPage.Spec("Give " + count * targets.size() + " keys?", lines,
                "Give Keys", false, Permissions.LOOTBOX_KEYS_GIVE);
        open.accept(new ConfirmActionPage(core, player, spec, (r, s) -> {
            reopen(r, s, page(next.withNotice("&7Giving...")));
            runGrants(next, targets, count, source);
        }, (r, s) -> reopen(r, s, page(next))));
    }

    private void runGrants(State next, List<Target> targets, int count, GrantSource source) {
        String actor = player.getUsername();
        List<CompletableFuture<Outcome>> each = new ArrayList<>();
        for (Target target : targets) {
            each.add(module.keys().grant(target.uuid(), target.name(), next.keyId(), count, next.virtual(), source, actor)
                    .handle((result, error) -> new Outcome(target, result, error)));
        }
        CompletableFuture.allOf(each.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) -> {
            List<String> given = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            int pending = 0;
            for (CompletableFuture<Outcome> future : each) {
                Outcome outcome = future.join();
                if (outcome.error() != null || outcome.result() == null || !outcome.result().ok()) {
                    String why = outcome.error() != null ? "see the server log"
                            : outcome.result() == null ? "no answer" : outcome.result().problem();
                    if (outcome.error() != null) {
                        core.log(Level.WARNING, "[lootbox] granting " + next.keyId() + " to " + outcome.target().name()
                                + " failed: " + outcome.error());
                    }
                    failed.add(outcome.target().name() + " (" + why + ")");
                    continue;
                }
                given.add(outcome.target().name());
                pending += outcome.result().pending();
                core.platform().findPlayer(outcome.target().uuid()).ifPresent(online -> core.getMessageService()
                        .sendKey(online, "lootbox-key-received", Map.of("count", Integer.toString(count),
                                "key", next.keyId())));
            }
            StringBuilder notice = new StringBuilder();
            if (!given.isEmpty()) {
                notice.append("&aGave &f").append(count).append("x &ato &f").append(String.join(", ", given)).append("&a.");
                if (pending > 0) {
                    notice.append(" &7").append(pending).append(" went to pending inboxes.");
                }
            }
            if (!failed.isEmpty()) {
                notice.append(notice.isEmpty() ? "" : " ").append("&cNot given to ").append(String.join(", ", failed))
                        .append(".");
            }
            refreshLater((given.isEmpty() ? next : next.cleared()).withNotice(notice.toString()));
        });
    }

    // ----- Look up and revoke -------------------------------------------------

    private void lookup(Ref<EntityStore> ref, Store<EntityStore> store, State next) {
        String serial = next.serial().trim();
        if (serial.isEmpty()) {
            reopen(ref, store, page(next.withLookup(null).withNotice("&cType a serial first.")));
            return;
        }
        reopen(ref, store, page(next.withNotice("&7Looking up &f" + serial + "&7...")));
        find(serial).whenComplete((found, error) -> {
            if (error != null) {
                core.log(Level.WARNING, "[lootbox] looking up serial " + serial + " failed: " + error);
                openLater(next.withNotice("&cThe lookup failed; see the server log."));
                return;
            }
            openLater(next.withLookup(found));
        });
    }

    /** Loads {@code serial} (or batch) and its owner's name. */
    private CompletableFuture<Lookup> find(String serial) {
        CompletableFuture<Lookup> loaded = KeyBatch.isBatch(serial)
                ? module.keys().batch(serial).thenApply(batch -> new Lookup(serial, null, batch, null))
                : module.keys().serial(serial).thenApply(found -> new Lookup(serial, found, null, null));
        return loaded.thenCompose(lookup -> {
            UUID owner = uuid(lookup.owner());
            if (owner == null) {
                return CompletableFuture.completedFuture(lookup);
            }
            Optional<PlayerRef> online = core.platform().findPlayer(owner);
            if (online.isPresent()) {
                return CompletableFuture.completedFuture(new Lookup(serial, lookup.found(), lookup.batch(),
                        online.get().getUsername()));
            }
            return core.getPlayerProfileService().lastKnownName(owner).handle((name, error) -> new Lookup(serial,
                    lookup.found(), lookup.batch(), name == null ? null : name.orElse(null)));
        });
    }

    private void confirmRevoke(Ref<EntityStore> ref, Store<EntityStore> store, State next) {
        if (!allowed(Permissions.LOOTBOX_KEYS_REVOKE)) {
            reopen(ref, store, page(next));
            return;
        }
        Lookup lookup = next.lookup();
        boolean spendable = lookup != null && !lookup.missing() && next.keyId().equals(lookup.keyId())
                && (lookup.batch() != null ? revocable(lookup.batch()) : revocable(lookup.found()));
        if (!spendable) {
            reopen(ref, store, page(next.withNotice("&cLook up a spendable serial of this key first.")));
            return;
        }
        String id = lookup.serial();
        List<String> lines = new ArrayList<>();
        if (lookup.batch() != null) {
            KeyBatch batch = lookup.batch();
            lines.add("&7Batch &f" + batch.batch + " &8· &f" + batch.remaining + " &7of &f" + batch.issued + " &7left");
            lines.add("&7Issued to &f" + who(batch.owner, lookup.ownerName()));
            lines.add("&7Every item of this batch stops opening anything, wherever it is now. This cannot be undone.");
            if (!batch.reservations.isEmpty()) {
                lines.add("&eSome of it is held by an opening in progress, which will then need staff review.");
            }
        } else {
            KeySerial serial = lookup.found();
            lines.add("&7Serial &f" + serial.serial + " &8· &7" + serial.state.toLowerCase(Locale.ROOT) + ", "
                    + serial.usesLeft + (serial.usesLeft == 1 ? " use" : " uses") + " left");
            lines.add("&7Owner &f" + who(serial.owner, lookup.ownerName()));
            lines.add("&7The key item stops opening anything, and so does any copy of it. This cannot be undone.");
            if (KeySerial.RESERVED.equals(serial.state)) {
                lines.add("&eIt is reserved by an opening in progress, which will then need staff review.");
            }
        }
        lines.add("&7Items stay in inventories until someone next tries to use them; then they are removed.");
        ConfirmActionPage.Spec spec = new ConfirmActionPage.Spec("Revoke this key?", lines, "Revoke", true,
                Permissions.LOOTBOX_KEYS_REVOKE);
        reopen(ref, store, new ConfirmActionPage(core, player, spec, (r, s) -> {
            reopen(r, s, page(next.withNotice("&7Revoking...")));
            module.keys().revoke(id, player.getUsername()).whenComplete((revoked, error) -> {
                if (error != null) {
                    core.log(Level.WARNING, "[lootbox] revoking " + id + " failed: " + error);
                }
                refreshLater(next.withNotice(error != null ? "&cRevoking failed; see the server log."
                        : Boolean.TRUE.equals(revoked) ? "&aRevoked &f" + id + "&a."
                        : "&eNothing revoked: it was already spent or revoked."));
            });
        }, (r, s) -> reopen(r, s, page(next))));
    }

    /** Whether {@link KeyService#revoke} would change this serial. */
    static boolean revocable(KeySerial serial) {
        return serial != null && (KeySerial.ISSUED.equals(serial.state) || KeySerial.RESERVED.equals(serial.state));
    }

    /** Whether {@link KeyService#revoke} would change this batch. */
    static boolean revocable(KeyBatch batch) {
        return batch != null && !batch.revoked && batch.remaining > 0;
    }

    // ----- Helpers ------------------------------------------------------------

    /** Reloads the history (and the looked-up serial) off the world thread, then opens {@code next}. */
    private void refreshLater(State next) {
        CompletableFuture<KeyLog> history = module.keys().history(next.keyId());
        CompletableFuture<Lookup> lookup = next.lookup() == null ? CompletableFuture.completedFuture(null)
                : find(next.lookup().serial());
        history.thenCombine(lookup, (log, found) -> next.withHistory(log.newest(HISTORY_SHOWN)).withLookup(found))
                .whenComplete((loaded, error) -> {
                    if (error != null) {
                        core.log(Level.WARNING, "[lootbox] loading the history of " + next.keyId() + " failed: " + error);
                        openLater(next.withHistory(List.of()).withNotice("&cThe history could not be loaded; see the "
                                + "server log."));
                        return;
                    }
                    openLater(loaded);
                });
    }

    private int maxCount() {
        return Math.max(1, module.config().keys.maxGrantPerCommand);
    }

    private String offlineRule() {
        return "VIRTUAL_CREDIT".equalsIgnoreCase(module.config().keys.offlineGrant) ? "virtual credit instead"
                : "them in their pending inbox";
    }

    private String keyName(KeyDraft.Published live) {
        return live == null || live.spec.name == null || live.spec.name.isBlank() ? state.keyId()
                : MysticText.stripMarkup(live.spec.name);
    }

    private static String usesText(KeyDraft.Published live) {
        return live.spec.uses + (live.spec.uses == 1 ? " use" : " uses");
    }

    static String sourceLabel(GrantSource source) {
        return switch (source) {
            case ADMIN_GRANT -> "Staff grant";
            case GAMEPLAY -> "Gameplay";
            case QUEST -> "Quest";
            case API -> "Another plugin";
            case PAID -> "Paid";
        };
    }

    private static String who(String owner, String name) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return owner == null ? "unknown" : owner.substring(0, Math.min(8, owner.length())) + "…";
    }

    private static String shortId(String id) {
        return id == null ? "?" : id.length() <= 12 ? id : id.substring(0, 12) + "…";
    }

    private static UUID uuid(String raw) {
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void lines(UICommandBuilder cmd, String list, List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String row = list + "[" + i + "]";
            cmd.append(list, LINE_UI);
            cmd.set(row + " #Line.TextSpans", uiText(row + " #Line.TextSpans", lines.get(i)));
        }
    }

    private KeyGrantsPage page(State next) {
        return new KeyGrantsPage(core, player, module, next);
    }

    private void openLater(State next) {
        core.platform().openPage(player, page(next));
    }

    private boolean allowed(String node) {
        if (player.hasPermission(node)) {
            return true;
        }
        core.getMessageService().sendKey(player, "no-permission");
        return false;
    }
}
