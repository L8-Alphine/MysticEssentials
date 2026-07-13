package org.hyzionstudios.mysticessentials.modules.customcommands;

import java.util.Locale;

import org.hyzionstudios.mysticessentials.api.Permissions;

import com.hypixel.hytale.server.core.command.system.CommandSender;

/**
 * Evaluates a custom command's permission gate against a sender. Modes:
 * {@code none} (open to all), {@code single} (one node — blank means the
 * implicit {@code mysticessentials.customcommands.command.<name>} node),
 * {@code all} (every listed node), {@code any} (at least one listed node).
 * The console passes every gate.
 */
public final class CustomCommandPermissionService {

    /** @return {@code true} if {@code sender} may run {@code definition}. */
    public boolean mayUse(CommandSender sender, CustomCommand definition, boolean isConsole) {
        if (isConsole) {
            return true;
        }
        CustomCommand.Permission permission = definition.permission;
        if (permission == null) {
            return true;
        }
        String mode = permission.mode == null ? "none" : permission.mode.toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "single" -> sender.hasPermission(effectiveSingleNode(definition));
            case "all" -> permission.nodes.stream().allMatch(sender::hasPermission);
            case "any" -> permission.nodes.stream().anyMatch(sender::hasPermission);
            default -> true; // "none" and anything the validator would have rejected
        };
    }

    /** The node checked in {@code single} mode: the explicit one, or the implicit per-command node. */
    public String effectiveSingleNode(CustomCommand definition) {
        String node = definition.permission == null ? "" : definition.permission.node;
        return node == null || node.isBlank()
                ? Permissions.customCommand(definition.nameLower())
                : node;
    }

    /** Custom deny message from the definition, or {@code null} to use the bundle default. */
    public String denyMessage(CustomCommand definition) {
        String message = definition.permission == null ? null : definition.permission.denyMessage;
        return message == null || message.isBlank() ? null : message;
    }

    /** @return {@code true} if {@code sender} bypasses this command's cooldown. */
    public boolean bypassesCooldown(CommandSender sender, CustomCommand definition, boolean isConsole) {
        if (isConsole) {
            return true;
        }
        if (sender.hasPermission(Permissions.CUSTOMCOMMANDS_BYPASS_COOLDOWN)
                || sender.hasPermission(Permissions.customCommandCooldownBypass(definition.nameLower()))) {
            return true;
        }
        String extra = definition.cooldown == null ? null : definition.cooldown.bypassPermission;
        return extra != null && !extra.isBlank() && sender.hasPermission(extra);
    }
}
