package org.hyzionstudios.mysticessentials.core.migration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

/** In-game migration command for importing data files from legacy essentials mods. */
public final class MigrationCommand extends MysticCommand {

    private static final List<String> ROOT_COMMANDS = List.of("mystic", "mysticessentials", "me");

    public MigrationCommand(MysticCore core) {
        super(core, "migrate", "Scan or import legacy essentials data files.");
        requirePermission(Permissions.MIGRATE);
        allowExtraArguments();
    }

    @Override
    protected void run(MysticCommandSender sender) {
        List<String> args = migrationArgs(sender.args());
        if (args.isEmpty() || isHelp(args.get(0))) {
            sendHelp(sender);
            return;
        }

        String action = args.get(0).toLowerCase(Locale.ROOT);
        if (!action.equals("scan") && !action.equals("import")) {
            sender.reply("&cUnknown migration action: &f" + args.get(0));
            sendHelp(sender);
            return;
        }
        if (args.size() < 2) {
            sender.reply("&cUsage: &f/mystic migrate " + action + " <source> [path] [--replace] [--dry-run]");
            return;
        }

        LegacyMigrationService.Source source = LegacyMigrationService.Source.from(args.get(1));
        if (source == null) {
            sender.reply("&cUnknown source: &f" + args.get(1));
            sender.reply("&7Sources: &fauto, essentialsplus, hyssentials, eliteessentials, essentials, hyessentialsx");
            return;
        }

        boolean dryRun = action.equals("scan") || args.stream().anyMatch("--dry-run"::equalsIgnoreCase);
        boolean replace = args.stream().anyMatch("--replace"::equalsIgnoreCase);
        String pathText = pathArgument(args.subList(2, args.size()));
        Path sourcePath = pathText == null ? null : Path.of(pathText);

        LegacyMigrationService service = new LegacyMigrationService(core);
        try {
            LegacyMigrationService.Report report = service.run(new LegacyMigrationService.Options(
                    source, sourcePath, dryRun, replace));
            sendReport(sender, report);
        } catch (Exception e) {
            core.log(java.util.logging.Level.WARNING, "Migration failed: " + e.getMessage());
            sender.reply("&cMigration failed: &f" + e.getMessage());
        }
    }

    private static boolean isHelp(String token) {
        String normalized = token.toLowerCase(Locale.ROOT);
        return normalized.equals("help") || normalized.equals("?") || normalized.equals("--help");
    }

    private static List<String> migrationArgs(String[] raw) {
        List<String> tokens = new ArrayList<>(Arrays.asList(raw));
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).replaceFirst("^/+", "").equalsIgnoreCase("migrate")) {
                return new ArrayList<>(tokens.subList(i + 1, tokens.size()));
            }
        }
        if (!tokens.isEmpty() && ROOT_COMMANDS.contains(tokens.get(0).replaceFirst("^/+", "")
                .toLowerCase(Locale.ROOT))) {
            tokens.remove(0);
        }
        return tokens;
    }

    private static String pathArgument(List<String> tokens) {
        List<String> path = new ArrayList<>();
        for (String token : tokens) {
            if (!token.startsWith("--")) {
                path.add(token);
            }
        }
        if (path.isEmpty()) {
            return null;
        }
        String joined = String.join(" ", path).trim();
        if ((joined.startsWith("\"") && joined.endsWith("\""))
                || (joined.startsWith("'") && joined.endsWith("'"))) {
            return joined.substring(1, joined.length() - 1);
        }
        return joined;
    }

    private void sendHelp(MysticCommandSender sender) {
        sender.reply("&bMystic Essentials migration");
        sender.reply("&7Scan first, then import when the counts look right.");
        sender.reply("&f/mystic migrate scan <source> [path]");
        sender.reply("&f/mystic migrate import <source> [path] [--replace] [--dry-run]");
        sender.reply("&7Sources: &fauto, essentialsplus, hyssentials, eliteessentials, essentials, hyessentialsx");
        sender.reply("&7If path is omitted, Mystic checks sibling folders under the server mods directory.");
    }

    private void sendReport(MysticCommandSender sender, LegacyMigrationService.Report report) {
        sender.reply((report.dryRun() ? "&bMigration scan complete" : "&aMigration import complete")
                + " &7(" + report.source().id() + ")");
        sender.reply("&7Path: &f" + report.root());
        sender.reply("&7Files scanned: &f" + report.filesScanned()
                + " &7failed: &f" + report.filesFailed());
        sender.reply("&7Found: &f" + report.homesFound() + " homes, "
                + report.serverWarpsFound() + " server warps, "
                + report.playerWarpsFound() + " player warps, "
                + report.spawnsFound() + " spawns, "
                + report.kitsFound() + " kits");
        if (!report.dryRun()) {
            sender.reply("&7Applied: &a" + report.created() + " created &7/ &e"
                    + report.replaced() + " replaced &7/ &6" + report.skipped() + " skipped");
        }
        for (String warning : report.warnings()) {
            sender.reply("&e" + warning);
        }
    }
}
