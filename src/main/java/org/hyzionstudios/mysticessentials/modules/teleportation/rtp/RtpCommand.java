package org.hyzionstudios.mysticessentials.modules.teleportation.rtp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.api.rtp.RtpCancelReason;
import org.hyzionstudios.mysticessentials.api.rtp.RtpProfile;
import org.hyzionstudios.mysticessentials.api.rtp.RtpRequest;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * {@code /rtp} — the player-facing Random Teleport command. Free-form parsed
 * (like {@code /mail} and {@code /pwarp}) so the many usage forms from spec §1
 * coexist without positional-variant ambiguity:
 *
 * <pre>
 * /rtp                         self, default profile
 * /rtp &lt;player&gt;                admin: another online player
 * /rtp world &lt;world&gt; [player]
 * /rtp profile &lt;profile&gt; [player]
 * /rtp biome &lt;biome&gt;
 * /rtp cancel | status | info [world/profile]
 * </pre>
 *
 * Trailing {@code --force}, {@code --silent}, {@code --bypass-cost} flags are
 * honoured on the admin forms.
 */
final class RtpCommand extends MysticCommand {

    private final RtpSubsystem rtp;

    RtpCommand(org.hyzionstudios.mysticessentials.core.MysticCore core, RtpSubsystem rtp) {
        super(core, "rtp", "Randomly teleport to a safe location.");
        this.rtp = rtp;
        requirePermission(Permissions.RTP_USE);
        allowExtraArguments();
    }

    @Override
    protected void run(MysticCommandSender sender) {
        if (rtp.service() == null || !rtp.config().enabled) {
            sender.replyKey("rtp-disabled");
            return;
        }
        List<String> args = new ArrayList<>();
        Flags flags = new Flags();
        for (String token : sender.args()) {
            switch (token.toLowerCase(Locale.ROOT)) {
                case "--force" -> flags.force = true;
                case "--silent" -> flags.silent = true;
                case "--bypass-cost", "--bypasscost" -> flags.bypassCost = true;
                default -> args.add(token);
            }
        }

        String keyword = args.isEmpty() ? "" : args.get(0).toLowerCase(Locale.ROOT);
        boolean uiDefault = rtp.config().randomTeleport.openUiOnRtp;
        switch (keyword) {
            case "" -> {
                if (uiDefault && sender.isPlayer()) {
                    openMenu(sender);
                } else {
                    runRtp(sender, null, null, null, null, flags);
                }
            }
            case "menu", "ui" -> openMenu(sender);
            case "cancel" -> cancel(sender);
            case "status" -> status(sender);
            case "info" -> info(sender, args.size() > 1 ? args.get(1) : null);
            case "world" -> {
                if (args.size() < 2) {
                    sender.replyKey("rtp-usage-world");
                } else {
                    runRtp(sender, args.size() > 2 ? args.get(2) : null, null, args.get(1), null, flags);
                }
            }
            case "profile" -> {
                if (args.size() < 2) {
                    sender.replyKey("rtp-usage-profile");
                } else {
                    runRtp(sender, args.size() > 2 ? args.get(2) : null, args.get(1), null, null, flags);
                }
            }
            case "biome" -> {
                if (!sender.hasPermission(Permissions.RTP_BIOME)) {
                    sender.replyKey("no-permission");
                } else if (args.size() < 2) {
                    sender.replyKey("rtp-usage-biome");
                } else {
                    runRtp(sender, null, null, null, args.get(1), flags);
                }
            }
            // Anything else is treated as a target player name (admin form).
            default -> runRtp(sender, args.get(0), null, null, null, flags);
        }
    }

    private void runRtp(MysticCommandSender sender, String targetName, String profileId, String world,
            String biome, Flags flags) {
        UUID actor = sender.isPlayer() ? sender.uuid() : null;
        UUID targetUuid;
        boolean adminInitiated;

        if (targetName != null) {
            PlayerRef target = core.platform().findPlayerByName(targetName)
                    .filter(ref -> actor == null || core.vanish().canSee(actor, ref.getUuid()))
                    .orElse(null);
            if (target == null) {
                sender.replyKey("player-not-found");
                return;
            }
            adminInitiated = actor == null || !target.getUuid().equals(actor);
            if (adminInitiated && !sender.hasPermission(Permissions.RTP_OTHERS)) {
                sender.replyKey("no-permission");
                return;
            }
            targetUuid = target.getUuid();
        } else {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            targetUuid = sender.uuid();
            adminInitiated = false;
        }

        RtpRequest request = RtpRequest.builder(targetUuid)
                .profileId(profileId)
                .world(world)
                .biome(biome)
                .actor(actor)
                .force(flags.force || adminInitiated)
                .silent(flags.silent)
                .bypassCost(flags.bypassCost)
                .build();

        rtp.service().teleport(request).thenAccept(result -> {
            if (!adminInitiated) {
                return; // the service already messaged the player directly.
            }
            String targetDisplay = core.platform().findPlayer(targetUuid)
                    .map(PlayerRef::getUsername).orElse(targetName);
            if (result.isSuccess()) {
                sender.replyKey("rtp-other-success", Map.of("player", targetDisplay));
            } else {
                sender.replyKey("rtp-other-failed", Map.of(
                        "player", targetDisplay,
                        "reason", result.status().name().toLowerCase(Locale.ROOT).replace('_', ' ')));
            }
        });
    }

    private void openMenu(MysticCommandSender sender) {
        if (!sender.isPlayer()) {
            sender.replyKey("player-only");
            return;
        }
        core.platform().openPage(sender.player().orElseThrow(),
                new RtpPages.SelectPage(core, rtp, sender.player().orElseThrow()));
    }

    private void cancel(MysticCommandSender sender) {
        if (!sender.isPlayer()) {
            sender.replyKey("player-only");
            return;
        }
        boolean cancelled = rtp.service().cancel(sender.uuid(), RtpCancelReason.COMMAND);
        sender.replyKey(cancelled ? "rtp-cancel-ok" : "rtp-cancel-none");
    }

    private void status(MysticCommandSender sender) {
        if (!sender.isPlayer()) {
            sender.replyKey("player-only");
            return;
        }
        UUID uuid = sender.uuid();
        sender.replyKey("rtp-status-header");
        rtp.service().activeSummary(uuid).ifPresentOrElse(summary -> {
            String[] parts = summary.split(":", 2);
            sender.replyKey("rtp-status-active", Map.of(
                    "phase", parts[0],
                    "profile", parts.length > 1 ? parts[1] : "?",
                    "queue", Integer.toString(rtp.engine().queuePosition(uuid))));
        }, () -> sender.replyKey("rtp-status-idle"));

        PlayerRef player = sender.player().orElseThrow();
        for (RtpProfile profile : rtp.service().getAvailableProfiles(player)) {
            long cd = rtp.service().cooldownRemaining(uuid, profile.id);
            sender.replyKey("rtp-status-profile", Map.of(
                    "profile", profile.displayNameOrId(),
                    "cooldown", cd > 0 ? cd + "s" : "ready"));
        }
    }

    private void info(MysticCommandSender sender, String name) {
        RtpProfile profile = resolveInfoProfile(name);
        if (profile == null) {
            sender.replyKey("rtp-info-unknown", Map.of("name", name == null ? "?" : name));
            return;
        }
        sender.replyKey("rtp-info", Map.of(
                "profile", profile.displayNameOrId(),
                "world", profile.world,
                "min", Integer.toString(profile.minimumRadius),
                "max", Integer.toString(profile.maximumRadius),
                "shape", profile.shape.name().toLowerCase(Locale.ROOT),
                "cost", profile.cost != null && profile.cost.enabled
                        ? core.getEconomyService().format(profile.cost.amount) : "free",
                "warmup", Integer.toString(profile.warmupSeconds),
                "cooldown", Integer.toString(profile.cooldownSeconds)));
    }

    private RtpProfile resolveInfoProfile(String name) {
        if (name == null || name.isBlank()) {
            String def = rtp.config().randomTeleport.defaultProfile;
            return rtp.config().profiles.get(def);
        }
        RtpProfile byId = rtp.config().profiles.get(name);
        if (byId != null) {
            return byId;
        }
        for (RtpProfile profile : rtp.config().profiles.values()) {
            if (name.equalsIgnoreCase(profile.world)) {
                return profile;
            }
        }
        return null;
    }

    private static final class Flags {
        boolean force;
        boolean silent;
        boolean bypassCost;
    }
}
