package org.hyzionstudios.mysticessentials.core.license;

import java.util.List;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

/**
 * {@code /mystic license [reload]} - shows what the current license grants, and
 * re-reads {@code license.key} on request.
 *
 * <p>The reload path lets an operator add or replace their key without a
 * restart. The answer comes from the licensing service a moment later, so the
 * reload reply does not wait for it: the result is logged, and the next
 * {@code /mystic license} shows it. Renewals, upgrades and lapses otherwise
 * arrive on their own.
 */
public final class LicenseCommand extends MysticCommand {

    private final EssentialsLicense license;

    public LicenseCommand(MysticCore core, EssentialsLicense license) {
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
            sender.reply("&aRe-reading " + LicenseSupport.KEY_FILE + "; checking it with the licensing service.");
            sender.reply("&7Run &f/mystic license &7in a moment to see the result, then &f/mystic reload"
                    + " &7to start or stop the modules it affects.");
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
        String serverId = license.serverId();
        sender.reply("&7Server licensing id: &f" + (serverId == null ? "none yet" : serverId));

        List<String> granted = license.licensedFeatures();
        sender.reply(granted.isEmpty()
                ? "&7Licensed features: &fnone"
                : "&7Licensed features: &f" + String.join(", ", granted));

        if (!license.isLicensed()) {
            sender.reply("&7Everything that is not a licensed feature keeps working normally.");
        }
    }
}
