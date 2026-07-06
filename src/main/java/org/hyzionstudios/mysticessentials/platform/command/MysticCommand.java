package org.hyzionstudios.mysticessentials.platform.command;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;

import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;

/**
 * Base class for all Mystic Essentials commands. Adapts the Hytale
 * {@link AbstractCommand} contract to a simpler {@link #run(MysticCommandSender)}
 * method, wraps handler exceptions so a bug in one command cannot crash the
 * command pipeline, and integrates permission gating.
 *
 * <p>Subclasses call {@code requirePermission(...)} and {@code addAliases(...)}
 * in their constructor as needed (both inherited from {@link AbstractCommand}).</p>
 */
public abstract class MysticCommand extends AbstractCommand {

    protected final MysticCore core;

    protected MysticCommand(MysticCore core, String name, String description) {
        super(name, description);
        this.core = core;
    }

    /**
     * Constructor for <b>usage variants</b> — commands attached to a base
     * command via {@code addUsageVariant}. Hytale requires variants to be
     * nameless (description-only); the parser picks the variant whose
     * positional required-arg count matches the typed token count.
     */
    protected MysticCommand(MysticCore core, String description) {
        super(description);
        this.core = core;
    }

    /** Opts a command into manual/free-form argument parsing through {@link MysticCommandSender#args()}. */
    protected final void allowExtraArguments() {
        setAllowsExtraArguments(true);
    }

    @Override
    protected CompletableFuture<Void> execute(CommandContext context) {
        try {
            run(new MysticCommandSender(core, context));
        } catch (Throwable t) {
            core.log(Level.SEVERE, "Command '" + getName() + "' threw: " + t);
            context.sendMessage(core.getMessageService().format(
                    "&cAn internal error occurred while running that command."));
        }
        return CompletableFuture.completedFuture(null);
    }

    /** Handles the command. Implementations should be non-blocking. */
    protected abstract void run(MysticCommandSender sender);
}
