package org.hyzionstudios.mysticessentials.modules.customcommands.argument;

import java.util.Locale;

/**
 * One declared argument of a custom command, as written in
 * {@code commands/*.json}:
 *
 * <pre>
 * { "name": "target", "type": "player", "required": true,
 *   "default": "", "description": "Who to greet" }
 * </pre>
 *
 * <p>Field names map to JSON keys via Gson. {@link #resolvedType} is compiled
 * by the {@code CustomCommandParser} after load and never serialized.</p>
 */
public final class CommandArgument {

    public String name;
    public String type = "string";
    public boolean required = true;
    /** Used when an optional argument is not supplied ({@code ""} = no default). */
    public String defaultValue = "";
    public String description = "";

    /** Compiled from {@link #type}; {@code null} means the type id was invalid. */
    public transient ArgumentType resolvedType;

    public String nameLower() {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    /** {@code <name>} for required arguments, {@code [name]} for optional ones. */
    public String usageToken() {
        return required ? "<" + name + ">" : "[" + name + "]";
    }
}
