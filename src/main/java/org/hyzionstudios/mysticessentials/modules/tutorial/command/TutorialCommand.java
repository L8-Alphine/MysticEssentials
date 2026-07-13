package org.hyzionstudios.mysticessentials.modules.tutorial.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.model.MysticLocation;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.tutorial.TutorialModule;
import org.hyzionstudios.mysticessentials.modules.tutorial.TutorialStopResult;
import org.hyzionstudios.mysticessentials.modules.tutorial.config.TutorialDefinition;
import org.hyzionstudios.mysticessentials.modules.tutorial.player.TutorialCompletionRecord;
import org.hyzionstudios.mysticessentials.modules.tutorial.scene.MachinimaSceneAssets;
import org.hyzionstudios.mysticessentials.modules.tutorial.scene.MachinimaSceneDocument;
import org.hyzionstudios.mysticessentials.modules.tutorial.scene.TutorialSceneProvider;
import org.hyzionstudios.mysticessentials.modules.tutorial.scene.TutorialSceneRequest;
import org.hyzionstudios.mysticessentials.modules.tutorial.session.TutorialPlayOptions;
import org.hyzionstudios.mysticessentials.modules.tutorial.session.TutorialSession;
import org.hyzionstudios.mysticessentials.modules.tutorial.session.TutorialStopReason;
import org.hyzionstudios.mysticessentials.platform.Conversions;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * {@code /tutorial} command tree (free-form parser, like the mail/pwarp
 * commands): help, list, info, play, stop, skip, reset, complete, status,
 * page, reload, debug — plus the shortcut {@code /tutorial <tutorial>
 * [player]} which maps to {@code play}. Every subcommand is permission
 * protected; {@code mysticessentials.tutorial.admin} grants everything.
 */
public final class TutorialCommand extends MysticCommand {

    private static final Set<String> KEYWORDS = Set.of("help", "list", "info", "play", "stop",
            "skip", "reset", "complete", "status", "page", "reload", "debug", "scene");

    private final TutorialModule module;

    public TutorialCommand(MysticCore core, TutorialModule module) {
        super(core, "tutorial", "Play and manage server tutorials.");
        this.module = module;
        allowExtraArguments();
    }

    @Override
    protected void run(MysticCommandSender sender) {
        String[] args = sender.args();
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);

        // Shortcut: /tutorial <tutorial> [player] → /tutorial play <tutorial> [player]
        if (!KEYWORDS.contains(sub)) {
            play(sender, args[0], optionalArg(args, 1), hasFlag(args, "--force"));
            return;
        }
        // Everything except reload works only while the module is switched on,
        // so an owner can enable config.json and /tutorial reload without a restart.
        if (!module.config().enabled && !sub.equals("reload") && !sub.equals("help")) {
            sender.replyKey("module-disabled");
            return;
        }
        switch (sub) {
            case "help" -> help(sender);
            case "list" -> list(sender);
            case "info" -> info(sender, optionalArg(args, 1));
            case "play" -> play(sender, optionalArg(args, 1), optionalArg(args, 2), hasFlag(args, "--force"));
            case "stop" -> stop(sender, optionalArg(args, 1));
            case "skip" -> skip(sender, optionalArg(args, 1));
            case "reset" -> resetOrComplete(sender, optionalArg(args, 1), optionalArg(args, 2), true);
            case "complete" -> resetOrComplete(sender, optionalArg(args, 1), optionalArg(args, 2), false);
            case "status" -> status(sender, optionalArg(args, 1));
            case "page" -> page(sender, optionalArg(args, 1), optionalArg(args, 2));
            case "reload" -> reload(sender);
            case "debug" -> debug(sender, optionalArg(args, 1));
            case "scene" -> scene(sender, args);
            default -> help(sender);
        }
    }

    private static String optionalArg(String[] args, int index) {
        if (index >= args.length) {
            return null;
        }
        String value = args[index];
        return value.startsWith("--") ? null : value;
    }

    /** Matches a flag whether typed bare ({@code relocate}) or dashed; the engine strips {@code --}. */
    private static boolean hasFlag(String[] args, String flag) {
        String want = flag.replaceFirst("^-+", "");
        for (String arg : args) {
            if (want.equalsIgnoreCase(arg.replaceFirst("^-+", ""))) {
                return true;
            }
        }
        return false;
    }

    // ----- Subcommands ----------------------------------------------------------

    private void help(MysticCommandSender sender) {
        sender.reply("&d&lTutorials &7— commands:");
        sender.reply("&f/tutorial list &7— available tutorials");
        sender.reply("&f/tutorial info <tutorial> &7— tutorial details");
        sender.reply("&f/tutorial play <tutorial> [player] [--force] &7— start a tutorial");
        sender.reply("&f/tutorial stop [player] &7— stop a running tutorial");
        sender.reply("&f/tutorial skip [player] &7— skip the current tutorial");
        sender.reply("&f/tutorial reset <tutorial> <player> &7— clear a completion");
        sender.reply("&f/tutorial complete <tutorial> <player> &7— mark completed");
        sender.reply("&f/tutorial status [player] &7— progress overview");
        sender.reply("&f/tutorial page <page> [player] &7— open a tutorial page");
        sender.reply("&f/tutorial reload &7— reload configs and definitions");
        sender.reply("&f/tutorial debug <on|off> &7— toggle debug logging");
        sender.reply("&f/tutorial scene <list|info|import|play|stop> &7— manage cinematic scenes");
    }

    /** {@code /tutorial scene <list|info|import|play|stop>} — scene tooling (admin). */
    private void scene(MysticCommandSender sender, String[] args) {
        if (!TutorialCommandPermissions.has(sender, TutorialCommandPermissions.SCENE)) {
            sender.replyKey("no-permission");
            return;
        }
        MachinimaSceneAssets assets = module.sceneAssets();
        if (assets == null) {
            sender.reply("&cThe scene system is not available.");
            return;
        }
        String action = optionalArg(args, 1);
        if (action == null) {
            action = "list";
        }
        switch (action.toLowerCase(Locale.ROOT)) {
            case "list" -> sceneList(sender, assets);
            case "info" -> sceneInfo(sender, assets, optionalArg(args, 2));
            case "import" -> sceneImport(sender, assets);
            case "play" -> scenePlay(sender, assets, args);
            case "stop" -> sceneStop(sender, optionalArg(args, 2));
            default -> sender.reply("&cUsage: /tutorial scene <list|info|import|play|stop>");
        }
    }

    private void sceneList(MysticCommandSender sender, MachinimaSceneAssets assets) {
        List<String> scenes = assets.listScenes();
        if (scenes.isEmpty()) {
            sender.reply("&7No scenes found in &f" + assets.sceneDir()
                    + "&7. Drop exported scene .json files there (or in the import/ folder)"
                    + " and run &f/tutorial scene import&7.");
            return;
        }
        sender.reply("&d&lScenes &7(" + scenes.size() + "):");
        for (String id : scenes) {
            sender.reply("&8 - &f" + id);
        }
    }

    private void sceneInfo(MysticCommandSender sender, MachinimaSceneAssets assets, String sceneId) {
        if (sceneId == null) {
            sender.reply("&cUsage: /tutorial scene info <sceneId>");
            return;
        }
        MachinimaSceneDocument document = assets.loadDocument(sceneId);
        if (document == null) {
            sender.reply("&cScene '&f" + sceneId + "&c' was not found or is not valid scene JSON.");
            return;
        }
        double[] origin = document.origin();
        String originText = origin == null ? "none"
                : String.format(Locale.ROOT, "%.1f, %.1f, %.1f", origin[0], origin[1], origin[2]);
        sender.reply("&d&l" + document.name(sceneId) + " &7(" + sceneId + ")");
        sender.reply("&7Version: &f" + document.version()
                + " &7| Actors: &f" + document.actorCount()
                + " &7| Keyframes: &f" + document.keyframeCount());
        sender.reply("&7Origin: &f" + originText);
    }

    private void sceneImport(MysticCommandSender sender, MachinimaSceneAssets assets) {
        MachinimaSceneAssets.ImportResult result = assets.importDropFolder();
        assets.clearCache();
        if (result.imported().isEmpty() && result.failed().isEmpty()) {
            sender.reply("&7Nothing to import. Drop exported scene .json files into &f"
                    + assets.importDir() + "&7 and run this again.");
            return;
        }
        if (!result.imported().isEmpty()) {
            sender.reply("&aImported &f" + result.imported().size() + "&a scene(s): &f"
                    + String.join(", ", result.imported()));
        }
        for (Map.Entry<String, String> failure : result.failed().entrySet()) {
            sender.reply("&cSkipped &f" + failure.getKey() + "&c: " + failure.getValue());
        }
    }

    private void scenePlay(MysticCommandSender sender, MachinimaSceneAssets assets, String[] args) {
        // The engine consumes "--"-prefixed tokens before run() sees them, so
        // flags are plain keywords: `relocate` (or `r`). Positional args (scene,
        // player) are whatever is left after the flags are removed.
        boolean relocate = hasFlag(args, "relocate") || hasFlag(args, "r");
        List<String> positional = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            String bare = args[i].replaceFirst("^-+", "");
            if (bare.equalsIgnoreCase("relocate") || bare.equalsIgnoreCase("r")) {
                continue;
            }
            positional.add(args[i]);
        }
        String sceneId = positional.isEmpty() ? null : positional.get(0);
        String targetName = positional.size() > 1 ? positional.get(1) : null;
        if (sceneId == null) {
            sender.reply("&cUsage: /tutorial scene play <sceneId> [player] [relocate]");
            return;
        }
        if (assets.load(sceneId) == null) {
            sender.reply("&cNo scene file '&f" + sceneId + "&c' found. Import it first with"
                    + " /tutorial scene import (see /tutorial scene list).");
            return;
        }
        TutorialSceneProvider provider = module.resolveSceneProvider();
        if (provider == null) {
            sender.reply("&cNo scene provider is available (check sceneProvider.type).");
            return;
        }
        PlayerRef target = resolveOnlineTarget(sender, targetName);
        if (target == null) {
            return;
        }
        TutorialSceneRequest request;
        if (relocate) {
            MysticLocation here = Conversions.capture(target);
            request = new TutorialSceneRequest(target, "", sceneId, "", false, 30,
                    true, here.getX(), here.getY(), here.getZ());
        } else {
            request = new TutorialSceneRequest(target, "", sceneId, "", false, 30);
        }
        provider.playScene(request).thenAccept(result -> {
            if (result.isCompleted()) {
                sender.reply("&aPlaying scene '&f" + sceneId + "&a' for &f" + target.getUsername()
                        + (relocate ? "&a (relocated to their position)" : "")
                        + "&a via '" + provider.id() + "'. &7Use /tutorial scene stop to end it.");
            } else {
                sender.reply("&cCould not play scene '&f" + sceneId + "&c': " + result);
            }
        });
    }

    private void sceneStop(MysticCommandSender sender, String targetName) {
        TutorialSceneProvider provider = module.resolveSceneProvider();
        PlayerRef target = resolveOnlineTarget(sender, targetName);
        if (target == null) {
            return;
        }
        if (provider == null) {
            sender.reply("&cNo scene provider is available.");
            return;
        }
        provider.stopScene(target.getUuid()).thenRun(() ->
                sender.reply("&aStopped any scene playing for &f" + target.getUsername() + "&a."));
    }

    private void list(MysticCommandSender sender) {
        if (!TutorialCommandPermissions.has(sender, TutorialCommandPermissions.LIST)) {
            sender.replyKey("no-permission");
            return;
        }
        var tutorials = module.loader().tutorials();
        if (tutorials.isEmpty()) {
            sender.replyKey("tutorial-list-empty");
            return;
        }
        sender.replyKey("tutorial-list-header", Map.of("count", Integer.toString(tutorials.size())));
        for (TutorialDefinition def : tutorials) {
            sender.reply("&8 - &f" + def.id + " &7(" + def.displayNameOrId() + ")"
                    + (def.enabled ? "" : " &c[disabled]"));
        }
    }

    private void info(MysticCommandSender sender, String tutorialId) {
        if (!TutorialCommandPermissions.has(sender, TutorialCommandPermissions.INFO)) {
            sender.replyKey("no-permission");
            return;
        }
        if (tutorialId == null) {
            sender.reply("&cUsage: /tutorial info <tutorial>");
            return;
        }
        TutorialDefinition def = module.loader().tutorial(tutorialId).orElse(null);
        if (def == null) {
            sender.replyKey("tutorial-unknown", Map.of("tutorial", tutorialId));
            return;
        }
        sender.reply("&d&l" + def.displayNameOrId() + " &7(" + def.id + ")"
                + (def.enabled ? "" : " &c[disabled]"));
        if (!def.description.isBlank()) {
            sender.reply("&7" + def.description);
        }
        sender.reply("&7Replayable: &f" + def.replay.allowReplay
                + " &7| Scene: &f" + (def.machinima.enabled && !def.machinima.sceneId.isBlank()
                        ? def.machinima.sceneId : "none")
                + " &7| Completion page: &f" + (def.completion.showPage
                        ? (def.completion.pageId.isBlank()
                                ? module.config().ui.defaultCompletionPage : def.completion.pageId)
                        : "none"));
    }

    private void play(MysticCommandSender sender, String tutorialId, String targetName, boolean force) {
        if (!module.config().enabled) {
            sender.replyKey("module-disabled");
            return;
        }
        if (tutorialId == null) {
            sender.reply("&cUsage: /tutorial play <tutorial> [player] [--force]");
            return;
        }
        boolean others = targetName != null;
        String node = others ? TutorialCommandPermissions.PLAY_OTHERS : TutorialCommandPermissions.PLAY;
        if (!TutorialCommandPermissions.has(sender, node)) {
            sender.replyKey("no-permission");
            return;
        }
        PlayerRef target = resolveOnlineTarget(sender, targetName);
        if (target == null) {
            return;
        }
        module.sessions().start(target, tutorialId,
                        TutorialPlayOptions.of(TutorialPlayOptions.Source.COMMAND, force, sender.name()))
                .thenAccept(result -> {
                    if (result.started()) {
                        if (others) {
                            sender.replyKey("tutorial-started-other",
                                    Map.of("tutorial", tutorialId, "player", target.getUsername()));
                        } else {
                            sender.replyKey("tutorial-started", Map.of("tutorial", tutorialId));
                        }
                    } else {
                        sender.replyKey(result.messageKey(), Map.of("tutorial", tutorialId));
                    }
                });
    }

    private void stop(MysticCommandSender sender, String targetName) {
        boolean others = targetName != null;
        String node = others ? TutorialCommandPermissions.STOP_OTHERS : TutorialCommandPermissions.STOP;
        if (!TutorialCommandPermissions.has(sender, node)) {
            sender.replyKey("no-permission");
            return;
        }
        PlayerRef target = resolveOnlineTarget(sender, targetName);
        if (target == null) {
            return;
        }
        TutorialStopResult result = module.sessions().stop(target.getUuid(),
                others ? TutorialStopReason.ADMIN : TutorialStopReason.CANCELLED);
        if (result.stopped()) {
            sender.replyKey(others ? "tutorial-stopped-other" : "tutorial-stopped",
                    Map.of("player", target.getUsername()));
        } else {
            sender.replyKey(others ? "tutorial-not-in-other" : "tutorial-not-in",
                    Map.of("player", target.getUsername()));
        }
    }

    private void skip(MysticCommandSender sender, String targetName) {
        boolean others = targetName != null;
        if (others) {
            if (!TutorialCommandPermissions.has(sender, TutorialCommandPermissions.SKIP_OTHERS)) {
                sender.replyKey("no-permission");
                return;
            }
        } else {
            // Self-skip: the command node, or the owner-configured skip
            // permission when skipping is switched on in the defaults.
            boolean allowed = TutorialCommandPermissions.has(sender, TutorialCommandPermissions.SKIP)
                    || (module.config().defaults.allowSkip
                            && sender.hasPermission(module.config().defaults.skipPermission));
            if (!allowed) {
                sender.replyKey("no-permission");
                return;
            }
        }
        PlayerRef target = resolveOnlineTarget(sender, targetName);
        if (target == null) {
            return;
        }
        TutorialStopResult result = module.sessions().stop(target.getUuid(), TutorialStopReason.SKIPPED);
        if (result.stopped()) {
            sender.replyKey("tutorial-skipped", Map.of("player", target.getUsername()));
        } else {
            sender.replyKey(others ? "tutorial-not-in-other" : "tutorial-not-in",
                    Map.of("player", target.getUsername()));
        }
    }

    private void resetOrComplete(MysticCommandSender sender, String tutorialId, String targetName,
            boolean reset) {
        String node = reset ? TutorialCommandPermissions.RESET : TutorialCommandPermissions.COMPLETE;
        if (!TutorialCommandPermissions.has(sender, node)) {
            sender.replyKey("no-permission");
            return;
        }
        if (tutorialId == null || targetName == null) {
            sender.reply("&cUsage: /tutorial " + (reset ? "reset" : "complete") + " <tutorial> <player>");
            return;
        }
        // Works for offline players too: resolve the UUID through the profile
        // service's username index, then load/patch/save the data file.
        core.getPlayerProfileService().resolveUuid(targetName).thenAccept(resolved -> {
            UUID uuid = resolved.orElse(null);
            if (uuid == null) {
                sender.replyKey("player-not-found");
                return;
            }
            module.storage().load(uuid, targetName).thenAccept(data -> {
                synchronized (data) {
                    if (reset) {
                        data.tutorials.remove(tutorialId.toLowerCase(Locale.ROOT));
                        data.addHistory(tutorialId, "reset", System.currentTimeMillis());
                    } else {
                        data.record(tutorialId).markCompleted(System.currentTimeMillis());
                        data.addHistory(tutorialId, "complete", System.currentTimeMillis());
                    }
                }
                module.storage().markDirty(uuid);
                finishOfflineEdit(uuid);
                sender.replyKey(reset ? "tutorial-reset-done" : "tutorial-complete-done",
                        Map.of("tutorial", tutorialId, "player", targetName));
            });
        });
    }

    private void status(MysticCommandSender sender, String targetName) {
        boolean others = targetName != null;
        String node = others ? TutorialCommandPermissions.STATUS_OTHERS : TutorialCommandPermissions.STATUS;
        if (!TutorialCommandPermissions.has(sender, node)) {
            sender.replyKey("no-permission");
            return;
        }
        if (!others && !sender.isPlayer()) {
            sender.replyKey("player-only");
            return;
        }
        String name = others ? targetName : sender.name();
        core.getPlayerProfileService().resolveUuid(name).thenAccept(resolved -> {
            UUID uuid = resolved.orElse(null);
            if (uuid == null) {
                sender.replyKey("player-not-found");
                return;
            }
            module.storage().load(uuid, name).thenAccept(data -> {
                Optional<TutorialSession> session = module.sessions().session(uuid);
                if (session.isPresent()) {
                    long seconds = (System.currentTimeMillis() - session.get().startedAt) / 1000L;
                    sender.replyKey("tutorial-status-active", Map.of(
                            "player", name,
                            "tutorial", session.get().definition.id,
                            "state", session.get().state().name(),
                            "seconds", Long.toString(seconds)));
                } else {
                    sender.replyKey("tutorial-status-idle", Map.of("player", name));
                }
                List<String> completed = new ArrayList<>();
                synchronized (data) {
                    for (TutorialCompletionRecord record : data.tutorials.values()) {
                        if (record.completed) {
                            completed.add(record.tutorialId);
                        }
                    }
                }
                sender.replyKey("tutorial-status-completed", Map.of(
                        "count", Integer.toString(completed.size()),
                        "tutorials", completed.isEmpty() ? "-" : String.join(", ", completed)));
                finishOfflineEdit(uuid);
            });
        });
    }

    private void page(MysticCommandSender sender, String pageId, String targetName) {
        boolean others = targetName != null;
        String node = others ? TutorialCommandPermissions.PAGE_OTHERS : TutorialCommandPermissions.PAGE;
        if (!TutorialCommandPermissions.has(sender, node)) {
            sender.replyKey("no-permission");
            return;
        }
        if (pageId == null) {
            sender.reply("&cUsage: /tutorial page <page> [player]");
            return;
        }
        PlayerRef target = resolveOnlineTarget(sender, targetName);
        if (target == null) {
            return;
        }
        module.pageService().openPage(target, pageId, "").thenAccept(opened -> {
            if (opened && others) {
                sender.replyKey("tutorial-page-opened",
                        Map.of("page", pageId, "player", target.getUsername()));
            }
        });
    }

    private void reload(MysticCommandSender sender) {
        if (!TutorialCommandPermissions.has(sender, TutorialCommandPermissions.RELOAD)) {
            sender.replyKey("no-permission");
            return;
        }
        module.onReload();
        sender.replyKey("tutorial-reloaded", Map.of(
                "tutorials", Integer.toString(module.loader().tutorials().size()),
                "pages", Integer.toString(module.loader().pages().size())));
    }

    private void debug(MysticCommandSender sender, String value) {
        if (!TutorialCommandPermissions.has(sender, TutorialCommandPermissions.DEBUG)) {
            sender.replyKey("no-permission");
            return;
        }
        if (value == null || !(value.equalsIgnoreCase("on") || value.equalsIgnoreCase("off"))) {
            sender.reply("&cUsage: /tutorial debug <on|off>");
            return;
        }
        boolean enable = value.equalsIgnoreCase("on");
        module.setDebug(enable);
        sender.replyKey(enable ? "tutorial-debug-on" : "tutorial-debug-off");
    }

    // ----- Helpers ---------------------------------------------------------------

    /**
     * Resolves the online target for a command: the named player, or the
     * sender themself when no name was given. Replies with the appropriate
     * error and returns {@code null} when unavailable.
     */
    private PlayerRef resolveOnlineTarget(MysticCommandSender sender, String targetName) {
        if (targetName == null) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return null;
            }
            return sender.player().orElse(null);
        }
        PlayerRef target = core.platform().findPlayerByName(targetName).orElse(null);
        if (target == null) {
            sender.replyKey("player-not-found");
        }
        return target;
    }

    /** Evicts data loaded purely for an offline edit so the cache tracks online players only. */
    private void finishOfflineEdit(UUID uuid) {
        if (core.platform().findPlayer(uuid).isEmpty()) {
            module.storage().unload(uuid);
        }
    }
}
