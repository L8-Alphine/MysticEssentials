package org.hyzionstudios.mysticessentials.modules.customcommands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.hyzionstudios.mysticessentials.modules.customcommands.action.BroadcastAction;
import org.hyzionstudios.mysticessentials.modules.customcommands.action.CommandAction;
import org.hyzionstudios.mysticessentials.modules.customcommands.action.ConditionalAction;
import org.hyzionstudios.mysticessentials.modules.customcommands.action.CustomCommandAction;
import org.hyzionstudios.mysticessentials.modules.customcommands.action.DelayAction;
import org.hyzionstudios.mysticessentials.modules.customcommands.action.MessageAction;
import org.hyzionstudios.mysticessentials.modules.customcommands.action.NotificationAction;
import org.hyzionstudios.mysticessentials.modules.customcommands.action.SoundAction;
import org.hyzionstudios.mysticessentials.modules.customcommands.argument.ArgumentType;
import org.hyzionstudios.mysticessentials.modules.customcommands.argument.CommandArgument;
import org.hyzionstudios.mysticessentials.modules.customcommands.condition.CommandCondition;
import org.hyzionstudios.mysticessentials.modules.customcommands.condition.PermissionCondition;
import org.hyzionstudios.mysticessentials.modules.customcommands.condition.PlaceholderCondition;
import org.hyzionstudios.mysticessentials.modules.customcommands.condition.ServerCondition;
import org.hyzionstudios.mysticessentials.modules.customcommands.condition.WorldCondition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Compiles a deserialized {@link CustomCommand} definition into its runtime
 * form: argument types are resolved, and the raw JSON {@code actions} /
 * {@code conditions} arrays become {@link CustomCommandAction} /
 * {@link CommandCondition} objects. Every problem is recorded on
 * {@link CustomCommand#issues} (never thrown) so the validator and
 * {@code /customcommands validate} can report all of them at once.
 */
public final class CustomCommandParser {

    /** Compiles {@code definition} in place. @return {@code true} when issue-free. */
    public boolean compile(CustomCommand definition) {
        List<String> issues = new ArrayList<>();

        if (definition.arguments == null) {
            definition.arguments = new ArrayList<>();
        }
        for (CommandArgument argument : definition.arguments) {
            argument.resolvedType = ArgumentType.fromId(argument.type);
            if (argument.resolvedType == null) {
                issues.add("argument '" + argument.name + "': unknown type '" + argument.type + "'");
            }
        }

        definition.compiledConditions = compileConditions(definition.conditions, "conditions", issues);
        definition.compiledActions = compileActions(definition.actions, "actions", 0, issues);
        if (definition.compiledActions.isEmpty() && issues.isEmpty()) {
            issues.add("no actions defined — the command would do nothing");
        }

        definition.issues = issues;
        return issues.isEmpty();
    }

    // ----- Actions ---------------------------------------------------------------

    /** Nested {@code condition} actions may nest further; this caps definition depth. */
    private static final int MAX_DEFINITION_NESTING = 8;

    private List<CustomCommandAction> compileActions(JsonArray array, String where, int nesting,
            List<String> issues) {
        List<CustomCommandAction> actions = new ArrayList<>();
        if (array == null) {
            return actions;
        }
        if (nesting > MAX_DEFINITION_NESTING) {
            issues.add(where + ": condition branches nested deeper than " + MAX_DEFINITION_NESTING);
            return actions;
        }
        int index = 0;
        for (JsonElement element : array) {
            String at = where + "[" + index++ + "]";
            if (!element.isJsonObject()) {
                issues.add(at + ": not a JSON object");
                continue;
            }
            CustomCommandAction action = compileAction(element.getAsJsonObject(), at, nesting, issues);
            if (action != null) {
                actions.add(action);
            }
        }
        return actions;
    }

    private CustomCommandAction compileAction(JsonObject json, String at, int nesting, List<String> issues) {
        String type = string(json, "type", "").toLowerCase(Locale.ROOT);
        switch (type) {
            case "message": {
                List<String> lines = stringList(json, "lines");
                String text = string(json, "text", "");
                if (!text.isEmpty()) {
                    lines.add(0, text);
                }
                if (lines.isEmpty()) {
                    issues.add(at + ": message action needs 'text' or 'lines'");
                    return null;
                }
                return new MessageAction(lines);
            }
            case "command": {
                String command = string(json, "command", "");
                if (command.isBlank()) {
                    issues.add(at + ": command action needs 'command'");
                    return null;
                }
                String runAs = json.has("runAs") ? string(json, "runAs", null) : null;
                if (runAs != null && !CustomCommandExecutor.isValidExecutorSpec(runAs)) {
                    issues.add(at + ": invalid runAs '" + runAs
                            + "' (use console, server, sender, arg:<name>, player:<name>)");
                    return null;
                }
                return new CommandAction(command, runAs);
            }
            case "broadcast": {
                String text = string(json, "text", "");
                if (text.isBlank()) {
                    issues.add(at + ": broadcast action needs 'text'");
                    return null;
                }
                return new BroadcastAction(text);
            }
            case "delay": {
                long millis = 0;
                if (json.has("seconds")) {
                    millis = (long) (number(json, "seconds", 0) * 1000);
                } else if (json.has("ticks")) {
                    millis = (long) (number(json, "ticks", 0) * 50);
                } else if (json.has("millis")) {
                    millis = (long) number(json, "millis", 0);
                }
                if (millis <= 0) {
                    issues.add(at + ": delay action needs a positive 'seconds', 'ticks', or 'millis'");
                    return null;
                }
                return new DelayAction(millis);
            }
            case "condition": {
                JsonArray conditionArray = json.has("conditions") && json.get("conditions").isJsonArray()
                        ? json.getAsJsonArray("conditions") : new JsonArray();
                if (json.has("if") && json.get("if").isJsonObject()) {
                    conditionArray.add(json.getAsJsonObject("if"));
                }
                List<CommandCondition> conditions = compileConditions(conditionArray, at, issues);
                if (conditions.isEmpty()) {
                    issues.add(at + ": condition action needs 'conditions' (or 'if')");
                    return null;
                }
                List<CustomCommandAction> thenActions = compileActions(
                        arrayOf(json, "then"), at + ".then", nesting + 1, issues);
                List<CustomCommandAction> elseActions = compileActions(
                        arrayOf(json, "else"), at + ".else", nesting + 1, issues);
                if (thenActions.isEmpty() && elseActions.isEmpty()) {
                    issues.add(at + ": condition action needs a 'then' or 'else' branch");
                    return null;
                }
                return new ConditionalAction(conditions, thenActions, elseActions);
            }
            case "notification": {
                String title = string(json, "title", string(json, "text", ""));
                if (title.isBlank()) {
                    issues.add(at + ": notification action needs 'title'");
                    return null;
                }
                String style = string(json, "style", "");
                if (!NotificationAction.isKnownStyle(style)) {
                    issues.add(at + ": unknown notification style '" + style
                            + "' (default, danger, warning, success)");
                    return null;
                }
                return new NotificationAction(title, string(json, "body", ""), style,
                        "all".equalsIgnoreCase(string(json, "target", "sender")));
            }
            case "sound": {
                String sound = string(json, "sound", string(json, "soundEvent", ""));
                if (sound.isBlank()) {
                    issues.add(at + ": sound action needs 'sound'");
                    return null;
                }
                String category = string(json, "category", "");
                if (!SoundAction.isKnownCategory(category)) {
                    issues.add(at + ": unknown sound category '" + category
                            + "' (music, ambient, sfx, ui, voice)");
                    return null;
                }
                return new SoundAction(sound, category,
                        (float) number(json, "volume", 1.0), (float) number(json, "pitch", 1.0),
                        "all".equalsIgnoreCase(string(json, "target", "sender")));
            }
            default:
                issues.add(at + ": unknown action type '" + type + "'");
                return null;
        }
    }

    // ----- Conditions --------------------------------------------------------------

    private List<CommandCondition> compileConditions(JsonArray array, String where, List<String> issues) {
        List<CommandCondition> conditions = new ArrayList<>();
        if (array == null) {
            return conditions;
        }
        int index = 0;
        for (JsonElement element : array) {
            String at = where + "[" + index++ + "]";
            if (!element.isJsonObject()) {
                issues.add(at + ": not a JSON object");
                continue;
            }
            CommandCondition condition = compileCondition(element.getAsJsonObject(), at, issues);
            if (condition != null) {
                conditions.add(condition);
            }
        }
        return conditions;
    }

    private CommandCondition compileCondition(JsonObject json, String at, List<String> issues) {
        String type = string(json, "type", "").toLowerCase(Locale.ROOT);
        String denyMessage = string(json, "denyMessage", "");
        switch (type) {
            case "permission": {
                String node = string(json, "node", string(json, "value", ""));
                if (node.isBlank()) {
                    issues.add(at + ": permission condition needs 'node'");
                    return null;
                }
                return new PermissionCondition(node, bool(json, "negate"), denyMessage);
            }
            case "world": {
                List<String> worlds = stringList(json, "worlds");
                String world = string(json, "world", "");
                if (!world.isBlank()) {
                    worlds.add(world);
                }
                if (worlds.isEmpty()) {
                    issues.add(at + ": world condition needs 'world' or 'worlds'");
                    return null;
                }
                return new WorldCondition(worlds, bool(json, "negate"), denyMessage);
            }
            case "server": {
                String server = string(json, "server", "");
                int min = (int) number(json, "minOnline", 0);
                int max = (int) number(json, "maxOnline", 0);
                if (server.isBlank() && min <= 0 && max <= 0) {
                    issues.add(at + ": server condition needs 'server', 'minOnline', or 'maxOnline'");
                    return null;
                }
                return new ServerCondition(server, min, max, denyMessage);
            }
            case "placeholder": {
                String placeholder = string(json, "placeholder", "");
                if (placeholder.isBlank()) {
                    issues.add(at + ": placeholder condition needs 'placeholder'");
                    return null;
                }
                String operator = string(json, "operator", "equals");
                if (!PlaceholderCondition.isKnownOperator(operator)) {
                    issues.add(at + ": unknown operator '" + operator
                            + "' (equals, not_equals, contains, greater_than, less_than)");
                    return null;
                }
                return new PlaceholderCondition(placeholder, operator, string(json, "value", ""), denyMessage);
            }
            default:
                issues.add(at + ": unknown condition type '" + type + "'");
                return null;
        }
    }

    // ----- JSON helpers ----------------------------------------------------------------

    private static String string(JsonObject json, String key, String fallback) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
    }

    private static boolean bool(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
                && element.getAsBoolean();
    }

    private static double number(JsonObject json, String key, double fallback) {
        JsonElement element = json.get(key);
        try {
            return element != null && element.isJsonPrimitive() ? element.getAsDouble() : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static List<String> stringList(JsonObject json, String key) {
        List<String> values = new ArrayList<>();
        JsonElement element = json.get(key);
        if (element != null && element.isJsonArray()) {
            for (JsonElement entry : element.getAsJsonArray()) {
                if (entry.isJsonPrimitive()) {
                    values.add(entry.getAsString());
                }
            }
        }
        return values;
    }

    private static JsonArray arrayOf(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }
}
