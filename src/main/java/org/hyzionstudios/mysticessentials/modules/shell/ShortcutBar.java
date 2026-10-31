package org.hyzionstudios.mysticessentials.modules.shell;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.hyzionstudios.mysticessentials.api.service.ChatService;
import org.hyzionstudios.mysticessentials.core.MysticCore;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Resolves the configured slots into what one player should actually see.
 *
 * <p>Everything here is recomputed per render rather than cached. A player
 * joins a guild, a module is disabled by {@code /mystic reload}, a permission
 * is revoked — each changes what the rail should say, and a cached rail that
 * still offers a guild channel the player just left is worse than no rail.</p>
 */
final class ShortcutBar {

    private final MysticCore core;
    private final ShellModule shell;

    ShortcutBar(MysticCore core, ShellModule shell) {
        this.core = core;
        this.shell = shell;
    }

    /** @return the nine slots in index order, resolved for this player. */
    List<ShortcutSlot> resolve(PlayerRef player) {
        List<ShellConfig.Slot> configured = shell.config().slots;
        List<ShortcutSlot> resolved = new ArrayList<>();
        for (int index = 1; index <= 9; index++) {
            ShellConfig.Slot slot = find(configured, index);
            resolved.add(slot == null || !slot.enabled ? ShortcutSlot.empty(index)
                    : resolve(player, slot).withIcon(ShellConfig.iconFor(slot)));
        }
        return resolved;
    }

    /** @return the slot at this number, resolved; empty when the number is out of range. */
    Optional<ShortcutSlot> resolve(PlayerRef player, int index) {
        if (index < 1 || index > 9) {
            return Optional.empty();
        }
        ShellConfig.Slot slot = find(shell.config().slots, index);
        if (slot == null || !slot.enabled) {
            return Optional.of(ShortcutSlot.empty(index));
        }
        return Optional.of(resolve(player, slot).withIcon(ShellConfig.iconFor(slot)));
    }

    private static ShellConfig.Slot find(List<ShellConfig.Slot> slots, int index) {
        if (slots == null) {
            return null;
        }
        for (ShellConfig.Slot slot : slots) {
            if (slot != null && slot.index == index) {
                return slot;
            }
        }
        return null;
    }

    private ShortcutSlot resolve(PlayerRef player, ShellConfig.Slot slot) {
        ShortcutKind kind = ShortcutKind.parse(slot.kind);
        String label = blankTo(slot.label, defaultLabel(kind, slot.index));
        String glyph = blankTo(slot.glyph, String.valueOf(slot.index));
        String target = slot.target == null ? "" : slot.target.trim();

        // The permission gate is first: a slot the player may not use should
        // say so rather than reporting on a module they cannot reach anyway.
        if (slot.permission != null && !slot.permission.isBlank()
                && !player.hasPermission(slot.permission)) {
            return denied(slot.index, kind, label, glyph, target, "You do not have access to this");
        }

        return switch (kind) {
            case CHANNEL -> channel(player, slot.index, label, glyph, target);
            case WIKI -> moduleBacked(slot.index, kind, label, glyph, target, "wiki", "The server wiki");
            case WARPS -> moduleBacked(slot.index, kind, label, glyph,
                    blankTo(target, "warps"), "warps", "Warps");
            case MENU -> ready(slot.index, kind, label, glyph, target, "Opens the menu");
            case STORE -> configured(slot.index, kind, label, glyph, target,
                    "The server has not set up a store yet");
            case SERVERS -> configured(slot.index, kind, label, glyph, target,
                    "No server browser is configured");
            case COMMAND -> configured(slot.index, kind, label, glyph, target,
                    "This shortcut has no command set");
            case NONE -> ShortcutSlot.empty(slot.index);
        };
    }

    /**
     * A chat channel. Three outcomes the bible asks to be told apart: the
     * server has no such channel at all, the player cannot use the one it has
     * (no guild, no party, no permission), or it is available — and possibly
     * the one they are already speaking in.
     */
    private ShortcutSlot channel(PlayerRef player, int index, String label, String glyph, String channelId) {
        if (channelId.isEmpty()) {
            return denied(index, ShortcutKind.CHANNEL, label, glyph, channelId, "No channel is assigned");
        }
        ChatService chat = core.getChatService();
        if (chat == null || !core.getModuleManager().isEnabled("chat")) {
            return denied(index, ShortcutKind.CHANNEL, label, glyph, channelId, "Chat channels are turned off");
        }
        if (chat.channelDisplayName(channelId).isEmpty()) {
            return denied(index, ShortcutKind.CHANNEL, label, glyph, channelId,
                    "This server has no " + label.toLowerCase(Locale.ROOT));
        }
        if (!chat.canUseChannel(player.getUuid(), channelId)) {
            return denied(index, ShortcutKind.CHANNEL, label, glyph, channelId, notYoursYet(channelId));
        }
        boolean speaking = channelId.equalsIgnoreCase(chat.currentChannel(player.getUuid()));
        return new ShortcutSlot(index, ShortcutKind.CHANNEL, label, glyph, channelId,
                speaking ? "Speaking here" : "Switch to this channel", true, "", speaking, "");
    }

    /**
     * The hint for a channel that exists but is not open to this player. Guild
     * and party channels are the common case and deserve the specific nudge
     * the bible calls for rather than a flat "no access".
     */
    private static String notYoursYet(String channelId) {
        String id = channelId.toLowerCase(Locale.ROOT);
        if (id.contains("guild")) {
            return "Join a guild to use this channel";
        }
        if (id.contains("party")) {
            return "Join a party to use this channel";
        }
        return "You cannot speak in this channel";
    }

    /** A slot backed by an Essentials module that may be switched off. */
    private ShortcutSlot moduleBacked(int index, ShortcutKind kind, String label, String glyph,
            String target, String moduleId, String what) {
        if (!core.getModuleManager().isEnabled(moduleId)) {
            return denied(index, kind, label, glyph, target, what + " is turned off on this server");
        }
        return ready(index, kind, label, glyph, target, "");
    }

    /** A slot that does nothing until the server points it somewhere. */
    private static ShortcutSlot configured(int index, ShortcutKind kind, String label, String glyph,
            String target, String missing) {
        if (target.isEmpty()) {
            return denied(index, kind, label, glyph, target, missing);
        }
        return ready(index, kind, label, glyph, target, "");
    }

    private static ShortcutSlot ready(int index, ShortcutKind kind, String label, String glyph,
            String target, String state) {
        return new ShortcutSlot(index, kind, label, glyph, target, state, true, "", false, "");
    }

    private static ShortcutSlot denied(int index, ShortcutKind kind, String label, String glyph,
            String target, String reason) {
        return new ShortcutSlot(index, kind, label, glyph, target, "", false, reason, false, "");
    }

    private static String defaultLabel(ShortcutKind kind, int index) {
        return switch (kind) {
            case CHANNEL -> "Channel " + index;
            case STORE -> "Server Store";
            case WIKI -> "Server Wiki";
            case WARPS -> "Warps";
            case SERVERS -> "Server Browser";
            case MENU -> "Menu";
            case COMMAND -> "Shortcut " + index;
            case NONE -> "Unused";
        };
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
