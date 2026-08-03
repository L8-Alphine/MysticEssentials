package org.hyzionstudios.mysticessentials.core.license;

import java.util.List;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.mysticlicensing.license.LicenseGate;
import com.mysticlicensing.license.Products;

/**
 * {@code /mystic license [reload]} - shows what the current license grants, and
 * re-reads it from disk on request.
 *
 * <p>The reload path exists so an operator who has just renewed can drop the new
 * file in and pick it up without a restart. Verification is otherwise done once,
 * at startup, and never on a timer.
 */
public final class LicenseCommand extends MysticCommand {

    private final LicenseGate license;

    public LicenseCommand(MysticCore core, LicenseGate license) {
        super(core, "license", "Show or reload the Mystic Essentials license.");
        requirePermission(Permissions.LICENSE);
        allowExtraArguments();
        this.license = license;
    }

    @Override
    protected void run(MysticCommandSender sender) {
        // The raw tokens still carry the root command and subcommand names, so
        // the action is whatever follows "license".
        if (isReload(sender.args())) {
            license.reload();
            sender.reply("&aLicense re-read from disk.");
            report(sender);
            if (!license.hasFeature(Products.Essentials.MODULE_CUSTOM_CONTENT)) {
                return;
            }
            sender.reply("&7Run &f/mystic reload &7to start any module the new license unlocked.");
            return;
        }

        report(sender);
    }

    /** True when the tokens after "license" ask for a reload. */
    private static boolean isReload(String[] raw) {
        for (int i = 0; i < raw.length; i++) {
            if (raw[i].replaceFirst("^/+", "").equalsIgnoreCase("license")) {
                return i + 1 < raw.length && raw[i + 1].equalsIgnoreCase("reload");
            }
        }
        return false;
    }

    private void report(MysticCommandSender sender) {
        sender.reply("&6" + license.summaryLine());
        sender.reply("&7Server licensing id: &f" + license.serverUuid());

        List<String> granted = license.licensedFeatures(Products.Essentials.ALL);
        sender.reply(granted.isEmpty()
                ? "&7Licensed features: &fnone"
                : "&7Licensed features: &f" + String.join(", ", granted));

        if (!license.isValid()) {
            sender.reply("&7Everything that is not a licensed feature keeps working normally.");
        }
    }
}
