package org.hyzionstudios.mysticessentials.modules.customcontent.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.api.ui.UiActionContext;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Runs the action lists attached to buttons, cards, toggles and dropdown
 * options.
 *
 * <p>Navigation and command actions are handled natively so a document works on
 * a stock server. Anything this class does not recognise is forwarded to the
 * compatibility bridge unchanged, so a server running the companion plugin
 * keeps its full action vocabulary. Previously every action went to the bridge,
 * which meant that without that plugin a click did nothing at all — no error,
 * no navigation.</p>
 */
final class LayoutActions {

    /** What running an action list did to the surface that triggered it. */
    enum Outcome {
        /** The surface is untouched and the caller decides whether to refresh. */
        CONTINUE,
        /** An action opened a different surface, so the caller must not reopen. */
        REPLACED,
        /** An action closed the surface. */
        CLOSED
    }

    private final MysticCore core;
    private final LayoutRuntime runtime;
    private final LayoutBridge bridge;

    LayoutActions(MysticCore core, LayoutRuntime runtime, LayoutBridge bridge) {
        this.core = core;
        this.runtime = runtime;
        this.bridge = bridge;
    }

    /**
     * @param ref   entity ref of the event being handled, or {@code null} when
     *              running outside a page event
     * @param store entity store of that event, or {@code null}
     */
    Outcome run(PlayerRef player, List<String> actions, Ref<EntityStore> ref, Store<EntityStore> store) {
        if (actions == null || actions.isEmpty()) {
            return Outcome.CONTINUE;
        }
        List<String> unhandled = new ArrayList<>();
        Outcome outcome = Outcome.CONTINUE;

        for (String raw : actions) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            // Later actions still run — only a second navigation is skipped,
            // since it would fight the surface the first one just opened.
            Outcome step = runOne(player, raw.trim(), ref, store, unhandled,
                    outcome != Outcome.CONTINUE);
            if (step != Outcome.CONTINUE) {
                outcome = step;
            }
        }

        if (!unhandled.isEmpty()) {
            forward(player, unhandled);
        }
        return outcome;
    }

    private Outcome runOne(PlayerRef player, String raw, Ref<EntityStore> ref,
            Store<EntityStore> store, List<String> unhandled, boolean surfaceGone) {
        int split = separator(raw);
        String verb = (split < 0 ? raw : raw.substring(0, split)).trim().toLowerCase(Locale.ROOT);
        String argument = split < 0 ? "" : raw.substring(split + 1).trim();

        return switch (verb) {
            case "opengui", "gui", "open" -> surfaceGone ? Outcome.CONTINUE
                    : open(player, resolve(player, argument), ref, store);
            case "closegui", "close" -> {
                if (surfaceGone) {
                    yield Outcome.CONTINUE;
                }
                LayoutPage.closeSurface(ref, store);
                yield Outcome.CLOSED;
            }
            case "hud" -> hud(player, argument);
            case "typed" -> typed(player, argument);
            case "showhud" -> hud(player, "show " + argument);
            case "hidehud" -> hud(player, "hide " + argument);
            case "command", "player", "run" -> {
                core.platform().dispatchPlayerCommand(player, command(resolve(player, argument)));
                yield Outcome.CONTINUE;
            }
            case "console", "server" -> {
                if (!player.hasPermission(Permissions.CUSTOMCONTENT_ADMIN)) {
                    core.getMessageService().send(player, "&cThis interface action requires administrator permission.");
                    core.log(Level.WARNING, "[customcontent] Blocked legacy server action from "
                            + player.getUsername() + "; use a registered typed action instead.");
                } else {
                    core.platform().dispatchConsoleCommand(command(resolve(player, argument)));
                }
                yield Outcome.CONTINUE;
            }
            case "message", "msg", "tell", "send" -> {
                core.getMessageService().send(player, resolve(player, argument));
                yield Outcome.CONTINUE;
            }
            case "broadcast", "announce" -> {
                String text = resolve(player, argument);
                core.platform().onlinePlayers()
                        .forEach(online -> core.getMessageService().send(online, text));
                yield Outcome.CONTINUE;
            }
            default -> {
                unhandled.add(raw);
                yield Outcome.CONTINUE;
            }
        };
    }

    private Outcome typed(PlayerRef player, String encoded) {
        String[] parts = encoded.split(";");
        String id = parts.length == 0 ? "" : parts[0].trim();
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        for (int index = 1; index < parts.length; index++) {
            int equals = parts[index].indexOf('=');
            if (equals > 0) {
                payload.put(parts[index].substring(0, equals),
                        resolve(player, parts[index].substring(equals + 1)));
            }
        }
        var session = core.getCustomUiService().sessions().find(player.getUuid()).orElse(null);
        var result = core.getCustomUiService().actions().dispatch(id,
                new UiActionContext(player.getUuid(), session, null,
                        session == null ? null : session.currentRoute(), payload,
                        player::hasPermission, java.time.Instant.now()));
        if (result.status() != org.hyzionstudios.mysticessentials.api.ui.UiActionResult.Status.SUCCESS
                && result.message() != null) {
            core.getMessageService().send(player, "&c" + result.message());
        }
        return Outcome.CONTINUE;
    }

    /** Opens another document, replacing the current page when there is one. */
    private Outcome open(PlayerRef player, String id, Ref<EntityStore> ref, Store<EntityStore> store) {
        UiDocument document = runtime.document(id);
        if (document == null) {
            core.getMessageService().send(player, "&cThis menu points at a GUI that does not exist: &f" + id);
            core.log(Level.WARNING, "[customcontent] Action 'opengui " + id
                    + "' has no matching document.");
            return Outcome.CONTINUE;
        }
        if (document.hud()) {
            runtime.showHud(player, document);
            return Outcome.CONTINUE;
        }
        // Swapping in place matters: opening through the scheduler would answer
        // the client's page request a tick late, which reads as a stuck load.
        LayoutPage.openPage(core, player, runtime, document, ref, store);
        return Outcome.REPLACED;
    }

    private Outcome hud(PlayerRef player, String argument) {
        int split = argument.indexOf(' ');
        String mode = (split < 0 ? argument : argument.substring(0, split)).trim().toLowerCase(Locale.ROOT);
        String id = split < 0 ? "" : argument.substring(split + 1).trim();
        UiDocument document = runtime.document(id);
        if (document == null || !document.hud()) {
            core.getMessageService().send(player, "&cThis menu points at a HUD that does not exist: &f" + id);
            return Outcome.CONTINUE;
        }
        if (mode.equals("hide") || mode.equals("off")) {
            runtime.hideHud(player, document.id);
        } else {
            runtime.showHud(player, document);
        }
        return Outcome.CONTINUE;
    }

    /**
     * Hands unrecognised actions to the compatibility plugin, and says so out
     * loud when there is no plugin to hand them to — a silent no-op here is the
     * hardest kind of broken menu to diagnose.
     */
    private void forward(PlayerRef player, List<String> actions) {
        if (!bridge.handlesUnknownActions()) {
            core.getMessageService().send(player,
                    "&cThis button uses an action Mystic Essentials does not provide: &f"
                            + actions.get(0));
            core.log(Level.WARNING, "[customcontent] Unhandled GUI action(s) "
                    + actions + " and no compatibility plugin is connected. Built-in verbs: "
                    + "opengui, close, hud, command, console, message, broadcast.");
            return;
        }
        bridge.executeActions(player, actions);
    }

    private String resolve(PlayerRef player, String value) {
        return bridge.substitute(player, value);
    }

    /** @return {@code command} without a leading slash, as the dispatchers expect. */
    private static String command(String command) {
        String trimmed = command.trim();
        return trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
    }

    /** @return the index of the {@code :} or space dividing verb from argument. */
    private static int separator(String action) {
        int colon = action.indexOf(':');
        int space = action.indexOf(' ');
        if (colon < 0) {
            return space;
        }
        return space < 0 ? colon : Math.min(colon, space);
    }

}
