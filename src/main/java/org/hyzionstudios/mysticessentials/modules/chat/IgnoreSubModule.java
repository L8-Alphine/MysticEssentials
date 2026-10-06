package org.hyzionstudios.mysticessentials.modules.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.api.model.PlayerProfile;
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
 * {@code /ignore} and {@code /unignore}: each player's ignore list, kept by UUID in
 * their notification preferences (the blocked players {@code /mentions} counts). An
 * ignored player's chat lines, mentions and private messages do not reach the player
 * ignoring them. Lists show players by their current name.
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
     * Adds or removes {@code target} on {@code preferences}' ignore list.
     *
     * @param owner        the player whose list it is
     * @param targetExempt whether the target holds {@link Permissions#CHAT_IGNORE_EXEMPT}
     */
    static Outcome apply(NotificationPreferences preferences, UUID owner, UUID target, boolean ignore,
            boolean targetExempt) {
        if (!ignore) {
            return preferences.setIgnored(target, false) ? Outcome.REMOVED : Outcome.NOT_IGNORED;
        }
        if (target.equals(owner)) {
            return Outcome.SELF;
        }
        if (preferences.ignores(target, null)) {
            return Outcome.ALREADY;
        }
        if (targetExempt) {
            return Outcome.EXEMPT;
        }
        preferences.setIgnored(target, true);
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

    /** The command sender's online player while notifications run, or a reply saying why not. */
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

    /** A UUID for a typed name: the online player of that name, else any name seen before. */
    private CompletableFuture<Optional<UUID>> resolve(String name) {
        Optional<UUID> online = core.platform().findPlayerByName(name).map(PlayerRef::getUuid);
        return online.isPresent()
                ? CompletableFuture.completedFuture(online)
                : core.getPlayerProfileService().resolveUuid(name);
    }

    /** A player's current name without touching storage: online here, cached, or on the network. */
    private Optional<String> knownNameNow(UUID player) {
        return core.platform().findPlayer(player).map(PlayerRef::getUsername)
                .or(() -> core.getPlayerProfileService().getCached(player).map(PlayerProfile::getUsername))
                .or(() -> core.networkPlayers() == null
                        ? Optional.empty()
                        : core.networkPlayers().find(player).map(found -> found.username()));
    }

    /** Suggestions for {@code /unignore}: the current names the sender's list resolves to now. */
    private List<String> ignoredNames(CommandSender commandSender) {
        NotificationServiceImpl notifications = core.notifications();
        if (notifications == null || commandSender.getUuid() == null) {
            return List.of();
        }
        NotificationPreferences preferences = notifications.preferences(commandSender.getUuid());
        List<String> names = new ArrayList<>();
        for (UUID id : preferences.ignoredIds()) {
            knownNameNow(id).ifPresent(names::add);
        }
        names.addAll(preferences.pendingIgnoredNames());
        return names;
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
            NotificationPreferences preferences = core.notifications().preferences(player.getUuid());
            List<CompletableFuture<String>> names = new ArrayList<>();
            for (UUID id : preferences.ignoredIds()) {
                // The name the player goes by now, so a rename shows as such.
                names.add(core.getPlayerProfileService().lastKnownName(id)
                        .thenApply(found -> found.orElse(id.toString()))
                        .exceptionally(failure -> id.toString()));
            }
            List<String> pending = preferences.pendingIgnoredNames();
            CompletableFuture.allOf(names.toArray(CompletableFuture[]::new)).whenComplete((done, failure) -> {
                List<String> shown = new ArrayList<>();
                names.forEach(name -> shown.add(name.join()));
                shown.addAll(pending);
                if (shown.isEmpty()) {
                    sender.replyKey("chat-ignore-list-empty");
                    return;
                }
                sender.replyKey("chat-ignore-list", Map.of(
                        "count", Integer.toString(shown.size()),
                        "players", String.join(", ", shown)));
            });
        }
    }

    private final class IgnorePlayerVariant extends MysticCommand {
        private final RequiredArg<String> target = withRequiredArg("player", "Player to ignore",
                MysticArgTypes.NETWORK_PLAYER_NAME);

        IgnorePlayerVariant() {
            super(IgnoreSubModule.this.core, "Hide a player's chat, mentions and private messages.");
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
            String name = online.map(PlayerRef::getUsername).orElse(typed);
            resolve(typed).thenAccept(uuid -> {
                if (uuid.isEmpty()) {
                    sender.replyKey("player-not-found");
                    return;
                }
                boolean exempt = online.map(ref -> ref.hasPermission(Permissions.CHAT_IGNORE_EXEMPT))
                        .orElseGet(() -> core.getPermissionService().has(uuid.get(),
                                Permissions.CHAT_IGNORE_EXEMPT));
                NotificationPreferences preferences = core.notifications().preferences(player.getUuid());
                // An entry for this name from before the list was kept by UUID moves to it.
                boolean migrated = preferences.resolvePendingIgnore(typed, uuid.get());
                Outcome outcome = apply(preferences, player.getUuid(), uuid.get(), true, exempt);
                if (outcome == Outcome.ADDED || migrated) {
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
            NotificationPreferences preferences = core.notifications().preferences(player.getUuid());
            if (preferences.removePendingIgnore(name)) {
                core.notifications().savePreferences(player.getUuid());
                reply(sender, Outcome.REMOVED, name);
                return;
            }
            resolve(name).thenAccept(uuid -> {
                Outcome outcome = uuid.isEmpty()
                        ? Outcome.NOT_IGNORED
                        : apply(preferences, player.getUuid(), uuid.get(), false, false);
                if (outcome == Outcome.REMOVED) {
                    core.notifications().savePreferences(player.getUuid());
                }
                reply(sender, outcome, name);
            });
        }
    }
}
