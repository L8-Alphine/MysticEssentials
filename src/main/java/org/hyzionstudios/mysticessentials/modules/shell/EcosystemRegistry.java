package org.hyzionstudios.mysticessentials.modules.shell;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.HytaleServer;

/**
 * Which Mystic Ecosystem mods are on this server, and what the menu should say
 * when one is not.
 *
 * <p>Detection is by plugin identifier only — no compile-time dependency, no
 * reflection into another mod's API, nothing that fails loudly when a mod is
 * absent. That keeps the bible's rule that no module may require
 * MysticEssentials, and its mirror: Essentials must not require any of them.
 * A mod that is present but not started reads as {@link Availability#INSTALLED}
 * rather than ready, so the card says "starting up" instead of offering an
 * action that would bounce.</p>
 *
 * <p>Third-party mods join the menu through
 * {@link ShellModule#registerDestination} rather than this list; this is only
 * the first-party ecosystem the bible enumerates.</p>
 */
public final class EcosystemRegistry {

    private static final String VENDOR = "org.hyzionstudios";

    /** How far along a module is, from the shell's point of view. */
    public enum Availability {
        /** Running and usable. */
        READY,
        /** Jar present but the plugin is not started yet. */
        INSTALLED,
        /** Not on the server at all. */
        ABSENT,
        /** Present, but the server hid it in config. */
        HIDDEN
    }

    /**
     * One first-party ecosystem module.
     *
     * @param id       plugin id under {@code org.hyzionstudios}
     * @param name     display name
     * @param glyph    one or two characters for the card's icon well
     * @param section  which menu section it belongs to
     * @param blurb    one line describing what it offers
     * @param command  the command the card runs, without a leading slash
     */
    public record EcosystemModule(String id, String name, String glyph, MenuSection section,
            String blurb, String command) {
    }

    /**
     * The ecosystem as the bible lists it (§1, §11), in the order the menu
     * offers them. MysticGeneration is creator tooling and sits under Staff;
     * everything else lands in the section that owns its task.
     */
    private static final List<EcosystemModule> ECOSYSTEM = List.of(
            new EcosystemModule("mysticquests", "Quests", "Q", MenuSection.ADVENTURE,
                    "Your journal, chapter progress and tracked objective.", "quests"),
            new EcosystemModule("mysticrpg", "Character", "R", MenuSection.ADVENTURE,
                    "Attributes, classes, skills and progression.", "rpg"),
            new EcosystemModule("mysticguilds", "Guilds", "G", MenuSection.COMMUNITY,
                    "Roster, ranks, claims and diplomacy.", "guild"),
            new EcosystemModule("mysticeconomy", "Economy", "$", MenuSection.SERVICES,
                    "Wallet, market, shops and trade.", "balance"),
            new EcosystemModule("mysticidentity", "Account", "I", MenuSection.ACCOUNT,
                    "Linking, profile and privacy.", "identity"),
            new EcosystemModule("mysticnametags", "Nameplates", "N", MenuSection.ACCOUNT,
                    "Tag style, badges and visibility.", "nametag"),
            new EcosystemModule("mysticmoderation", "Moderation", "M", MenuSection.STAFF,
                    "Reports, cases, evidence and sanctions.", "reports"),
            new EcosystemModule("mysticgeneration", "Generation", "E", MenuSection.STAFF,
                    "NPC, encounter and spawn-rule tooling.", "generation"));

    private final List<String> hidden;

    EcosystemRegistry(List<String> hiddenModuleIds) {
        this.hidden = hiddenModuleIds == null ? List.of()
                : hiddenModuleIds.stream().filter(Objects::nonNull)
                        .map(id -> id.toLowerCase(Locale.ROOT).trim()).toList();
    }

    /** @return every first-party ecosystem module, in menu order. */
    public List<EcosystemModule> modules() {
        return ECOSYSTEM;
    }

    /** @return the ecosystem modules that belong in one menu section. */
    public List<EcosystemModule> modulesIn(MenuSection section) {
        List<EcosystemModule> matching = new ArrayList<>();
        for (EcosystemModule module : ECOSYSTEM) {
            if (module.section() == section) {
                matching.add(module);
            }
        }
        return matching;
    }

    /**
     * How a module stands right now. Re-checked on every menu open rather than
     * cached: a mod can finish starting, or be unloaded, between two opens, and
     * a stale "ready" is the one answer the shell must never give.
     */
    public Availability availability(EcosystemModule module) {
        if (hidden.contains(module.id())) {
            return Availability.HIDDEN;
        }
        Object plugin = lookup(module.id());
        if (plugin == null) {
            return Availability.ABSENT;
        }
        return started(plugin) ? Availability.READY : Availability.INSTALLED;
    }

    /** @return a line the card can show for anything that is not {@link Availability#READY}. */
    public static String reason(Availability availability, String name) {
        return switch (availability) {
            case READY -> "";
            case INSTALLED -> name + " is still starting up";
            case ABSENT -> name + " is not installed on this server";
            case HIDDEN -> "Turned off by the server";
        };
    }

    /** @return a snapshot of every module's state, for {@code /mystic} diagnostics. */
    public Map<String, Availability> snapshot() {
        Map<String, Availability> states = new LinkedHashMap<>();
        for (EcosystemModule module : ECOSYSTEM) {
            states.put(module.id(), availability(module));
        }
        return states;
    }

    private static Object lookup(String id) {
        try {
            return HytaleServer.get().getPluginManager().getPlugin(new PluginIdentifier(VENDOR, id));
        } catch (Throwable ignored) {
            // No plugin manager yet, or the id is unknown: treat as absent.
            return null;
        }
    }

    /**
     * Whether a located plugin has actually started. {@code PluginBase} has no
     * stable "is enabled" accessor across 0.6/0.7, so this asks for one
     * reflectively and, finding none, falls back to "present means ready" —
     * the same fail-open stance the integration bridges take.
     */
    private static boolean started(Object plugin) {
        for (String method : new String[] {"isEnabled", "isStarted", "isRunning"}) {
            try {
                Object result = plugin.getClass().getMethod(method).invoke(plugin);
                if (result instanceof Boolean enabled) {
                    return enabled;
                }
            } catch (Throwable ignored) {
                // Try the next name.
            }
        }
        return true;
    }
}
