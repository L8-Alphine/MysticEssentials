package org.hyzionstudios.mysticessentials.modules.shell;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.MysticEssentialsAPI;
import org.hyzionstudios.mysticessentials.api.shell.MenuDestination;
import org.hyzionstudios.mysticessentials.api.shell.ShellService;
import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.modules.shell.MenuCatalog.MenuCard;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * The navigation shell: the shortcut rail beside chat and the main menu.
 *
 * <h2>What the engine allows, and what this module does about it</h2>
 *
 * <p>The design bible asks for ALT+1–9 (§8). On this engine a server-side mod
 * cannot bind a client key: there is no keybind registry in the server API, the
 * client's binding table is not asset-driven, and the protocol's one key event
 * ({@code CustomUIEventBindingType.KeyDown}) is a page binding that fires only
 * while a page is open and focused. A HUD overlay, meanwhile, carries no event
 * bindings at all, so the rail cannot be clicked either.</p>
 *
 * <p>So the rail is a display and activation comes from three places that do
 * work today: {@code /1} … {@code /9}, the Shortcuts page (same nine slots as
 * real buttons), and {@link #activateSlot} for anything else. All three funnel
 * through one method, so the day the engine grows a keybind the change is a
 * single new caller — and {@link #railHint()} already refuses to advertise a
 * shortcut that does not exist on this build.</p>
 */
public final class ShellModule extends AbstractMysticModule implements ShellService {

    private static final Type HIDDEN_RAILS_TYPE = new TypeToken<HashSet<String>>() {
    }.getType();

    /**
     * Players whose rail setting differs from {@code rail.visibleByDefault} —
     * not "players who hid it". Storing the difference rather than the value
     * means changing the server default moves everyone who never chose, and
     * leaves everyone who did.
     */
    private final Set<UUID> railOverrides = ConcurrentHashMap.newKeySet();

    /** Cards other mods registered, in registration order. */
    private final Map<String, MenuDestination> destinations = new LinkedHashMap<>();

    /**
     * The slots last drawn for each player, so the refresh tick only re-sends
     * the rail when something actually changed — a guild joined, a channel
     * switched, a module disabled by {@code /mystic reload}.
     */
    private final Map<UUID, List<ShortcutSlot>> rendered = new ConcurrentHashMap<>();

    private ShellConfig config = new ShellConfig();
    private ShortcutBar bar;
    /**
     * Registered once for the plugin's lifetime: the entity-store registry
     * rejects a second registration of the same system class, so a module that
     * is hot-disabled and re-enabled reuses these and gates them instead.
     */
    private AltShortcuts altShortcuts;
    private volatile boolean altActive;
    private MenuCatalog catalog;
    private EcosystemRegistry ecosystem;
    private java.util.concurrent.ScheduledFuture<?> refreshTask;

    public ShellModule() {
        super("shell", "Navigation Shell", "1.0.0");
    }

    @Override
    public List<String> softDependencies() {
        // The shell works without either: chat decides whether slots 1–4 can
        // do anything, and the wiki decides whether slot 6 can. Both are
        // re-checked per render, so enabling one later needs no restart.
        return List.of("chat", "wiki");
    }

    @Override
    public void onLoad(MysticEssentialsAPI api) {
        super.onLoad(api);
    }

    @Override
    public void onEnable() {
        config = core.configManager().loadModuleConfig(id(), ShellConfig.class, new ShellConfig());
        normalize();
        bar = new ShortcutBar(core, this);
        catalog = new MenuCatalog(core, this);
        ecosystem = new EcosystemRegistry(config.menu.hiddenModules);
        loadRailPreferences();

        registerCommand(new MenuCommand());
        registerCommand(new ShortcutsCommand());
        registerNumberCommands();
        installAltShortcuts();

        // The rail is appended on join. PlayerReadyEvent is keyed, so it needs
        // a global registration to arrive at all; a plain registerEvent never
        // sees it.
        registerGlobalEvent(PlayerReadyEvent.class,
                (PlayerReadyEvent event) -> core.platform().playerRefOf(event.getPlayerRef())
                        .ifPresent(this::showRail));
        registerEvent(PlayerDisconnectEvent.class, (PlayerDisconnectEvent event) -> {
            rendered.remove(event.getPlayerRef().getUuid());
            if (altShortcuts != null) {
                altShortcuts.forget(event.getPlayerRef().getUuid());
            }
        });

        startRefresh();
        publishWikiEntry();
        log("Enabled: rail=" + config.rail.enabled + ", menu=" + config.menu.enabled
                + ", slots=" + config.slots.size() + ", altShortcuts=" + altActive);
    }

    @Override
    public void onReload() {
        config = core.configManager().loadModuleConfig(id(), ShellConfig.class, new ShellConfig());
        normalize();
        ecosystem = new EcosystemRegistry(config.menu.hiddenModules);
        altActive = config.keys.altShortcuts && altShortcuts != null;
        rendered.clear();
        stopRefresh();
        startRefresh();
        // A reload can change the whole rail, so redraw it rather than wait for
        // the tick: a rail still offering a slot the server just removed is the
        // one state that must not survive a reload.
        for (PlayerRef player : core.platform().onlinePlayers()) {
            showRail(player);
        }
    }

    /**
     * Puts the shortcut reference in the server wiki, written per reader so it
     * lists the slots <em>that</em> player can actually use. Also the shell's
     * own proof that the contributed-entry API works: nothing here is stored,
     * and the page leaves with the module.
     */
    private void publishWikiEntry() {
        org.hyzionstudios.mysticessentials.api.wiki.WikiService wiki = core.getWikiService();
        if (wiki == null) {
            return;
        }
        wiki.registerEntry(new org.hyzionstudios.mysticessentials.api.wiki.WikiEntry(
                "mysticessentials:shortcuts", "Shortcuts", "Getting Started",
                "The nine slots beside your chat.", List.of("shortcut", "rail", "chat", "channel"),
                10, null, this::shortcutReference));
    }

    /** The shortcut reference, written for one reader. */
    private String shortcutReference(PlayerRef reader) {
        StringBuilder body = new StringBuilder();
        body.append("## Your shortcuts\n")
                .append("Nine slots sit beside your chat panel. ")
                .append(activationHint())
                .append("\n\n");
        for (ShortcutSlot slot : bar.resolve(reader)) {
            body.append("- /").append(slot.index()).append(" — ").append(slot.label())
                    .append(": ").append(slot.stateLine()).append('\n');
        }
        body.append("\n## Hiding the rail\n")
                .append("Run /shortcuts rail, or open the Shortcuts page and press Hide Rail. ")
                .append("Your choice is remembered between sessions.\n");
        return body.toString();
    }

    @Override
    public void onDisable() {
        altActive = false;
        org.hyzionstudios.mysticessentials.api.wiki.WikiService wiki = core.getWikiService();
        if (wiki != null) {
            wiki.unregisterNamespace("mysticessentials");
        }
        stopRefresh();
        saveRailPreferences();
        for (PlayerRef player : core.platform().onlinePlayers()) {
            core.platform().removeHud(player, ShortcutRailHud.KEY);
        }
        rendered.clear();
        destinations.clear();
    }

    /** Fills in anything the config file left blank or out of range. */
    private void normalize() {
        if (config.rail == null) {
            config.rail = new ShellConfig.Rail();
        }
        if (config.menu == null) {
            config.menu = new ShellConfig.Menu();
        }
        if (config.slots == null) {
            config.slots = new ArrayList<>();
        }
        if (config.rail.separatorsAfter == null) {
            config.rail.separatorsAfter = new ArrayList<>();
        }
        if (config.menu.hiddenModules == null) {
            config.menu.hiddenModules = new ArrayList<>();
        }
        if (config.keys == null) {
            config.keys = new ShellConfig.Keys();
        }
    }

    /** Registers the ALT chord systems once, then follows the config switch. */
    private void installAltShortcuts() {
        if (altShortcuts == null) {
            AltShortcuts candidate = new AltShortcuts(this);
            boolean walk = core.platform().registerEntitySystem(candidate.walkSystem());
            boolean slot = walk && core.platform().registerEntitySystem(candidate.slotSystem());
            if (walk && slot) {
                altShortcuts = candidate;
            } else {
                log("Could not install the ALT shortcut listeners; /1-/9 and the Shortcuts page still work.");
            }
        }
        altActive = config.keys.altShortcuts && altShortcuts != null;
    }

    /** Whether ALT+1-9 should act right now. Read on world threads every tick. */
    boolean altShortcutsActive() {
        return altActive;
    }

    // ----- Accessors used by the rail, pages and catalog ---------------------

    ShellConfig config() {
        return config;
    }

    MenuCatalog catalog() {
        return catalog;
    }

    EcosystemRegistry ecosystem() {
        return ecosystem;
    }

    List<ShortcutSlot> slotsFor(PlayerRef player) {
        return bar.resolve(player);
    }

    /** @return the server name shown in the menu's context strip. */
    String serverTitle() {
        return config.menu.serverTitle == null ? "" : config.menu.serverTitle.trim();
    }

    /**
     * What the rail's footer says. Written from what is actually live, so it
     * never advertises a shortcut that is switched off.
     */
    String railHint() {
        String configured = config.rail.hint;
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return altActive ? "Alt+#" : "/1 – /9";
    }

    /** The key a row shows for one slot. */
    String keyLabel(int index) {
        return altActive ? "Alt+" + index : "/" + index;
    }

    /** The same point, as a sentence for a page. */
    String activationHint() {
        String configured = config.rail.hint;
        if (configured != null && !configured.isBlank()) {
            return "Use " + configured.trim() + " to reach a slot.";
        }
        return altActive
                ? "Hold Alt and press 1 to 9, type /1 to /9 in chat, or press a row here."
                : "Type /1 to /9 in chat, or press a row here.";
    }

    /** One line on the ecosystem, for the menu's footer. */
    String ecosystemSummary() {
        int ready = 0;
        int total = 0;
        for (Map.Entry<String, EcosystemRegistry.Availability> entry : ecosystem.snapshot().entrySet()) {
            if (entry.getValue() == EcosystemRegistry.Availability.HIDDEN) {
                continue;
            }
            total++;
            if (entry.getValue() == EcosystemRegistry.Availability.READY) {
                ready++;
            }
        }
        return ready + " of " + total + " Mystic modules connected.";
    }

    void tell(PlayerRef player, String message) {
        core.getMessageService().send(player, "&7" + message);
    }

    // ----- ShellService ------------------------------------------------------

    @Override
    public boolean registerDestination(MenuDestination destination) {
        if (destination == null) {
            return false;
        }
        destinations.put(destination.id(), destination);
        return true;
    }

    @Override
    public boolean unregisterDestination(String id) {
        return id != null && destinations.remove(id) != null;
    }

    @Override
    public List<MenuDestination> destinations() {
        return List.copyOf(destinations.values());
    }

    @Override
    public void openMenu(PlayerRef player) {
        if (!config.menu.enabled) {
            tell(player, "The menu is turned off on this server.");
            return;
        }
        core.platform().openPage(player, new ShellPages.MainMenuPage(core, this, player));
    }

    @Override
    public void openShortcuts(PlayerRef player) {
        core.platform().openPage(player, new ShellPages.ShortcutBarPage(core, this, player));
    }

    @Override
    public boolean railVisible(PlayerRef player) {
        if (!config.rail.enabled) {
            return false;
        }
        return config.rail.visibleByDefault != railOverrides.contains(player.getUuid());
    }

    @Override
    public void setRailVisible(PlayerRef player, boolean visible) {
        boolean overridden = config.rail.visibleByDefault != visible;
        if (overridden) {
            railOverrides.add(player.getUuid());
        } else {
            railOverrides.remove(player.getUuid());
        }
        saveRailPreferences();
        showRail(player);
    }

    /**
     * Runs one slot. The single entry point for every input source — command,
     * page row, or another mod — so authorisation and the unavailable reasons
     * are decided in exactly one place.
     */
    @Override
    public boolean activateSlot(PlayerRef player, int index) {
        Optional<ShortcutSlot> resolved = bar.resolve(player, index);
        if (resolved.isEmpty()) {
            tell(player, "Shortcuts are numbered 1 to 9.");
            return false;
        }
        ShortcutSlot slot = resolved.get();
        if (!slot.available()) {
            tell(player, slot.label() + ": " + slot.reason());
            return false;
        }
        switch (slot.kind()) {
            case CHANNEL -> {
                org.hyzionstudios.mysticessentials.api.service.ChatService chat = core.getChatService();
                if (chat == null || !chat.setChannel(player.getUuid(), slot.target())) {
                    tell(player, "That channel is not available right now.");
                    return false;
                }
                tell(player, "Now speaking in " + slot.label() + ".");
                refreshRail(player);
            }
            case MENU -> openMenu(player);
            case WIKI -> openWiki(player);
            case WARPS -> core.platform().dispatchPlayerCommand(player, blankTo(slot.target(), "warps"));
            case STORE, SERVERS, COMMAND -> runTarget(player, slot.kind(), slot.target());
            case NONE -> {
                tell(player, "Nothing is assigned to slot " + index + ".");
                return false;
            }
        }
        return true;
    }

    /**
     * Opens the built-in wiki. Dispatched as a command rather than called
     * directly so the wiki module owns its own permission check and the shell
     * keeps no compile-time dependency on it.
     */
    void openWiki(PlayerRef player) {
        if (!core.getModuleManager().isEnabled("wiki")) {
            tell(player, "The server wiki is turned off on this server.");
            return;
        }
        core.platform().dispatchPlayerCommand(player, "wiki");
    }

    /** @return what the rail slot of this kind is pointed at, or empty when none is. */
    String targetFor(ShortcutKind kind) {
        for (ShellConfig.Slot slot : config.slots) {
            if (slot != null && slot.enabled && ShortcutKind.parse(slot.kind) == kind) {
                return slot.target == null ? "" : slot.target.trim();
            }
        }
        return "";
    }

    /**
     * Follows a configured target: a URL becomes a clickable chat line (the
     * client cannot be told to open a browser, but it can render a link), and
     * anything else runs as a command in the player's own name, so the
     * command's permission check is the authorisation.
     */
    void runTarget(PlayerRef player, ShortcutKind kind, String target) {
        String value = target == null ? "" : target.trim();
        if (value.isEmpty()) {
            tell(player, "That shortcut has nothing set.");
            return;
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            core.getMessageService().send(player,
                    "&7" + labelFor(kind) + ": <a href=\"" + value + "\">" + value + "</a>");
            return;
        }
        core.platform().dispatchPlayerCommand(player, value.startsWith("/") ? value.substring(1) : value);
    }

    /** Runs a menu card, guarding against a card whose owner threw. */
    void runCard(PlayerRef player, MenuCard card) {
        try {
            card.action().accept(player);
        } catch (Throwable t) {
            core.log(Level.WARNING, "Menu card '" + card.id() + "' failed: " + t);
            tell(player, "That did not open. The server owner has been told.");
        }
    }

    private static String labelFor(ShortcutKind kind) {
        return switch (kind) {
            case STORE -> "Server store";
            case SERVERS -> "Server browser";
            default -> "Shortcut";
        };
    }

    // ----- The rail ----------------------------------------------------------

    /** Draws or removes the rail for one player, per their own preference. */
    void showRail(PlayerRef player) {
        if (!railVisible(player)) {
            core.platform().removeHud(player, ShortcutRailHud.KEY);
            rendered.remove(player.getUuid());
            return;
        }
        List<ShortcutSlot> slots = bar.resolve(player);
        rendered.put(player.getUuid(), slots);
        core.platform().showHud(player, new ShortcutRailHud(player, this, slots));
    }

    /** Redraws the rail when what it says has changed. */
    private void refreshRail(PlayerRef player) {
        if (!railVisible(player)) {
            return;
        }
        List<ShortcutSlot> slots = bar.resolve(player);
        if (slots.equals(rendered.get(player.getUuid()))) {
            return;
        }
        rendered.put(player.getUuid(), slots);
        core.platform().showHud(player, new ShortcutRailHud(player, this, slots));
    }

    /**
     * A slow sweep that catches changes the shell cannot observe directly: a
     * channel switched with {@code /channel}, a guild joined in another mod, a
     * permission revoked. Nothing is sent unless the resolved slots differ from
     * what that player was last shown.
     */
    private void startRefresh() {
        if (!config.rail.enabled) {
            return;
        }
        refreshTask = core.scheduler().runRepeating(() -> {
            for (PlayerRef player : core.platform().onlinePlayers()) {
                try {
                    refreshRail(player);
                } catch (Throwable t) {
                    core.log(Level.WARNING, "Shortcut rail refresh failed for "
                            + player.getUsername() + ": " + t);
                }
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    private void stopRefresh() {
        if (refreshTask == null) {
            return;
        }
        refreshTask.cancel(false);
        refreshTask = null;
    }

    // ----- Per-player rail preference ----------------------------------------

    private Path railFile() {
        return core.paths().moduleDataDir(id()).resolve("rail.json");
    }

    private void loadRailPreferences() {
        railOverrides.clear();
        try {
            JsonElement element = Json.readFile(railFile());
            Set<String> stored = element == null ? null : Json.gson().fromJson(element, HIDDEN_RAILS_TYPE);
            if (stored == null) {
                return;
            }
            for (String raw : stored) {
                try {
                    railOverrides.add(UUID.fromString(raw));
                } catch (IllegalArgumentException ignored) {
                    // A hand-edited file with a bad id: skip that entry only.
                }
            }
        } catch (Exception e) {
            log("Failed to load rail preferences: " + e.getMessage());
        }
    }

    private void saveRailPreferences() {
        try {
            Json.writeFile(railFile(), railOverrides.stream().map(UUID::toString).toList());
        } catch (Exception e) {
            log("Failed to save rail preferences: " + e.getMessage());
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    // ----- Commands ----------------------------------------------------------

    /**
     * {@code /1} … {@code /9}. Registered one per slot rather than as
     * {@code /sc <n>} because a single keystroke plus Enter is the closest this
     * engine gets to the bible's ALT+N, and that difference is the whole point
     * of the rail. A name the command system refuses is logged and skipped —
     * {@code /shortcuts} still reaches every slot.
     */
    private void registerNumberCommands() {
        for (int index = 1; index <= 9; index++) {
            try {
                registerCommand(new SlotCommand(index));
            } catch (Throwable t) {
                log("Could not register /" + index + " (" + t + "); use /shortcuts " + index);
            }
        }
    }

    private final class SlotCommand extends MysticCommand {
        private final int index;

        SlotCommand(int index) {
            super(ShellModule.this.core, String.valueOf(index), "Shortcut slot " + index + ".");
            this.index = index;
        }

        @Override
        protected void run(MysticCommandSender sender) {
            sender.player().ifPresentOrElse(
                    player -> activateSlot(player, index),
                    () -> sender.reply("&cShortcuts are for players."));
        }
    }

    private final class MenuCommand extends MysticCommand {
        MenuCommand() {
            super(ShellModule.this.core, "menu", "Open the Mystic menu.");
            addAliases("mysticmenu");
        }

        @Override
        protected void run(MysticCommandSender sender) {
            sender.player().ifPresentOrElse(
                    ShellModule.this::openMenu,
                    () -> sender.reply("&cThe menu is for players."));
        }
    }

    /**
     * {@code /shortcuts} opens the page; {@code /shortcuts <1-9>} runs a slot;
     * {@code /shortcuts rail} toggles the overlay. Free-form arguments rather
     * than subcommands so a bare number keeps working even where {@code /1} did
     * not register.
     */
    private final class ShortcutsCommand extends MysticCommand {
        ShortcutsCommand() {
            super(ShellModule.this.core, "shortcuts", "Your nine chat-side shortcuts.");
            addAliases("sc");
            allowExtraArguments();
        }

        @Override
        protected void run(MysticCommandSender sender) {
            Optional<PlayerRef> viewer = sender.player();
            if (viewer.isEmpty()) {
                sender.reply("&cShortcuts are for players.");
                return;
            }
            PlayerRef player = viewer.get();
            String argument = sender.arg(0).map(value -> value.toLowerCase(Locale.ROOT)).orElse("");
            switch (argument) {
                case "" -> openShortcuts(player);
                case "rail", "toggle" -> {
                    setRailVisible(player, !railVisible(player));
                    tell(player, railVisible(player) ? "Shortcut rail shown." : "Shortcut rail hidden.");
                }
                default -> {
                    try {
                        activateSlot(player, Integer.parseInt(argument));
                    } catch (NumberFormatException e) {
                        sender.reply("&cUsage: /shortcuts [1-9|rail]");
                    }
                }
            }
        }
    }
}
