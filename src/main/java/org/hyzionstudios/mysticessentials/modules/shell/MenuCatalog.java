package org.hyzionstudios.mysticessentials.modules.shell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import org.hyzionstudios.mysticessentials.api.shell.MenuDestination;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.shell.EcosystemRegistry.Availability;
import org.hyzionstudios.mysticessentials.modules.shell.EcosystemRegistry.EcosystemModule;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Everything the main menu can offer, in one place.
 *
 * <p>Three sources feed it, in this order of precedence when ids collide:
 * Essentials' own modules, the first-party ecosystem detected by
 * {@link EcosystemRegistry}, and cards other mods registered through
 * {@code ShellService}. Nothing here is cached — a card's availability is a
 * live question (module enabled? plugin started? permission held?) and the
 * answer changes between two opens of the menu.</p>
 *
 * <p>Unavailable cards are kept and explained rather than dropped. A menu that
 * silently omits Guilds looks like a menu with no guild feature; one that says
 * "MysticGuilds is not installed on this server" is a menu that answered the
 * question.</p>
 */
final class MenuCatalog {

    /**
     * A rendered card.
     *
     * @param available whether pressing it will do anything
     * @param reason    why not, when it will not
     */
    record MenuCard(String id, MenuSection section, String title, String glyph, String blurb,
            int order, boolean available, String reason, Consumer<PlayerRef> action) {
    }

    private static final String CATEGORY = "Icons/ItemCategories/";
    private static final String MARKER = "UI/WorldMap/MapMarkers/";

    /**
     * Vanilla icons for the cards, by card id, as asset paths under
     * {@code Common/}. Cards missing here keep their letter mark: a letter that
     * says what a card is beats an icon that only looks tidy. Paths with spaces
     * in the file name (Builder Tools, Scripted Blocks) are avoided on purpose.
     */
    private static final java.util.Map<String, String> ICONS = java.util.Map.ofEntries(
            java.util.Map.entry("essentials:warps", CATEGORY + "Portal.png"),
            java.util.Map.entry("essentials:warps.adventure", CATEGORY + "Portal.png"),
            java.util.Map.entry("essentials:homes", MARKER + "Home.png"),
            java.util.Map.entry("essentials:spawn", MARKER + "Spawn.png"),
            java.util.Map.entry("essentials:kits", CATEGORY + "Items-Armor.png"),
            java.util.Map.entry("essentials:channels", CATEGORY + "Signs.png"),
            java.util.Map.entry("essentials:channels.settings", CATEGORY + "Signs.png"),
            java.util.Map.entry("essentials:tpa", MARKER + "Player.png"),
            java.util.Map.entry("essentials:playerwarps", MARKER + "Warp.png"),
            java.util.Map.entry("essentials:store", CATEGORY + "Natural-Ore.png"),
            java.util.Map.entry("essentials:servers", CATEGORY + "Natural.png"),
            java.util.Map.entry("essentials:wiki", CATEGORY + "Items-Recipe.png"),
            java.util.Map.entry("essentials:vaults", CATEGORY + "Container.png"),
            java.util.Map.entry("essentials:nick", CATEGORY + "Items-Clothes.png"),
            java.util.Map.entry("essentials:notifications", CATEGORY + "Build-Lights.png"),
            java.util.Map.entry("essentials:notifications.account", CATEGORY + "Build-Lights.png"),
            java.util.Map.entry("essentials:patchnotes", CATEGORY + "Misc.png"),
            java.util.Map.entry("essentials:shortcuts", CATEGORY + "Build-Walls.png"),
            java.util.Map.entry("essentials:shortcuts.settings", CATEGORY + "Build-Walls.png"),
            java.util.Map.entry("essentials:rail", CATEGORY + "Shelves.png"),
            java.util.Map.entry("ecosystem:mysticquests", MARKER + "Coordinate.png"),
            java.util.Map.entry("ecosystem:mysticrpg", CATEGORY + "Items-Weapons.png"),
            java.util.Map.entry("ecosystem:mysticguilds", CATEGORY + "Items-Shields.png"),
            java.util.Map.entry("ecosystem:mysticeconomy", CATEGORY + "Metal.png"),
            java.util.Map.entry("ecosystem:mysticmoderation", CATEGORY + "Editor.png"),
            java.util.Map.entry("ecosystem:mysticgeneration", CATEGORY + "Natural-Vegetal.png"));

    /** @return the card's vanilla icon, or empty for its letter mark. */
    static String iconFor(MenuCard card) {
        return card == null ? "" : ICONS.getOrDefault(card.id(), "");
    }

    private final MysticCore core;
    private final ShellModule shell;

    MenuCatalog(MysticCore core, ShellModule shell) {
        this.core = core;
        this.shell = shell;
    }

    /** @return the sections that have at least one card for this player, in rail order. */
    List<MenuSection> sectionsFor(PlayerRef player) {
        List<MenuSection> sections = new ArrayList<>();
        for (MenuSection section : MenuSection.values()) {
            if (section == MenuSection.OVERVIEW || !cards(player, section).isEmpty()) {
                sections.add(section);
            }
        }
        return sections;
    }

    /** @return the cards in one section, ordered, for this player. */
    List<MenuCard> cards(PlayerRef player, MenuSection section) {
        List<MenuCard> cards = new ArrayList<>();
        addBuiltins(player, section, cards);
        addEcosystem(section, cards);
        addRegistered(player, section, cards);
        cards.sort(Comparator.comparingInt(MenuCard::order).thenComparing(MenuCard::title));
        return cards;
    }

    // ----- Essentials' own modules -------------------------------------------

    private void addBuiltins(PlayerRef player, MenuSection section, List<MenuCard> cards) {
        switch (section) {
            case OVERVIEW -> {
                // "Quick resume and frequently used tools" (bible §11,
                // MysticEssentials): what a player reaches for most, not a
                // second copy of every section.
                command(cards, "essentials:mail", section, "Mail", "M",
                        "Read and send letters.", 10, "mail", "mail");
                command(cards, "essentials:notifications", section, "Notifications", "!",
                        "Everything the server told you.", 20, null, "notifications");
                command(cards, "essentials:warps", section, "Warps", ">",
                        "Jump to a public destination.", 30, "warps", "warps");
                page(cards, "essentials:shortcuts", section, "Shortcuts", "=",
                        "The nine slots beside your chat.", 40, true, "",
                        shell::openShortcuts);
            }
            case ADVENTURE -> {
                command(cards, "essentials:warps.adventure", section, "Warps", ">",
                        "Public destinations you can reach.", 10, "warps", "warps");
                command(cards, "essentials:homes", section, "Homes", "H",
                        "Your own saved places.", 20, "teleportation", "homes");
                command(cards, "essentials:spawn", section, "Spawn", "O",
                        "Return to the server spawn.", 30, "spawn", "spawn");
                command(cards, "essentials:kits", section, "Kits", "K",
                        "Claim a kit you are entitled to.", 40, "kits", "kits");
            }
            case COMMUNITY -> {
                command(cards, "essentials:channels", section, "Chat Channels", "C",
                        "Choose where you are speaking.", 10, "chat", "channels");
                command(cards, "essentials:mail.community", section, "Mail", "M",
                        "Read and send letters.", 20, "mail", "mail");
                command(cards, "essentials:tpa", section, "Teleport Requests", "TP",
                        "Ask to join someone, or invite them.", 30, "teleportation", "tpa");
                command(cards, "essentials:playerwarps", section, "Player Warps", "PW",
                        "Places other players opened up.", 40, "warps", "pwarp");
            }
            case SERVICES -> {
                slotCard(cards, "essentials:store", section, "Server Store", "$",
                        "Support the server.", 10, ShortcutKind.STORE,
                        "The server has not set up a store yet");
                slotCard(cards, "essentials:servers", section, "Server Browser", "B",
                        "Move to another world.", 20, ShortcutKind.SERVERS,
                        "No server browser is configured");
                page(cards, "essentials:wiki", section, "Server Wiki", "W",
                        "How this server works, written down.", 30,
                        core.getModuleManager().isEnabled("wiki"),
                        "The server wiki is turned off on this server",
                        shell::openWiki);
                command(cards, "essentials:vaults", section, "Player Vaults", "V",
                        "Your extra storage.", 40, "playervaults", "vault");
            }
            case ACCOUNT -> {
                command(cards, "essentials:nick", section, "Nickname", "NK",
                        "How your name appears.", 10, "nick", "nick");
                command(cards, "essentials:notifications.account", section, "Notifications", "!",
                        "What the server has told you.", 20, null, "notifications");
                command(cards, "essentials:patchnotes", section, "Patch Notes", "PN",
                        "What changed on this server.", 30, "patchnotes", "patchnotes");
            }
            case SETTINGS -> {
                page(cards, "essentials:shortcuts.settings", section, "Shortcuts", "=",
                        "The nine slots beside your chat.", 10, true, "", shell::openShortcuts);
                page(cards, "essentials:rail", section, "Shortcut Rail", "RL",
                        shell.railVisible(player) ? "Showing beside chat. Press to hide."
                                : "Hidden. Press to show it again.",
                        20, shell.config().rail.enabled,
                        "The rail is turned off on this server",
                        viewer -> shell.setRailVisible(viewer, !shell.railVisible(viewer)));
                command(cards, "essentials:channels.settings", section, "Chat Channels", "C",
                        "Choose where you are speaking.", 30, "chat", "channels");
            }
            case STAFF -> {
                // Staff cards come from the ecosystem; Essentials' own admin
                // tools stay on their commands, where their permission checks
                // already live.
            }
        }
    }

    // ----- First-party ecosystem ---------------------------------------------

    private void addEcosystem(MenuSection section, List<MenuCard> cards) {
        EcosystemRegistry registry = shell.ecosystem();
        for (EcosystemModule module : registry.modulesIn(section)) {
            Availability availability = registry.availability(module);
            if (availability == Availability.HIDDEN) {
                continue;
            }
            boolean ready = availability == Availability.READY;
            cards.add(new MenuCard("ecosystem:" + module.id(), section, module.name(),
                    module.glyph(), module.blurb(), 100,
                    ready, EcosystemRegistry.reason(availability, module.name()),
                    player -> core.platform().dispatchPlayerCommand(player, module.command())));
        }
    }

    // ----- Cards other mods registered ---------------------------------------

    private void addRegistered(PlayerRef player, MenuSection section, List<MenuCard> cards) {
        for (MenuDestination destination : shell.destinations()) {
            if (MenuSection.parse(destination.section().name()) != section) {
                continue;
            }
            if (!safe(destination.visibleTo(), player, false)) {
                continue;
            }
            boolean available = safe(destination.availableTo(), player, false);
            cards.add(new MenuCard(destination.id(), section, destination.title(),
                    destination.glyph(), destination.blurb(), 200 + destination.order(),
                    available,
                    available ? "" : blankTo(destination.unavailableReason(), "Not available right now"),
                    destination.action()));
        }
    }

    /**
     * Another mod's predicate runs inside our menu render. One that throws
     * must not take the whole menu down with it, so a failure reads as "not
     * available" and is logged once by the caller's own logger.
     */
    private boolean safe(java.util.function.Predicate<PlayerRef> predicate, PlayerRef player,
            boolean onFailure) {
        try {
            return predicate.test(player);
        } catch (Throwable t) {
            core.log(java.util.logging.Level.WARNING,
                    "A menu destination's predicate failed: " + t);
            return onFailure;
        }
    }

    // ----- Card builders -----------------------------------------------------

    /** A card backed by an Essentials command, gated on its module being enabled. */
    private void command(List<MenuCard> cards, String id, MenuSection section, String title,
            String glyph, String blurb, int order, String moduleId, String command) {
        boolean enabled = moduleId == null || core.getModuleManager().isEnabled(moduleId);
        cards.add(new MenuCard(id, section, title, glyph, blurb, order, enabled,
                enabled ? "" : title + " is turned off on this server",
                player -> core.platform().dispatchPlayerCommand(player, command)));
    }

    /** A card that opens one of the shell's own pages. */
    private void page(List<MenuCard> cards, String id, MenuSection section, String title,
            String glyph, String blurb, int order, boolean available, String reason,
            Consumer<PlayerRef> action) {
        cards.add(new MenuCard(id, section, title, glyph, blurb, order, available, reason, action));
    }

    /** A card that follows whatever the matching rail slot is pointed at. */
    private void slotCard(List<MenuCard> cards, String id, MenuSection section, String title,
            String glyph, String blurb, int order, ShortcutKind kind, String missing) {
        String target = shell.targetFor(kind);
        boolean available = !target.isEmpty();
        cards.add(new MenuCard(id, section, title, glyph, blurb, order, available,
                available ? "" : missing,
                player -> shell.runTarget(player, kind, target)));
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
