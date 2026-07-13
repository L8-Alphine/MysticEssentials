package org.hyzionstudios.mysticessentials.modules.customcommands;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;

import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.command.system.CommandRegistration;

/**
 * Adapter around Hytale's command registration for the dynamic custom-command
 * stubs. Isolates every 0.5.6-specific assumption in one place:
 *
 * <ul>
 *   <li>One {@link DynamicCommandStub} is registered <b>per label</b> (primary
 *       name and each alias get their own stub, so the engine's alias table
 *       never has to be touched).</li>
 *   <li>Unregistration is real: {@code CommandRegistry.registerCommand}
 *       returns a {@link CommandRegistration} whose public
 *       {@code unregister()} removes the command from the engine's command
 *       and alias maps (verified against the 0.5.6 bytecode). The handle is
 *       one-shot — re-adding a label later registers a fresh stub.</li>
 *   <li>Stubs still resolve their definition at call time, so even a label
 *       whose unregistration was skipped (e.g. registry already shut down)
 *       degrades to a harmless "unknown custom command" reply.
 *       TODO(hytale-api): verify whether connected clients refresh
 *       tab-completion after late registration/unregistration or need to
 *       rejoin.</li>
 * </ul>
 */
public final class CustomCommandRegistrar {

    private final MysticCore core;
    private final CustomCommandsModule module;
    /** Labels we registered, with the engine handle used to unregister them. */
    private final Map<String, CommandRegistration> ourStubs = new ConcurrentHashMap<>();

    public CustomCommandRegistrar(MysticCore core, CustomCommandsModule module) {
        this.core = core;
        this.module = module;
    }

    /**
     * @return {@code true} if {@code label} is already a registered command
     *         (by name or alias) that we did not register ourselves — i.e. a
     *         native, Mystic Essentials core, or third-party command.
     */
    public boolean isTakenByOthers(String label) {
        String needle = label.toLowerCase(Locale.ROOT);
        if (ourStubs.containsKey(needle)) {
            return false;
        }
        try {
            Map<String, AbstractCommand> registered = CommandManager.get().getCommandRegistration();
            if (registered.containsKey(needle)) {
                return true;
            }
            for (AbstractCommand command : registered.values()) {
                Set<String> aliases = command.getAliases();
                if (aliases != null && aliases.stream().anyMatch(needle::equalsIgnoreCase)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            // Command manager not ready — err on the side of "free" so boot-time
            // registration is not blocked; validate reports conflicts on reload.
        }
        return false;
    }

    /**
     * Brings the registered stubs in line with {@code activeLabels}: registers
     * a stub for every new label and unregisters stubs whose label no longer
     * maps to a command.
     */
    public void syncStubs(Collection<String> activeLabels, Function<String, String> descriptionOf) {
        for (String label : activeLabels) {
            String needle = label.toLowerCase(Locale.ROOT);
            if (ourStubs.containsKey(needle)) {
                continue;
            }
            try {
                CommandRegistration registration = core.platform().registerCommand(
                        new DynamicCommandStub(core, module, needle, descriptionOf.apply(needle)));
                if (registration != null) {
                    ourStubs.put(needle, registration);
                } else {
                    core.log(Level.WARNING, "[customcommands] Engine rejected registration of '/"
                            + needle + "'.");
                }
            } catch (Throwable t) {
                core.log(Level.WARNING, "[customcommands] Could not register '/" + needle + "': " + t);
            }
        }
        for (String stale : Set.copyOf(ourStubs.keySet())) {
            if (!activeLabels.contains(stale)) {
                unregister(stale);
            }
        }
    }

    /** Unregisters one of our stubs through its engine registration handle. */
    private void unregister(String label) {
        CommandRegistration registration = ourStubs.remove(label);
        if (registration == null) {
            return;
        }
        try {
            registration.unregister();
            core.log(Level.INFO, "[customcommands] Unregistered '/" + label + "'.");
        } catch (Throwable t) {
            // The call-time lookup keeps a leftover stub harmless either way.
            core.log(Level.WARNING, "[customcommands] Could not unregister '/" + label + "': " + t);
        }
    }

    /** Labels currently backed by a registered stub. */
    public Set<String> registeredLabels() {
        return Set.copyOf(ourStubs.keySet());
    }

    /** Unregisters every stub (module disable), so a later re-enable starts clean. */
    public void unregisterAll() {
        for (String label : Set.copyOf(ourStubs.keySet())) {
            unregister(label);
        }
    }
}
