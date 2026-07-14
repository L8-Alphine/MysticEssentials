package org.hyzionstudios.mysticessentials.modules.teleportation.rtp;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.api.rtp.RtpCancelReason;
import org.hyzionstudios.mysticessentials.api.rtp.RtpDestinationRequest;
import org.hyzionstudios.mysticessentials.api.rtp.RtpProfile;
import org.hyzionstudios.mysticessentials.api.rtp.RtpRequest;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.platform.Conversions;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * {@code /rtpadmin} — administrative Random Teleport tooling (spec §1 admin table
 * and §9 spread / queue-login). Kept separate from {@code /rtp} so world and
 * profile names never collide with player names. Gated by
 * {@code mysticessentials.teleport.rtp.admin}.
 */
final class RtpAdminCommand extends MysticCommand {

    private final RtpSubsystem rtp;

    RtpAdminCommand(MysticCore core, RtpSubsystem rtp) {
        super(core, "rtpadmin", "Administer Random Teleport profiles.");
        this.rtp = rtp;
        addAliases("rtpa");
        requirePermission(Permissions.RTP_ADMIN);
        allowExtraArguments();
    }

    @Override
    protected void run(MysticCommandSender sender) {
        String[] args = sender.args();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> reload(sender);
            case "test" -> test(sender, arg(args, 1));
            case "preview" -> preview(sender, arg(args, 1));
            case "debug" -> debug(sender, arg(args, 1));
            case "enable" -> setWorldEnabled(sender, arg(args, 1), true);
            case "disable" -> setWorldEnabled(sender, arg(args, 1), false);
            case "setcenter" -> setCenter(sender, arg(args, 1));
            case "clearcache" -> clearCache(sender, arg(args, 1));
            case "queue" -> queue(sender);
            case "ui", "editor" -> openEditor(sender);
            case "cancel" -> cancel(sender, arg(args, 1));
            case "spread" -> spread(sender, arg(args, 1), arg(args, 2));
            case "queue-login", "queuelogin" -> queueLogin(sender, arg(args, 1), arg(args, 2));
            default -> help(sender);
        }
    }

    private void reload(MysticCommandSender sender) {
        rtp.reload();
        sender.replyKey("rtp-admin-reloaded", Map.of("count", Integer.toString(rtp.config().profiles.size())));
    }

    private void test(MysticCommandSender sender, String profileId) {
        RtpProfile profile = requireProfile(sender, profileId);
        if (profile == null) {
            return;
        }
        sender.replyKey("rtp-admin-test-start", Map.of("profile", profile.id));
        rtp.service().findDestination(RtpDestinationRequest.of(profile.id)).thenAccept(result -> {
            if (result.found()) {
                sender.replyKey("rtp-admin-test-found", Map.of(
                        "x", Integer.toString((int) Math.floor(result.location().getX())),
                        "y", Integer.toString((int) Math.floor(result.location().getY())),
                        "z", Integer.toString((int) Math.floor(result.location().getZ())),
                        "attempts", Integer.toString(result.attempts())));
            } else {
                sender.replyKey("rtp-admin-test-none", Map.of(
                        "attempts", Integer.toString(result.attempts()),
                        "reason", String.valueOf(result.failureReason())));
            }
        });
    }

    private void preview(MysticCommandSender sender, String profileId) {
        RtpProfile p = requireProfile(sender, profileId);
        if (p == null) {
            return;
        }
        double cx = p.center == null ? 0 : p.center.x;
        double cz = p.center == null ? 0 : p.center.z;
        sender.replyKey("rtp-admin-preview", Map.of(
                "profile", p.id,
                "world", p.world,
                "shape", p.shape.name().toLowerCase(Locale.ROOT),
                "center", (int) cx + ", " + (int) cz,
                "min", Integer.toString(p.minimumRadius),
                "max", Integer.toString(p.maximumRadius),
                "y", p.minimumY + "-" + p.maximumY));
    }

    private void debug(MysticCommandSender sender, String profileId) {
        RtpProfile profile = requireProfile(sender, profileId);
        if (profile == null) {
            return;
        }
        sender.replyKey("rtp-admin-debug-start", Map.of("profile", profile.id));
        rtp.service().findDestination(RtpDestinationRequest.of(profile.id)).thenAccept(result -> {
            sender.replyKey("rtp-admin-debug-summary", Map.of(
                    "found", result.found() ? "yes" : "no",
                    "attempts", Integer.toString(result.attempts())));
            if (result.rejectionTally() != null) {
                result.rejectionTally().forEach((reason, count) ->
                        sender.replyKey("rtp-admin-debug-line", Map.of(
                                "reason", reason, "count", Integer.toString(count))));
            }
        });
    }

    private void setWorldEnabled(MysticCommandSender sender, String world, boolean enabled) {
        if (world == null) {
            sender.replyKey("rtp-admin-usage-world");
            return;
        }
        RandomTeleportConfig.Defaults defaults = rtp.config().randomTeleport;
        defaults.disabledWorlds.removeIf(w -> w.equalsIgnoreCase(world));
        defaults.enabledWorlds.removeIf(w -> w.equalsIgnoreCase(world));
        if (enabled) {
            if (!defaults.enabledWorlds.isEmpty()) {
                defaults.enabledWorlds.add(world);
            }
        } else {
            defaults.disabledWorlds.add(world);
        }
        rtp.saveConfig();
        sender.replyKey(enabled ? "rtp-admin-world-enabled" : "rtp-admin-world-disabled",
                Map.of("world", world));
    }

    private void setCenter(MysticCommandSender sender, String profileId) {
        if (!sender.isPlayer()) {
            sender.replyKey("player-only");
            return;
        }
        RtpProfile profile = requireProfile(sender, profileId);
        if (profile == null) {
            return;
        }
        var loc = Conversions.capture(sender.player().orElseThrow());
        profile.center.x = loc.getX();
        profile.center.z = loc.getZ();
        rtp.saveConfig();
        sender.replyKey("rtp-admin-setcenter", Map.of(
                "profile", profile.id,
                "x", Integer.toString((int) loc.getX()),
                "z", Integer.toString((int) loc.getZ())));
    }

    private void clearCache(MysticCommandSender sender, String profileId) {
        rtp.engine().clearCache(profileId);
        sender.replyKey("rtp-admin-cache-cleared",
                Map.of("profile", profileId == null ? "all" : profileId));
    }

    private void queue(MysticCommandSender sender) {
        sender.replyKey("rtp-admin-queue-header", Map.of(
                "active", Integer.toString(rtp.engine().activeCount()),
                "queued", Integer.toString(rtp.engine().queuedCount())));
        List<String> lines = rtp.engine().describeQueue();
        if (lines.isEmpty()) {
            sender.replyKey("rtp-admin-queue-empty");
        } else {
            for (String line : lines) {
                sender.reply("&7 - &f" + line);
            }
        }
    }

    private void cancel(MysticCommandSender sender, String playerName) {
        if (playerName == null) {
            sender.replyKey("rtp-admin-usage-cancel");
            return;
        }
        PlayerRef target = core.platform().findPlayerByName(playerName).orElse(null);
        if (target == null) {
            sender.replyKey("player-not-found");
            return;
        }
        boolean cancelled = rtp.service().cancel(target.getUuid(), RtpCancelReason.ADMIN);
        sender.replyKey(cancelled ? "rtp-admin-cancel-ok" : "rtp-admin-cancel-none",
                Map.of("player", target.getUsername()));
    }

    /**
     * {@code /rtpadmin spread <profile> <all|world:name>} — random-teleports a
     * group. Each player gets an independent safe search; the large sampling area
     * naturally separates them (strict inter-player minimum distance is not
     * enforced here).
     */
    private void spread(MysticCommandSender sender, String profileId, String selector) {
        RtpProfile profile = requireProfile(sender, profileId);
        if (profile == null) {
            return;
        }
        if (selector == null) {
            sender.replyKey("rtp-admin-usage-spread");
            return;
        }
        String worldFilter = selector.toLowerCase(Locale.ROOT).startsWith("world:")
                ? selector.substring("world:".length()) : null;
        int moved = 0;
        for (PlayerRef player : core.platform().onlinePlayers()) {
            if (worldFilter != null && !core.platform().worldNameOf(player)
                    .map(w -> w.equalsIgnoreCase(worldFilter)).orElse(false)) {
                continue;
            }
            rtp.service().teleport(RtpRequest.builder(player.getUuid())
                    .profileId(profile.id).actor(sender.isPlayer() ? sender.uuid() : null)
                    .force(true).silent(false).build());
            moved++;
        }
        sender.replyKey("rtp-admin-spread", Map.of(
                "count", Integer.toString(moved), "profile", profile.id));
    }

    private void queueLogin(MysticCommandSender sender, String playerName, String profileId) {
        RtpProfile profile = requireProfile(sender, profileId);
        if (profile == null) {
            return;
        }
        if (playerName == null) {
            sender.replyKey("rtp-admin-usage-queuelogin");
            return;
        }
        UUID actor = sender.isPlayer() ? sender.uuid() : null;
        core.getPlayerProfileService().resolveUuid(playerName).thenAccept(resolved -> {
            if (resolved.isEmpty()) {
                sender.replyKey("player-not-found");
                return;
            }
            rtp.queueLogin(resolved.get(), profile.id, actor);
            sender.replyKey("rtp-admin-queuelogin-ok", Map.of(
                    "player", playerName, "profile", profile.id));
        });
    }

    private void openEditor(MysticCommandSender sender) {
        if (!sender.isPlayer()) {
            sender.replyKey("player-only");
            return;
        }
        core.platform().openPage(sender.player().orElseThrow(),
                new RtpPages.AdminPage(core, rtp, sender.player().orElseThrow()));
    }

    private void help(MysticCommandSender sender) {
        sender.replyKey("rtp-admin-help");
    }

    private RtpProfile requireProfile(MysticCommandSender sender, String profileId) {
        if (profileId == null) {
            sender.replyKey("rtp-admin-usage-profile");
            return null;
        }
        RtpProfile profile = rtp.config().profiles.get(profileId);
        if (profile == null) {
            sender.replyKey("rtp-info-unknown", Map.of("name", profileId));
            return null;
        }
        return profile;
    }

    private static String arg(String[] args, int index) {
        return index < args.length ? args[index] : null;
    }
}
