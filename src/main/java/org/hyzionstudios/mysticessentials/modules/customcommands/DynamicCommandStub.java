package org.hyzionstudios.mysticessentials.modules.customcommands;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

/**
 * The Hytale command registered for one custom-command label. Deliberately
 * knows nothing about the definition: it resolves the label against the live
 * registry <b>at call time</b>, so config reloads (changed actions, new
 * permissions) apply instantly with no registration churn; only genuinely
 * added/removed labels are registered/unregistered by the
 * {@link CustomCommandRegistrar}.
 *
 * <p>Arguments are free-form here; typed parsing happens in the module's own
 * {@code ArgumentParser} against the definition current at dispatch.</p>
 */
final class DynamicCommandStub extends MysticCommand {

    private final CustomCommandsModule module;
    private final String label;

    DynamicCommandStub(MysticCore core, CustomCommandsModule module, String label, String description) {
        super(core, label, description == null || description.isBlank()
                ? "Custom command." : description);
        this.module = module;
        this.label = label;
        allowExtraArguments();
    }

    @Override
    protected void run(MysticCommandSender sender) {
        module.dispatch(label, sender);
    }
}
