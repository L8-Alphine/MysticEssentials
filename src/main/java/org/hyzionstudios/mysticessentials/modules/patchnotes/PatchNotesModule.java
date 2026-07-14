package org.hyzionstudios.mysticessentials.modules.patchnotes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.api.service.StorageService;
import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Patch Notes: a searchable, categorised changelog surfaced through a two-column
 * Custom UI (scrollable patch list on the left, filtered content on the right).
 * Patches are authored as JSON-with-Markdown-body files under
 * {@code modules/patchnotes/patches/}; per-player read state lives in the {@code
 * patchnotes} storage namespace so unread indicators and the join notification
 * work across any storage provider. Enabled by default.
 */
public final class PatchNotesModule extends AbstractMysticModule {

    static final String READ_NAMESPACE = "patchnotes";

    private PatchNotesConfig config = new PatchNotesConfig();
    private PatchNoteFileLoader loader;
    private List<PatchNote> notes = new ArrayList<>();

    public PatchNotesModule() {
        super("patchnotes", "Patch Notes", "1.0.0");
    }

    @Override
    public void onEnable() {
        config = core.configManager().loadModuleConfig(id(), PatchNotesConfig.class, new PatchNotesConfig());
        loader = new PatchNoteFileLoader(core, id());
        notes = loader.load(config.generateExamples);
        log("Loaded " + notes.size() + " patch note(s).");

        registerCommand(new PatchNotesCommand());
        registerEvent(PlayerConnectEvent.class, event -> notifyOnJoin(event.getPlayerRef()));
    }

    @Override
    public void onReload() {
        config = core.configManager().loadModuleConfig(id(), PatchNotesConfig.class, new PatchNotesConfig());
        notes = loader.load(config.generateExamples);
    }

    @Override
    public void onDisable() {
        // Patches are read from disk; read state is persisted on every mutation. Nothing to flush.
    }

    PatchNotesConfig config() {
        return config;
    }

    // ----- Patch access & ordering -------------------------------------------

    List<PatchNote> allNotes() {
        return notes;
    }

    /**
     * Returns the notes matching {@code search}, ordered pinned-first, then by
     * descending priority, then by date (newest or oldest per config), capped at
     * {@code maxPatchNotesShown}.
     */
    List<PatchNote> sortedNotes(String search) {
        List<PatchNote> matched = new ArrayList<>();
        for (PatchNote note : notes) {
            if (matchesSearch(note, search)) {
                matched.add(note);
            }
        }
        Comparator<String> dateOrder = config.sortNewestFirst()
                ? Comparator.<String>reverseOrder() : Comparator.<String>naturalOrder();
        Comparator<PatchNote> byPinned = Comparator.comparing((PatchNote n) -> n.pinned).reversed();
        Comparator<PatchNote> byPriority = Comparator.comparingInt((PatchNote n) -> n.priority).reversed();
        Comparator<PatchNote> byDate = Comparator.comparing(PatchNotesModule::dateKey, dateOrder);
        matched.sort(byPinned.thenComparing(byPriority).thenComparing(byDate));
        if (config.maxPatchNotesShown > 0 && matched.size() > config.maxPatchNotesShown) {
            return matched.subList(0, config.maxPatchNotesShown);
        }
        return matched;
    }

    private static String dateKey(PatchNote note) {
        // ISO yyyy-MM-dd sorts lexicographically; blank dates fall to the bottom of "newest".
        return note.date == null ? "" : note.date;
    }

    private static boolean matchesSearch(PatchNote note, String search) {
        if (search == null || search.isBlank()) {
            return true;
        }
        String needle = search.toLowerCase(java.util.Locale.ROOT).trim();
        if (contains(note.title, needle) || contains(note.version, needle)
                || contains(note.summary, needle) || contains(note.author, needle)) {
            return true;
        }
        if (note.tags != null) {
            for (String tag : note.tags) {
                if (contains(tag, needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean contains(String haystack, String lowerNeedle) {
        return haystack != null && haystack.toLowerCase(java.util.Locale.ROOT).contains(lowerNeedle);
    }

    PatchNote noteById(String id) {
        if (id == null) {
            return null;
        }
        for (PatchNote note : notes) {
            if (note.safeId().equals(id)) {
                return note;
            }
        }
        return null;
    }

    // ----- Read state --------------------------------------------------------

    CompletableFuture<PatchReadState> readState(UUID player) {
        StorageService storage = core.getStorageService();
        return storage.load(READ_NAMESPACE, player.toString()).thenApply(element -> {
            PatchReadState state = element == null ? null : Json.gson().fromJson(element, PatchReadState.class);
            if (state == null) {
                state = new PatchReadState(player.toString());
            }
            if (state.readPatchIds == null) {
                state.readPatchIds = new ArrayList<>();
            }
            return state;
        });
    }

    private CompletableFuture<Void> saveReadState(UUID player, PatchReadState state) {
        state.lastOpened = Instant.now().toString();
        return core.getStorageService().save(READ_NAMESPACE, player.toString(), Json.toTree(state));
    }

    /** Marks one patch read for a player. @return future of whether anything changed. */
    CompletableFuture<Boolean> markRead(UUID player, String patchId) {
        return readState(player).thenCompose(state -> {
            if (!state.markRead(patchId)) {
                return CompletableFuture.completedFuture(false);
            }
            return saveReadState(player, state).thenApply(v -> true);
        });
    }

    /** Marks every currently-loaded patch read. @return future of how many were newly marked. */
    CompletableFuture<Integer> markAllRead(UUID player) {
        return readState(player).thenCompose(state -> {
            int flipped = 0;
            for (PatchNote note : notes) {
                if (state.markRead(note.safeId())) {
                    flipped++;
                }
            }
            if (flipped == 0) {
                return CompletableFuture.completedFuture(0);
            }
            int total = flipped;
            return saveReadState(player, state).thenApply(v -> total);
        });
    }

    // ----- Join notification -------------------------------------------------

    private void notifyOnJoin(PlayerRef player) {
        // Nothing to surface if both join behaviours are off, there are no notes,
        // or the player cannot view patch notes at all.
        if ((!config.showOnJoin && !config.openOnJoin) || notes.isEmpty()
                || !player.hasPermission(Permissions.PATCHNOTES_VIEW)) {
            return;
        }
        readState(player.getUuid()).thenAccept(state -> {
            int count = 0;
            for (PatchNote note : notes) {
                if (!note.showOnLogin) {
                    continue;
                }
                if (config.showOnlyUnreadOnJoin && state.isRead(note.safeId())) {
                    continue;
                }
                count++;
            }
            if (count <= 0) {
                return;
            }
            // Auto-open takes precedence over the chat notice: showing the UI
            // makes a "you have N unread notes" chat line redundant.
            if (config.openOnJoin) {
                scheduleOpenOnJoin(player);
                return;
            }
            if (config.showOnJoin) {
                core.getMessageService().sendKey(player, "patchnotes-notify-join",
                        Map.of("count", Integer.toString(count), "command", config.openCommand));
            }
        });
    }

    /**
     * Opens the Patch Notes UI shortly after join. A delay is required because
     * the player entity is not ready to receive a Custom UI page the instant
     * {@code PlayerConnectEvent} fires; the player is re-resolved after the delay
     * so a fast disconnect is a no-op.
     */
    private void scheduleOpenOnJoin(PlayerRef player) {
        long delayMillis = Math.max(0, config.openOnJoinDelayTicks) * 50L;
        UUID uuid = player.getUuid();
        core.scheduler().runLater(() ->
                core.platform().findPlayer(uuid).ifPresent(this::openUi),
                delayMillis, TimeUnit.MILLISECONDS);
    }

    // ----- UI opening --------------------------------------------------------

    /** Opens the Patch Notes UI on the newest note with the default filter. */
    void openUi(PlayerRef player) {
        openUi(player, null, "", config.defaultFilter == null ? "all" : config.defaultFilter);
    }

    /**
     * Loads the viewer's read state (storage is async; page building is not), then
     * opens the Patch Notes UI. If no patch is selected, the first note in the
     * sorted list is auto-selected so the right pane is never blank.
     */
    void openUi(PlayerRef player, String selectedId, String search, String filter) {
        readState(player.getUuid()).thenAccept(state -> {
            List<PatchNote> visible = sortedNotes(search);
            String selection = selectedId;
            if (selection == null && !visible.isEmpty()) {
                selection = visible.get(0).safeId();
            }
            if (config.markReadOnView && selection != null) {
                final String toRead = selection;
                final String openSearch = search;
                final String openFilter = filter;
                markRead(player.getUuid(), selection).thenAccept(changed -> {
                    if (changed) {
                        // Reload the state so the freshly-read note renders without its unread dot.
                        readState(player.getUuid()).thenAccept(updated ->
                                openPage(player, updated, toRead, openSearch, openFilter));
                    } else {
                        openPage(player, state, toRead, openSearch, openFilter);
                    }
                });
            } else {
                openPage(player, state, selection, search, filter);
            }
        });
    }

    private void openPage(PlayerRef player, PatchReadState state, String selectedId, String search, String filter) {
        core.platform().openPage(player,
                new PatchNotesPages.PatchNotesPage(core, this, player, state, selectedId, search, filter));
    }

    // ----- Command -----------------------------------------------------------

    /**
     * {@code /patchnotes} opens the UI; subcommands: open [player] / reload /
     * markread [player] / list.
     */
    private final class PatchNotesCommand extends MysticCommand {
        PatchNotesCommand() {
            super(PatchNotesModule.this.core, PatchNotesModule.this.config.openCommand, "Open the server patch notes.");
            requirePermission(Permissions.PATCHNOTES_VIEW);
            for (String alias : PatchNotesModule.this.config.aliases) {
                if (alias != null && !alias.isBlank()) {
                    addAliases(alias);
                }
            }
            addSubCommand(new OpenCommand());
            addSubCommand(new ReloadCommand());
            addSubCommand(new MarkReadCommand());
            addSubCommand(new ListCommand());
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            openUi(sender.player().orElseThrow());
        }

        private final class OpenCommand extends MysticCommand {
            private final OptionalArg<String> target = withOptionalArg("player", "Player to open for", ArgTypes.STRING)
                    .suggest((commandSender, input, index, result) ->
                            core.platform().onlinePlayers().forEach(ref -> result.suggest(ref.getUsername())));

            OpenCommand() {
                super(PatchNotesModule.this.core, "open", "Open patch notes (optionally for another player).");
            }

            @Override
            protected void run(MysticCommandSender sender) {
                if (!sender.provided(target)) {
                    if (!sender.isPlayer()) {
                        sender.replyKey("player-only");
                        return;
                    }
                    openUi(sender.player().orElseThrow());
                    return;
                }
                if (!sender.hasPermission(Permissions.PATCHNOTES_OPEN_OTHERS)) {
                    sender.replyKey("no-permission");
                    return;
                }
                String name = sender.get(target);
                core.platform().findPlayerByName(name).ifPresentOrElse(ref -> {
                    openUi(ref);
                    sender.replyKey("patchnotes-opened-other", Map.of("player", ref.getUsername()));
                }, () -> sender.replyKey("player-not-found"));
            }
        }

        private final class ReloadCommand extends MysticCommand {
            ReloadCommand() {
                super(PatchNotesModule.this.core, "reload", "Reload patch notes from disk.");
                requirePermission(Permissions.PATCHNOTES_RELOAD);
            }

            @Override
            protected void run(MysticCommandSender sender) {
                onReload();
                List<String> errors = loader.fileErrors();
                sender.replyKey("patchnotes-reloaded", Map.of(
                        "count", Integer.toString(notes.size()),
                        "errors", Integer.toString(errors.size())));
                for (String error : errors) {
                    sender.reply("&c - " + error);
                }
            }
        }

        private final class MarkReadCommand extends MysticCommand {
            private final OptionalArg<String> target =
                    withOptionalArg("player", "Player to mark read for", ArgTypes.STRING)
                            .suggest((commandSender, input, index, result) ->
                                    core.platform().onlinePlayers().forEach(ref -> result.suggest(ref.getUsername())));

            MarkReadCommand() {
                super(PatchNotesModule.this.core, "markread", "Mark all patch notes as read.");
            }

            @Override
            protected void run(MysticCommandSender sender) {
                if (!sender.provided(target)) {
                    if (!sender.isPlayer()) {
                        sender.replyKey("player-only");
                        return;
                    }
                    markAllRead(sender.uuid()).thenAccept(count ->
                            sender.replyKey("patchnotes-marked-read"));
                    return;
                }
                if (!sender.hasPermission(Permissions.PATCHNOTES_MARKREAD_OTHERS)) {
                    sender.replyKey("no-permission");
                    return;
                }
                String name = sender.get(target);
                core.platform().findPlayerByName(name).ifPresentOrElse(ref ->
                        markAllRead(ref.getUuid()).thenAccept(count ->
                                sender.replyKey("patchnotes-marked-read-other",
                                        Map.of("player", ref.getUsername()))),
                        () -> sender.replyKey("player-not-found"));
            }
        }

        private final class ListCommand extends MysticCommand {
            ListCommand() {
                super(PatchNotesModule.this.core, "list", "List patch notes in chat.");
            }

            @Override
            protected void run(MysticCommandSender sender) {
                List<PatchNote> all = sortedNotes("");
                if (all.isEmpty()) {
                    sender.replyKey("patchnotes-none");
                    return;
                }
                sender.replyKey("patchnotes-list-header", Map.of("count", Integer.toString(all.size())));
                for (PatchNote note : all) {
                    sender.replyKey("patchnotes-list-entry", Map.of(
                            "pin", note.pinned ? "&e[PIN] " : "",
                            "title", note.title == null ? note.safeId() : note.title,
                            "version", note.version == null ? "" : note.version,
                            "date", note.date == null ? "" : note.date));
                }
            }
        }
    }
}
