package org.hyzionstudios.mysticessentials.modules.chat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.notification.NotificationPreferences;
import org.hyzionstudios.mysticessentials.core.notification.NotificationServiceImpl;
import org.hyzionstudios.mysticessentials.platform.command.MysticArgTypes;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * {@code /ignore} and {@code /unignore}: each player's ignore list, the blocked-players
 * list kept in their notification preferences (and shown in {@code /mentions}). An
 * ignored player's chat lines and mentions do not reach the player ignoring them.
 */
final class IgnoreSubModule {

    /** What an {@code /ignore <player>} or {@code /unignore <player>} did. */
    enum Outcome {
        ADDED, REMOVED, ALREADY, NOT_IGNORED, SELF, EXEMPT
    }

    private final MysticCore core;

    IgnoreSubModule(MysticCore core) {
        this.core = core;
    }

    void enable(Consumer<MysticCommand> commandRegistrar) {
        commandRegistrar.accept(new IgnoreCommand());
        commandRegistrar.accept(new UnignoreCommand());
    }

    /**
     * Adds or removes {@code targetName} on {@code preferences}' ignore list.
     *
     * @param ownName      the name of the player whose list it is
     * @param targetExempt whether the target holds {@link Permissions#CHAT_IGNORE_EXEMPT}
     */
    static Outcome apply(NotificationPreferences preferences, String ownName, String targetName,
            boolean ignore, boolean targetExempt) {
        if (!ignore) {
            return preferences.setBlocked(targetName, false) ? Outcome.REMOVED : Outcome.NOT_IGNORED;
        }
        if (targetName.equalsIgnoreCase(ownName)) {
            return Outcome.SELF;
        }
        if (preferences.blocks(targetName)) {
            return Outcome.ALREADY;
        }
        if (targetExempt) {
            return Outcome.EXEMPT;
        }
        preferences.setBlocked(targetName, true);
        return Outcome.ADDED;
    }

    private void reply(MysticCommandSender sender, Outcome outcome, String targetName) {
        String key = switch (outcome) {
            case ADDED -> "chat-ignore-added";
            case REMOVED -> "chat-ignore-removed";
            case ALREADY -> "chat-ignore-already";
            case NOT_IGNORED -> "chat-ignore-not-ignored";
            case SELF -> "chat-ignore-self";
            case EXEMPT -> "chat-ignore-exempt";
        };
        sender.replyKey(key, Map.of("player", targetName));
    }

    /** The command sender's online player and notification engine, or a reply saying why not. */
    private Optional<PlayerRef> playerFor(MysticCommandSender sender) {
        PlayerRef player = sender.player().orElse(null);
        if (player == null) {
            sender.replyKey("player-only");
            return Optional.empty();
        }
        if (core.notifications() == null) {
            sender.replyKey("chat-ignore-unavailable");
            return Optional.empty();
        }
        return Optional.of(player);
    }

    private List<String> ignoredNames(CommandSender commandSender) {
        NotificationServiceImpl notifications = core.notifications();
        return notifications == null || commandSender.getUuid() == null
                ? List.of()
                : notifications.preferences(commandSender.getUuid()).blockedNames();
    }

    /** {@code /ignore} lists the ignored players; {@code /ignore <player>} is the variant below. */
    private final class IgnoreCommand extends MysticCommand {
        IgnoreCommand() {
            super(IgnoreSubModule.this.core, "ignore", "List the players you ignore, or ignore one.");
            requirePermission(Permissions.CHAT_IGNORE);
            addUsageVariant(new IgnorePlayerVariant());
        }

        @Override
        protected void run(MysticCommandSender sender) {
            PlayerRef player = playerFor(sender).orElse(null);
            if (player == null) {
                return;
            }
            List<String> names = core.notifications().preferences(player.getUuid()).blockedNames();
            if (names.isEmpty()) {
                sender.replyKey("chat-ignore-list-empty");
                return;
            }
            sender.replyKey("chat-ignore-list", Map.of(
                    "count", Integer.toString(names.size()),
                    "players", String.join(", ", names)));
        }
    }

    private final class IgnorePlayerVariant extends MysticCommand {
        private final RequiredArg<String> target = withRequiredArg("player", "Player to ignore",
                MysticArgTypes.NETWORK_PLAYER_NAME);

        IgnorePlayerVariant() {
            super(IgnoreSubModule.this.core, "Hide a player's chat and mentions.");
            requirePermission(Permissions.CHAT_IGNORE);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            PlayerRef player = playerFor(sender).orElse(null);
            if (player == null) {
                return;
            }
            String typed = sender.get(target).trim();
            // An online player is named as they spell it; anyone else must be known to
            // this server, so a typo does not silently fill the list.
            Optional<PlayerRef> online = core.platform().findPlayerByName(typed);
            CompletableFuture<Optional<UUID>> resolved = online.isPresent()
                    ? CompletableFuture.completedFuture(online.map(PlayerRef::getUuid))
                    : core.getPlayerProfileService().resolveUuid(typed);
            String name = online.map(PlayerRef::getUsername).orElse(typed);
            resolved.thenAccept(uuid -> {
                if (uuid.isEmpty()) {
                    sender.replyKey("player-not-found");
                    return;
                }
                boolean exempt = online.map(ref -> ref.hasPermission(Permissions.CHAT_IGNORE_EXEMPT))
                        .orElseGet(() -> core.getPermissionService().has(uuid.get(),
                                Permissions.CHAT_IGNORE_EXEMPT));
                Outcome outcome = apply(core.notifications().preferences(player.getUuid()),
                        player.getUsername(), name, true, exempt);
                if (outcome == Outcome.ADDED) {
                    core.notifications().savePreferences(player.getUuid());
                }
                reply(sender, outcome, name);
            });
        }
    }

    private final class UnignoreCommand extends MysticCommand {
        private final RequiredArg<String> target = withRequiredArg("player", "Player to stop ignoring",
                MysticArgTypes.dynamic(IgnoreSubModule.this::ignoredNames));

        UnignoreCommand() {
            super(IgnoreSubModule.this.core, "unignore", "Stop ignoring a player.");
            requirePermission(Permissions.CHAT_IGNORE);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            PlayerRef player = playerFor(sender).orElse(null);
            if (player == null) {
                return;
            }
            String name = sender.get(target).trim();
            Outcome outcome = apply(core.notifications().preferences(player.getUuid()),
                    player.getUsername(), name, false, false);
            if (outcome == Outcome.REMOVED) {
                core.notifications().savePreferences(player.getUuid());
            }
            reply(sender, outcome, name);
        }
    }
}
