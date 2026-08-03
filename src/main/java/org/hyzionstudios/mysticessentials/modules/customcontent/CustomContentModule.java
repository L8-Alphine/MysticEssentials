package org.hyzionstudios.mysticessentials.modules.customcontent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;
import org.hyzionstudios.mysticessentials.modules.customcontent.layout.LayoutRuntime;
import org.hyzionstudios.mysticessentials.modules.customcontent.layout.PlayerPortraitService;
import org.hyzionstudios.mysticessentials.modules.customcontent.layout.UiDocument;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.hypixel.hytale.server.core.command.system.CommandRegistration;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Optional CustomGUIs and CustomDialogs feature set behind one Essentials
 * module toggle, with soft compatibility for legacy files and action engines.
 */
public final class CustomContentModule extends AbstractMysticModule {

    private CustomContentConfig config = new CustomContentConfig();
    private DialogStore dialogs;
    private CustomDialogExporter exporter;
    private CustomGuiRepository guis;
    private CustomContentBridge bridge;
    private LayoutRuntime layouts;
    private PlayerPortraitService portraits;
    private final List<CommandRegistration> guiAliasRegistrations = new ArrayList<>();

    public CustomContentModule() {
        super("customcontent", "Custom Content UI Framework", "2.0.0");
    }

    /**
     * CustomGUIs and CustomDialogs are a licensed feature. Without the grant the
     * module never enables and its commands are never registered; the rest of
     * Mystic Essentials is untouched.
     */
    @Override
    public String licensedFeature() {
        return com.mysticlicensing.license.Products.Essentials.MODULE_CUSTOM_CONTENT;
    }

    @Override
    public void onEnable() {
        loadState(true);
        registerCommand(new CustomContentCommand(config.command));
        if (config.customDialogsEnabled && validCommand(config.customDialogsCommand)) {
            registerCommand(new DialogBuilderCommand(config.customDialogsCommand));
        }
        if (config.customGuisEnabled && validCommand(config.customGuisCommand)) {
            registerCommand(new GuiAdminCommand(config.customGuisCommand));
        }
        registerEvent(PlayerConnectEvent.class,
                (PlayerConnectEvent event) -> showAutoHuds(event.getPlayerRef()));
        registerEvent(PlayerDisconnectEvent.class, (PlayerDisconnectEvent event) -> {
            if (portraits != null) {
                portraits.handlerClosed(event.getPlayerRef().getPacketHandler());
            }
        });
        log("Loaded " + dialogs.ids().size() + " dialog(s) and " + guis.all().size()
                + " CustomGUI(s); compatibility bridge "
                + (bridge.connected() ? "connected." : "not present."));
    }

    @Override
    public void onReload() {
        unregisterGuiAliases();
        loadState(false);
        log("Reloaded " + dialogs.ids().size() + " dialog(s) and " + guis.all().size() + " GUI(s).");
    }

    @Override
    public void onDisable() {
        unregisterGuiAliases();
        if (layouts != null) {
            layouts.hideAllHuds();
            layouts.stop();
        }
        if (portraits != null) {
            portraits.stop();
            portraits = null;
        }
        if (dialogs != null) {
            dialogs.save();
        }
        if (exporter != null && dialogs != null) {
            exporter.exportAll(dialogs.all().values());
        }
        if (bridge != null) {
            bridge.reloadQuests();
            bridge.disconnect();
        }
    }

    private void loadState(boolean allowImport) {
        if (bridge != null) {
            bridge.disconnect();
        }
        if (layouts != null) {
            // Overlays are re-shown from the freshly compiled documents below.
            layouts.hideAllHuds();
            layouts.stop();
        }
        if (portraits != null) {
            portraits.stop();
            portraits = null;
        }
        config = core.configManager().loadModuleConfig(id(), CustomContentConfig.class, new CustomContentConfig());
        Path moduleDirectory = core.paths().moduleConfigDir(id());
        dialogs = new DialogStore(core, moduleDirectory.resolve("dialogs.json"));
        guis = new CustomGuiRepository(core, moduleDirectory.resolve("guis"));
        guis.maxNodes(config.maxGuiElements);

        if (allowImport && config.importStandaloneData) {
            boolean importedDialogs = dialogs.importStandalone(
                    Path.of("mods", "QuestLinesDialog", "dialogs.json"));
            int importedGuis = guis.importStandalone(Path.of("mods", "QuestLinesGUI", "guis"));
            if (importedDialogs || importedGuis > 0) {
                log("Imported standalone data (" + (importedDialogs ? "dialogs" : "no dialogs")
                        + ", " + importedGuis + " GUI file(s)).");
            }
            int examples = guis.seedExamples();
            if (examples > 0) {
                log("Wrote " + examples + " example GUI document(s) to " + guis.directory() + ".");
            }
        }

        dialogs.load();
        guis.load();
        exporter = new CustomDialogExporter(core, Path.of(config.dialogExportDirectory));
        importExportedDialogs();
        bridge = new CustomContentBridge(core, config);
        bridge.connect(this::openGui);
        if (config.playerPortraitsEnabled) {
            portraits = new PlayerPortraitService(core, moduleDirectory.resolve("portrait_cache"),
                    config.playerPortraitApiTemplate, config.playerPortraitCacheHours,
                    username -> {
                        LayoutRuntime active = layouts;
                        if (active != null) {
                            active.portraitAvailable(username);
                        }
                    });
            portraits.start();
        }
        layouts = new LayoutRuntime(core, bridge, guis::get, portraits);
        layouts.verifyAssets();
        layouts.start();
        registerGuiAliases();
        core.platform().onlinePlayers().forEach(this::showAutoHuds);
    }

    /** Shows every {@code auto-show} HUD document to one player. */
    private void showAutoHuds(PlayerRef player) {
        if (!config.customGuisEnabled || player == null) {
            return;
        }
        for (UiDocument document : guis.all().values()) {
            if (document.hud() && document.hudAutoShow) {
                layouts.showHud(player, document);
            }
        }
    }

    private void importExportedDialogs() {
        int imported = 0;
        for (Map.Entry<String, DialogDefinition> entry : exporter.scanExports().entrySet()) {
            if (dialogs.contains(entry.getKey())) {
                continue;
            }
            dialogs.put(entry.getValue());
            imported++;
        }
        if (imported > 0) {
            dialogs.save();
            log("Imported " + imported + " compatible dialog export(s).");
        }
    }

    private void registerGuiAliases() {
        if (!config.customGuisEnabled || !config.registerGuiAliasCommands) {
            return;
        }
        for (UiDocument document : guis.all().values()) {
            if (!validCommand(document.command)) {
                continue;
            }
            try {
                CommandRegistration registration = core.platform().registerCommand(
                        new GuiAliasCommand(document.command, document.id));
                if (registration != null) {
                    guiAliasRegistrations.add(registration);
                }
            } catch (Throwable t) {
                core.log(Level.WARNING, "[customcontent] Could not register /" + document.command
                        + " for GUI '" + document.id + "': " + t.getMessage());
            }
        }
    }

    private void unregisterGuiAliases() {
        for (CommandRegistration registration : guiAliasRegistrations) {
            try {
                registration.unregister();
            } catch (Throwable ignored) {
            }
        }
        guiAliasRegistrations.clear();
    }

    private static boolean validCommand(String command) {
        return command != null && command.matches("[a-zA-Z0-9_-]+");
    }

    CustomContentConfig config() {
        return config;
    }

    DialogStore dialogs() {
        return dialogs;
    }

    CustomDialogExporter exporter() {
        return exporter;
    }

    CustomContentBridge bridge() {
        return bridge;
    }

    void persistAndExport(DialogDefinition dialog) {
        dialogs.save();
        try {
            exporter.export(dialog);
        } catch (Exception e) {
            core.log(Level.WARNING, "[customcontent] Could not export dialog '" + dialog.id + "': "
                    + e.getMessage());
        }
    }

    void openDialogBuilder(PlayerRef player) {
        if (config.customDialogsEnabled) {
            core.platform().openPage(player,
                    new CustomContentPages.DialogBuilderPage(core, this, player, null, -1, -1, false));
        }
    }

    /**
     * Opens {@code id} for the player. HUD documents are shown as overlays;
     * everything else opens as a window.
     */
    void openGui(PlayerRef player, String id) {
        if (!config.customGuisEnabled || player == null) {
            return;
        }
        UiDocument document = guis.get(id);
        if (document == null) {
            core.getMessageService().send(player, "&cUnknown CustomGUI: &f" + id);
            return;
        }
        if (document.hud()) {
            layouts.showHud(player, document);
        } else {
            layouts.openPage(player, document);
        }
    }

    private void reloadAll() {
        unregisterGuiAliases();
        layouts.hideAllHuds();
        dialogs.load();
        guis.maxNodes(config.maxGuiElements);
        guis.load();
        bridge.connect(this::openGui);
        registerGuiAliases();
        core.platform().onlinePlayers().forEach(this::showAutoHuds);
    }

    private void showGuiList(MysticCommandSender sender) {
        if (guis.all().isEmpty()) {
            sender.reply("&eNo declarative GUIs are loaded from &f" + guis.directory());
            return;
        }
        sender.reply("&6Loaded CustomGUIs (" + guis.all().size() + "):");
        guis.all().values().forEach(gui -> sender.reply("&7- &f" + gui.id
                + " &8[" + (gui.hud() ? "hud" : gui.width + "x" + gui.height) + ", "
                + gui.nodeCount() + " elements]"
                + (gui.command == null ? "" : " &7(/" + gui.command + ")")));
    }

    /** Shows or hides a HUD document for a player. */
    private void handleHudCommand(MysticCommandSender sender, String[] args) {
        String mode = value(args, 0);
        String id = value(args, 1);
        if (mode == null || id == null) {
            sender.reply("&cUsage: /" + config.customGuisCommand + " hud <show|hide> <id> [player]");
            return;
        }
        UiDocument document = guis.get(id);
        if (document == null || !document.hud()) {
            sender.reply("&cNo HUD document named &f" + id + "&c.");
            return;
        }
        String targetName = value(args, 2);
        PlayerRef target;
        if (targetName == null || targetName.isBlank()) {
            if (!sender.isPlayer()) {
                sender.reply("&cConsole must provide a player name.");
                return;
            }
            target = sender.player().orElseThrow();
        } else {
            target = core.platform().findPlayerByName(targetName).orElse(null);
            if (target == null) {
                sender.replyKey("player-not-found");
                return;
            }
        }
        if (mode.equalsIgnoreCase("hide")) {
            layouts.hideHud(target, document.id);
            sender.reply("&aHid &f" + document.id + " &afor &f" + target.getUsername() + "&a.");
        } else {
            layouts.showHud(target, document);
            sender.reply("&aShowed &f" + document.id + " &afor &f" + target.getUsername() + "&a.");
        }
    }

    private void openGuiFromCommand(MysticCommandSender sender, String id, String targetName) {
        if (id == null || id.isBlank()) {
            sender.reply("&cUsage: /" + config.customGuisCommand + " open <id> [player]");
            return;
        }
        if (targetName == null || targetName.isBlank()) {
            if (!sender.isPlayer()) {
                sender.reply("&cConsole must provide a player name.");
                return;
            }
            openGui(sender.player().orElseThrow(), id);
            return;
        }
        core.platform().findPlayerByName(targetName).ifPresentOrElse(player -> {
            openGui(player, id);
            sender.reply("&aOpened &f" + id + " &afor &f" + player.getUsername() + "&a.");
        }, () -> sender.replyKey("player-not-found"));
    }

    private void handleGuiAdmin(MysticCommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            showGuiList(sender);
            return;
        }
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "open" -> openGuiFromCommand(sender, value(args, 1), value(args, 2));
            case "hud" -> handleHudCommand(sender, java.util.Arrays.copyOfRange(args, 1, args.length));
            case "validate" -> showValidation(sender, value(args, 1));
            case "inspect" -> inspectUi(sender, value(args, 1));
            case "debug" -> debugSession(sender, value(args, 1));
            case "reload" -> {
                reloadAll();
                sender.reply("&aReloaded &f" + guis.all().size()
                        + " &aCustomGUI(s) from &f" + guis.directory() + "&a.");
            }
            default -> openGuiFromCommand(sender, args[0], value(args, 1));
        }
    }

    private void showValidation(MysticCommandSender sender, String filter) {
        boolean strict = "--strict".equalsIgnoreCase(filter);
        String document = strict ? null : filter;
        var findings = guis.diagnostics().stream()
                .filter(finding -> document == null || finding.source().toLowerCase(java.util.Locale.ROOT)
                        .contains(document.toLowerCase(java.util.Locale.ROOT)))
                .toList();
        long errors = findings.stream().filter(finding -> finding.severity()
                == org.hyzionstudios.mysticessentials.api.ui.UiDiagnostic.Severity.ERROR).count();
        long warnings = findings.stream().filter(finding -> finding.severity()
                == org.hyzionstudios.mysticessentials.api.ui.UiDiagnostic.Severity.WARNING).count();
        sender.reply("&6Custom UI validation: &f" + errors + " error(s)&7, &f"
                + warnings + " warning(s).");
        findings.stream().filter(finding -> strict || finding.severity()
                != org.hyzionstudios.mysticessentials.api.ui.UiDiagnostic.Severity.INFO)
                .limit(20).forEach(finding -> sender.reply(
                        (finding.severity() == org.hyzionstudios.mysticessentials.api.ui.UiDiagnostic.Severity.ERROR
                                ? "&c" : "&e") + finding.source() + "&7: " + finding.message()));
        if (findings.size() > 20) sender.reply("&7...and " + (findings.size() - 20) + " more finding(s).");
    }

    private void inspectUi(MysticCommandSender sender, String id) {
        if (id == null || id.isBlank()) {
            sender.reply("&cUsage: /customui inspect <id>");
            return;
        }
        var registry = core.getCustomUiService().registry();
        var blueprint = registry.surface(id).or(() -> registry.component(id)).or(() -> registry.theme(id));
        if (blueprint.isEmpty()) {
            sender.reply("&cNo compiled UI blueprint named &f" + id + "&c.");
            return;
        }
        var value = blueprint.orElseThrow();
        sender.reply("&6" + value.id() + " &7[" + value.kind().name().toLowerCase(java.util.Locale.ROOT)
                + ", " + value.surface().name().toLowerCase(java.util.Locale.ROOT) + "]");
        sender.reply("&7Version: &f" + value.version() + "&7, components: &f" + value.nodeCount()
                + "&7, source: &f" + value.source());
        sender.reply("&7Theme: &f" + (value.theme() == null ? "default" : value.theme())
                + "&7, controller: &f" + (value.controller() == null ? "none" : value.controller()));
    }

    private void debugSession(MysticCommandSender sender, String playerName) {
        PlayerRef target = playerName == null ? sender.player().orElse(null)
                : core.platform().findPlayerByName(playerName).orElse(null);
        if (target == null) {
            sender.reply("&cProvide an online player name.");
            return;
        }
        var session = core.getCustomUiService().sessions().find(target.getUuid()).orElse(null);
        if (session == null) {
            sender.reply("&e" + target.getUsername() + " has no active Custom UI session.");
            return;
        }
        sender.reply("&6Custom UI session for &f" + target.getUsername());
        sender.reply("&7Page: &f" + session.currentPage() + "&7, route: &f" + session.currentRoute());
        sender.reply("&7History: &f" + session.navigationHistory() + "&7, dialogs: &f" + session.openDialogs());
        sender.reply("&7HUDs: &f" + session.activeHuds() + "&7, last action: &f" + session.lastAction());
        if (session.lastValidationError() != null) sender.reply("&cLast validation: " + session.lastValidationError());
    }

    private static String value(String[] args, int index) {
        return index >= 0 && index < args.length ? args[index] : null;
    }

    private final class CustomContentCommand extends MysticCommand {
        CustomContentCommand(String name) {
            super(CustomContentModule.this.core, validCommand(name) ? name : "customcontent",
                    "Manage CustomGUIs and CustomDialogs.");
            requirePermission(Permissions.CUSTOMCONTENT_ADMIN);
            allowExtraArguments();
            if (config.aliases != null) {
                config.aliases.stream().filter(CustomContentModule::validCommand)
                        .forEach(this::addAliases);
            }
        }

        @Override
        protected void run(MysticCommandSender sender) {
            String[] args = sender.args();
            if (args.length == 0) {
                sender.reply("&6CustomGUIs & CustomDialogs &7- dialogs: &f" + dialogs.ids().size()
                        + "&7, GUIs: &f" + guis.all().size() + "&7, bridge: &f"
                        + (bridge.connected() ? "connected" : "offline"));
                sender.reply("&7/" + config.command
                        + " dialog | gui <list|open|hud|reload> | import | reload");
                return;
            }
            switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
                case "dialog", "builder" -> {
                    if (!sender.isPlayer()) {
                        sender.replyKey("player-only");
                    } else {
                        openDialogBuilder(sender.player().orElseThrow());
                    }
                }
                case "gui" -> handleGuiAdmin(sender,
                        java.util.Arrays.copyOfRange(args, 1, args.length));
                case "import" -> {
                    importExportedDialogs();
                    sender.reply("&aImport complete. &f" + dialogs.ids().size() + " &adialog(s) loaded.");
                }
                case "reload" -> {
                    onReload();
                    sender.reply("&aReloaded CustomGUIs & CustomDialogs.");
                }
                default -> sender.reply("&cUnknown subcommand.");
            }
        }
    }

    private final class DialogBuilderCommand extends MysticCommand {
        DialogBuilderCommand(String name) {
            super(CustomContentModule.this.core, name, "Open the CustomDialogs builder.");
            requirePermission(Permissions.CUSTOMDIALOGS_ADMIN);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            openDialogBuilder(sender.player().orElseThrow());
        }
    }

    private final class GuiAdminCommand extends MysticCommand {
        GuiAdminCommand(String name) {
            super(CustomContentModule.this.core, name, "Manage declarative CustomGUIs.");
            requirePermission(Permissions.CUSTOMGUIS_ADMIN);
            allowExtraArguments();
            if (!"customui".equalsIgnoreCase(name)) {
                addAliases("customui");
            }
        }

        @Override
        protected void run(MysticCommandSender sender) {
            handleGuiAdmin(sender, sender.args());
        }
    }

    private final class GuiAliasCommand extends MysticCommand {
        private final String guiId;

        GuiAliasCommand(String name, String guiId) {
            super(CustomContentModule.this.core, name, "Open the " + guiId + " CustomGUI.");
            this.guiId = guiId;
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            openGui(sender.player().orElseThrow(), guiId);
        }
    }
}
