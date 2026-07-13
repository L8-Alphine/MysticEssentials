package org.hyzionstudios.mysticessentials.modules.customcommands;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.hyzionstudios.mysticessentials.modules.customcommands.action.CustomCommandAction;
import org.hyzionstudios.mysticessentials.modules.customcommands.argument.ArgumentParser;
import org.hyzionstudios.mysticessentials.modules.customcommands.argument.CommandArgument;
import org.hyzionstudios.mysticessentials.modules.customcommands.condition.CommandCondition;

import com.google.gson.JsonArray;

/**
 * One custom command definition, deserialized from a file in
 * {@code modules/customcommands/commands/}. The raw {@code actions} and
 * {@code conditions} arrays are kept as JSON (so unknown keys round-trip
 * losslessly through Gson) and compiled into runtime objects by
 * {@link CustomCommandParser} after load; compiled state is transient and
 * never written back.
 */
public final class CustomCommand {

    // ----- Persisted definition (JSON) -----------------------------------------

    /** Primary command name, lowercase, no slash (e.g. {@code "rules"}). */
    public String name;
    public String description = "";
    /** Per-command toggle, flipped by {@code /customcommands enable|disable}. */
    public boolean enabled = true;
    public List<String> aliases = new ArrayList<>();
    public Permission permission = new Permission();
    public Cooldown cooldown = new Cooldown();
    public List<CommandArgument> arguments = new ArrayList<>();
    /** Top-level gates checked before any action runs. */
    public JsonArray conditions = new JsonArray();
    /** The action chain. */
    public JsonArray actions = new JsonArray();
    /** Default executor for {@code command} actions that do not set {@code runAs}. */
    public String runAs = "console";

    /**
     * Permission gate. Modes:
     * <ul>
     *   <li>{@code none} — everyone may run the command.</li>
     *   <li>{@code single} — requires {@link #node} (blank = the implicit
     *       {@code mysticessentials.customcommands.command.<name>} node).</li>
     *   <li>{@code all} — requires every node in {@link #nodes}.</li>
     *   <li>{@code any} — requires at least one node in {@link #nodes}.</li>
     * </ul>
     */
    public static final class Permission {
        public String mode = "none";
        public String node = "";
        public List<String> nodes = new ArrayList<>();
        /** Optional custom deny message; blank = the {@code no-permission} bundle message. */
        public String denyMessage = "";
    }

    public static final class Cooldown {
        /** Seconds between uses per player; 0 = no cooldown. */
        public long seconds = 0;
        /**
         * Extra bypass node for this command. The global
         * {@code mysticessentials.customcommands.bypass.cooldown} and the
         * per-command {@code ...bypass.cooldown.<name>} nodes always bypass.
         */
        public String bypassPermission = "";
        /** Optional custom message; blank = the {@code customcommands-cooldown} bundle message. */
        public String message = "";
    }

    // ----- Compiled runtime state (never serialized) -----------------------------

    /** File this definition was loaded from; used by enable/disable to write back. */
    public transient Path sourceFile;
    public transient List<CustomCommandAction> compiledActions = List.of();
    public transient List<CommandCondition> compiledConditions = List.of();
    /** Compile problems found by the parser/validator; non-empty = not registered. */
    public transient List<String> issues = new ArrayList<>();

    // ----- Helpers ---------------------------------------------------------------

    public String nameLower() {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    /** Primary name plus aliases, lowercase — every label this command answers to. */
    public List<String> labels() {
        List<String> labels = new ArrayList<>();
        labels.add(nameLower());
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias != null && !alias.isBlank()) {
                    labels.add(alias.toLowerCase(Locale.ROOT));
                }
            }
        }
        return labels;
    }

    /** Usage suffix built from the declared arguments (e.g. {@code <target> [message]}). */
    public String usage() {
        return arguments == null ? "" : ArgumentParser.usageOf(arguments);
    }
}
