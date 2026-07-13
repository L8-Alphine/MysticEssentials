package org.hyzionstudios.mysticessentials.modules.customcommands;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.hyzionstudios.mysticessentials.modules.customcommands.argument.ArgumentType;
import org.hyzionstudios.mysticessentials.modules.customcommands.argument.CommandArgument;

/**
 * Validates loaded command definitions: names, aliases, permission settings,
 * argument declarations, and conflicts (between definitions, and against
 * commands already registered on the server). Compile problems recorded by
 * {@link CustomCommandParser} are folded into the report so
 * {@code /customcommands validate} shows everything in one pass.
 */
public final class CustomCommandValidator {

    /** Legal command names/aliases: lowercase, digits, {@code _} and {@code -}, 1-32 chars. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");
    /** Loose permission-node shape check (dot-separated segments, {@code *} wildcards). */
    private static final Pattern PERMISSION_NODE = Pattern.compile("[a-zA-Z0-9_*-]+(\\.[a-zA-Z0-9_*-]+)*");

    public enum Severity {
        ERROR, WARNING
    }

    /** One finding; {@code ERROR} findings block registration of that command. */
    public record Issue(Severity severity, String command, String message) {
        @Override
        public String toString() {
            return "[" + severity + "] " + command + ": " + message;
        }
    }

    private final CustomCommandRegistrar registrar;

    public CustomCommandValidator(CustomCommandRegistrar registrar) {
        this.registrar = registrar;
    }

    /**
     * Validates all definitions together (so cross-definition conflicts are
     * caught) and returns every finding. Definitions with {@code ERROR}
     * findings must not be registered.
     */
    public List<Issue> validate(List<CustomCommand> definitions, CustomCommandsConfig config) {
        List<Issue> issues = new ArrayList<>();
        Map<String, String> claimedLabels = new HashMap<>(); // label -> command that claimed it

        for (CustomCommand definition : definitions) {
            String id = definition.name == null ? "(unnamed)" : definition.name;
            validateName(definition, id, issues);
            validateAliases(definition, id, issues, claimedLabels);
            validatePermission(definition, id, issues);
            validateCooldown(definition, id, issues);
            validateArguments(definition, id, issues);
            for (String compileIssue : definition.issues) {
                issues.add(new Issue(Severity.ERROR, id, compileIssue));
            }
            validateConflicts(definition, id, config, issues);
        }
        return issues;
    }

    /** @return {@code true} if no {@code ERROR}-severity issue names this command. */
    public static boolean isRegistrable(CustomCommand definition, List<Issue> issues) {
        String id = definition.name == null ? "(unnamed)" : definition.name;
        return issues.stream().noneMatch(issue ->
                issue.severity() == Severity.ERROR && issue.command().equalsIgnoreCase(id));
    }

    // ----- Individual checks -------------------------------------------------------

    private void validateName(CustomCommand definition, String id, List<Issue> issues) {
        if (definition.name == null || definition.name.isBlank()) {
            issues.add(new Issue(Severity.ERROR, id, "missing 'name'"));
            return;
        }
        if (!NAME.matcher(definition.nameLower()).matches()) {
            issues.add(new Issue(Severity.ERROR, id,
                    "invalid command name '" + definition.name
                            + "' (lowercase letters, digits, _ and -, max 32 chars)"));
        }
    }

    private void validateAliases(CustomCommand definition, String id, List<Issue> issues,
            Map<String, String> claimedLabels) {
        Set<String> seen = new HashSet<>();
        for (String label : definition.labels()) {
            if (!NAME.matcher(label).matches()) {
                issues.add(new Issue(Severity.ERROR, id, "invalid alias '" + label + "'"));
                continue;
            }
            if (!seen.add(label)) {
                issues.add(new Issue(Severity.WARNING, id, "duplicate alias '" + label + "'"));
                continue;
            }
            String claimedBy = claimedLabels.putIfAbsent(label, id);
            if (claimedBy != null && !claimedBy.equalsIgnoreCase(id)) {
                boolean isPrimary = label.equals(definition.nameLower());
                if (isPrimary) {
                    // Two commands cannot share a primary name — block this one.
                    issues.add(new Issue(Severity.ERROR, id,
                            "name '" + label + "' is already used by custom command '" + claimedBy + "'"));
                } else {
                    // A clashing alias is skipped, not fatal — the command still
                    // registers under its name and its other aliases.
                    issues.add(new Issue(Severity.WARNING, id,
                            "alias '" + label + "' already used by '" + claimedBy + "' — alias skipped"));
                }
            }
        }
    }

    private void validatePermission(CustomCommand definition, String id, List<Issue> issues) {
        CustomCommand.Permission permission = definition.permission;
        if (permission == null) {
            definition.permission = new CustomCommand.Permission();
            return;
        }
        String mode = permission.mode == null ? "none" : permission.mode.toLowerCase(Locale.ROOT);
        switch (mode) {
            case "none":
                break;
            case "single":
                if (!permission.node.isBlank() && !PERMISSION_NODE.matcher(permission.node).matches()) {
                    issues.add(new Issue(Severity.ERROR, id,
                            "invalid permission node '" + permission.node + "'"));
                }
                break;
            case "all", "any":
                if (permission.nodes == null || permission.nodes.isEmpty()) {
                    issues.add(new Issue(Severity.ERROR, id,
                            "permission mode '" + mode + "' needs a non-empty 'nodes' list"));
                    break;
                }
                for (String node : permission.nodes) {
                    if (node == null || node.isBlank() || !PERMISSION_NODE.matcher(node).matches()) {
                        issues.add(new Issue(Severity.ERROR, id, "invalid permission node '" + node + "'"));
                    }
                }
                break;
            default:
                issues.add(new Issue(Severity.ERROR, id,
                        "unknown permission mode '" + permission.mode + "' (none, single, all, any)"));
        }
        if (definition.runAs != null && !CustomCommandExecutor.isValidExecutorSpec(definition.runAs)) {
            issues.add(new Issue(Severity.ERROR, id, "invalid runAs '" + definition.runAs
                    + "' (use console, server, sender, arg:<name>, player:<name>)"));
        }
    }

    private void validateCooldown(CustomCommand definition, String id, List<Issue> issues) {
        if (definition.cooldown == null) {
            definition.cooldown = new CustomCommand.Cooldown();
            return;
        }
        if (definition.cooldown.seconds < 0) {
            issues.add(new Issue(Severity.ERROR, id, "cooldown.seconds must be >= 0"));
        }
        String bypass = definition.cooldown.bypassPermission;
        if (bypass != null && !bypass.isBlank() && !PERMISSION_NODE.matcher(bypass).matches()) {
            issues.add(new Issue(Severity.ERROR, id, "invalid cooldown bypass node '" + bypass + "'"));
        }
    }

    private void validateArguments(CustomCommand definition, String id, List<Issue> issues) {
        if (definition.arguments == null || definition.arguments.isEmpty()) {
            return;
        }
        Set<String> names = new HashSet<>();
        boolean sawOptional = false;
        for (int i = 0; i < definition.arguments.size(); i++) {
            CommandArgument argument = definition.arguments.get(i);
            if (argument.name == null || argument.name.isBlank()) {
                issues.add(new Issue(Severity.ERROR, id, "argument " + (i + 1) + " has no 'name'"));
                continue;
            }
            if (!names.add(argument.nameLower())) {
                issues.add(new Issue(Severity.ERROR, id, "duplicate argument name '" + argument.name + "'"));
            }
            if (argument.resolvedType == ArgumentType.GREEDY_STRING && i != definition.arguments.size() - 1) {
                issues.add(new Issue(Severity.ERROR, id,
                        "greedy_string argument '" + argument.name + "' must be last"));
            }
            if (argument.required && sawOptional) {
                issues.add(new Issue(Severity.ERROR, id,
                        "required argument '" + argument.name + "' follows an optional one"));
            }
            if (!argument.required) {
                sawOptional = true;
            }
        }
    }

    private void validateConflicts(CustomCommand definition, String id, CustomCommandsConfig config,
            List<Issue> issues) {
        for (String label : definition.labels()) {
            if (!registrar.isTakenByOthers(label)) {
                continue;
            }
            boolean isPrimary = label.equals(definition.nameLower());
            if (config.allowOverrideExisting) {
                issues.add(new Issue(Severity.WARNING, id,
                        "label '" + label + "' shadows an existing server command"
                                + " (allowed by allowOverrideExisting)"));
            } else if (isPrimary) {
                // The primary name is unavailable — the command cannot register.
                issues.add(new Issue(Severity.ERROR, id,
                        "name '" + label + "' conflicts with an existing server command"
                                + " (set allowOverrideExisting to permit this)"));
            } else {
                // Only an alias conflicts: skip that alias, keep the command usable.
                issues.add(new Issue(Severity.WARNING, id,
                        "alias '" + label + "' conflicts with an existing server command — alias skipped"
                                + " (set allowOverrideExisting to claim it)"));
            }
        }
    }

    /**
     * @return the alias labels that must be skipped when registering — those that
     *         clash with an existing command or an earlier custom command while
     *         {@code allowOverrideExisting} is off. Primary names are never
     *         returned (a conflicting primary makes the whole command
     *         non-registrable instead).
     */
    public Set<String> skippableLabels(List<CustomCommand> definitions, CustomCommandsConfig config) {
        Set<String> claimed = new HashSet<>();
        Set<String> skipped = new HashSet<>();
        for (CustomCommand definition : definitions) {
            String primary = definition.nameLower();
            for (String label : definition.labels()) {
                boolean isPrimary = label.equals(primary);
                if (!config.allowOverrideExisting && registrar.isTakenByOthers(label)) {
                    if (!isPrimary) {
                        skipped.add(label);
                    }
                } else if (!claimed.add(label) && !isPrimary) {
                    skipped.add(label);
                }
            }
        }
        return skipped;
    }
}
