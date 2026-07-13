package org.hyzionstudios.mysticessentials.modules.customcommands;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.customcommands.action.CustomCommandAction;
import org.hyzionstudios.mysticessentials.modules.customcommands.condition.CommandCondition;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

/**
 * {@code /customcommands} admin tree (aliases {@code /ccmd},
 * {@code /customcmd}, {@code /mecustomcommands}), free-form parser like the
 * tutorial/mail commands: list, info, reload, enable, disable, test,
 * validate. Each subcommand has its own permission node;
 * {@code mysticessentials.customcommands.admin} grants everything.
 * {@code reload} and {@code validate} work even while the module's runtime
 * switch is off, so an owner can finish setup without a restart.
 */
public final class CustomCommandsAdminCommand extends MysticCommand {

    private static final Set<String> KEYWORDS = Set.of("help", "list", "info", "reload",
            "enable", "disable", "test", "validate");

    private final CustomCommandsModule module;

    public CustomCommandsAdminCommand(MysticCore core, CustomCommandsModule module) {
        super(core, "customcommands", "Manage JSON-defined custom commands.");
        this.module = module;
        addAliases("ccmd", "customcmd", "mecustomcommands");
        allowExtraArguments();
    }

    @Override
    protected void run(MysticCommandSender sender) {
        String[] args = sender.args();
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (!KEYWORDS.contains(sub)) {
            sub = "help";
        }
        if (!module.config().enabled && !sub.equals("reload") && !sub.equals("validate")
                && !sub.equals("help")) {
            sender.replyKey("module-disabled");
            return;
        }
        switch (sub) {
            case "list" -> list(sender);
            case "info" -> info(sender, arg(args, 1));
            case "reload" -> reload(sender);
            case "enable" -> setEnabled(sender, arg(args, 1), true);
            case "disable" -> setEnabled(sender, arg(args, 1), false);
            case "test" -> test(sender, args);
            case "validate" -> validate(sender);
            default -> help(sender);
        }
    }

    private static String arg(String[] args, int index) {
        return index < args.length ? args[index] : null;
    }

    private boolean denyUnless(MysticCommandSender sender, String node) {
        if (sender.hasPermission(node) || sender.hasPermission(Permissions.CUSTOMCOMMANDS_ADMIN)) {
            return false;
        }
        sender.replyKey("no-permission");
        return true;
    }

    // ----- Subcommands ------------------------------------------------------------

    private void help(MysticCommandSender sender) {
        sender.reply("&d&lCustom Commands &7— admin commands:");
        sender.reply("&f/customcommands list &7— all loaded commands");
        sender.reply("&f/customcommands info <command> &7— definition details");
        sender.reply("&f/customcommands reload &7— reload config + definitions");
        sender.reply("&f/customcommands enable|disable <command> &7— toggle one command");
        sender.reply("&f/customcommands test <command> [args...] &7— run ignoring cooldown/disabled");
        sender.reply("&f/customcommands validate &7— re-check every definition");
    }

    private void list(MysticCommandSender sender) {
        if (denyUnless(sender, Permissions.CUSTOMCOMMANDS_LIST)) {
            return;
        }
        List<CustomCommand> loaded = module.loadedDefinitions();
        if (loaded.isEmpty()) {
            sender.reply("&7No custom commands are defined. Add JSON files to &f"
                    + module.fileLoader().commandsDir() + "&7.");
            return;
        }
        sender.reply("&d&lCustom Commands &7(" + module.registry().size() + " active / "
                + loaded.size() + " loaded):");
        for (CustomCommand definition : loaded) {
            boolean active = module.registry().byName(definition.nameLower()).isPresent();
            String state = !active ? "&cinvalid" : definition.enabled ? "&aenabled" : "&7disabled";
            sender.reply("&f/" + definition.nameLower() + " " + state
                    + " &8- &7" + (definition.description == null || definition.description.isBlank()
                            ? "no description" : definition.description)
                    + " &8(" + module.audit().usageCount(definition.nameLower()) + " uses)");
        }
    }

    private void info(MysticCommandSender sender, String name) {
        if (denyUnless(sender, Permissions.CUSTOMCOMMANDS_INFO)) {
            return;
        }
        Optional<CustomCommand> found = module.findDefinition(name);
        if (name == null || found.isEmpty()) {
            sender.replyKey("customcommands-unknown",
                    java.util.Map.of("command", name == null ? "?" : name));
            return;
        }
        CustomCommand definition = found.get();
        boolean active = module.registry().byName(definition.nameLower()).isPresent();
        sender.reply("&d&l/" + definition.nameLower() + " &7— "
                + (definition.description == null ? "" : definition.description));
        sender.reply("&7State: " + (!active ? "&cinvalid (see /customcommands validate)"
                : definition.enabled ? "&aenabled" : "&7disabled")
                + " &8| &7Uses: &f" + module.audit().usageCount(definition.nameLower())
                + " &8| &7File: &f" + (definition.sourceFile == null ? "?"
                        : definition.sourceFile.getFileName()));
        if (!definition.aliases.isEmpty()) {
            sender.reply("&7Aliases: &f" + String.join(", ", definition.aliases));
        }
        String mode = definition.permission == null ? "none" : definition.permission.mode;
        sender.reply("&7Permission mode: &f" + mode
                + ("single".equalsIgnoreCase(mode)
                        ? " &8(" + module.permissions().effectiveSingleNode(definition) + ")"
                        : "")
                + ("all".equalsIgnoreCase(mode) || "any".equalsIgnoreCase(mode)
                        ? " &8(" + String.join(", ", definition.permission.nodes) + ")"
                        : ""));
        if (definition.cooldown != null && definition.cooldown.seconds > 0) {
            sender.reply("&7Cooldown: &f" + definition.cooldown.seconds + "s");
        }
        if (!definition.arguments.isEmpty()) {
            sender.reply("&7Usage: &f/" + definition.nameLower() + " " + definition.usage());
        }
        sender.reply("&7Runs as: &f" + definition.runAs);
        for (CommandCondition condition : definition.compiledConditions) {
            sender.reply("&7Condition: &f" + condition.describe());
        }
        int index = 1;
        for (CustomCommandAction action : definition.compiledActions) {
            sender.reply("&7Action " + index++ + ": &f" + action.describe());
        }
    }

    private void reload(MysticCommandSender sender) {
        if (denyUnless(sender, Permissions.CUSTOMCOMMANDS_RELOAD)) {
            return;
        }
        module.reload(sender.name(), false);
        long errors = module.lastValidation().stream()
                .filter(issue -> issue.severity() == CustomCommandValidator.Severity.ERROR).count()
                + module.fileLoader().fileErrors().size();
        sender.replyKey("customcommands-reloaded", java.util.Map.of(
                "count", Integer.toString(module.registry().size()),
                "errors", Long.toString(errors)));
        if (errors > 0) {
            sender.reply("&7Run &f/customcommands validate &7for details.");
        }
        if (!module.config().enabled) {
            sender.reply("&7The module is still switched off — set &f\"enabled\": true &7in "
                    + "&fmodules/customcommands/config.json &7and reload again.");
        }
    }

    private void setEnabled(MysticCommandSender sender, String name, boolean enabled) {
        if (denyUnless(sender, Permissions.CUSTOMCOMMANDS_MANAGE)) {
            return;
        }
        Optional<CustomCommand> found = module.findDefinition(name);
        if (name == null || found.isEmpty()) {
            sender.replyKey("customcommands-unknown",
                    java.util.Map.of("command", name == null ? "?" : name));
            return;
        }
        CustomCommand definition = found.get();
        definition.enabled = enabled;
        boolean saved = module.fileLoader().saveDefinition(definition);
        module.audit().logAdmin(sender.name(),
                (enabled ? "enabled" : "disabled") + " /" + definition.nameLower());
        sender.reply("&7Custom command &f/" + definition.nameLower() + " &7is now "
                + (enabled ? "&aenabled" : "&cdisabled") + "&7."
                + (saved ? "" : " &c(could not write the change to its file)"));
    }

    private void test(MysticCommandSender sender, String[] args) {
        if (denyUnless(sender, Permissions.CUSTOMCOMMANDS_TEST)) {
            return;
        }
        String name = arg(args, 1);
        Optional<CustomCommand> found = name == null ? Optional.empty()
                : module.registry().byLabel(name)
                        .or(() -> module.registry().byName(name));
        if (found.isEmpty()) {
            sender.replyKey("customcommands-unknown",
                    java.util.Map.of("command", name == null ? "?" : name));
            return;
        }
        String rawArgs = args.length <= 2 ? ""
                : String.join(" ", java.util.Arrays.asList(args).subList(2, args.length));
        sender.reply("&7Testing &f/" + found.get().nameLower()
                + (rawArgs.isBlank() ? "" : " " + rawArgs) + "&7 (cooldowns ignored)...");
        module.executor().invoke(found.get(), sender.raw().sender(),
                sender.player().orElse(null), found.get().nameLower(), rawArgs, true);
    }

    private void validate(MysticCommandSender sender) {
        if (denyUnless(sender, Permissions.CUSTOMCOMMANDS_VALIDATE)) {
            return;
        }
        List<String> fileErrors = module.fileLoader().fileErrors();
        List<CustomCommandValidator.Issue> issues = module.lastValidation();
        if (fileErrors.isEmpty() && issues.isEmpty()) {
            sender.reply("&aAll " + module.registry().size() + " custom command(s) are valid.");
            return;
        }
        sender.reply("&d&lValidation report &7(" + module.registry().size() + " active):");
        for (String error : fileErrors) {
            sender.reply("&c[FILE] &f" + error);
        }
        for (CustomCommandValidator.Issue issue : issues) {
            String color = issue.severity() == CustomCommandValidator.Severity.ERROR ? "&c" : "&e";
            sender.reply(color + "[" + issue.severity() + "] &f" + issue.command()
                    + "&7: " + issue.message());
        }
    }
}
