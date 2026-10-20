package org.hyzionstudios.mysticessentials.modules.lootbox.command;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.lootbox.LootBoxModule;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.GrantSource;
import org.hyzionstudios.mysticessentials.modules.lootbox.hytale.BlockTargets;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.CrateInstance;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeySerial;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.CrateService;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.CrateEditorPage;
import org.hyzionstudios.mysticessentials.modules.lootbox.validate.DraftValidator;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * {@code /lootbox} (alias {@code /lb}). Parsing is manual, like
 * {@code /playervault}, so one entry point covers players and staff:
 *
 * <ul>
 *   <li>no args → catalog; {@code preview <id>}; {@code history [player]}; {@code pending};</li>
 *   <li>{@code key give|revoke|info|list|publish …};</li>
 *   <li>{@code publish|rollback|disable|enable …}; {@code simulate <id> [draws]};</li>
 *   <li>{@code crate select|convert|edit|rebind|remove|info|verify|enable …};</li>
 *   <li>{@code reconcile <openingId> regrant|dismiss}; {@code admin}; {@code reload}.</li>
 * </ul>
 *
 * <p>Every staff branch checks its own node here, on the server.</p>
 */
public final class LootBoxCommand extends MysticCommand {

    private static final String USAGE = "/lootbox [preview <id>|history|pending|key …|publish <id>|crate …|admin]";

    private final LootBoxModule module;

    public LootBoxCommand(MysticCore core, LootBoxModule module, String name, java.util.List<String> aliases) {
        super(core, name, "Open loot boxes, see their exact odds, and manage them.");
        this.module = module;
        if (aliases != null && !aliases.isEmpty()) {
            addAliases(aliases.toArray(String[]::new));
        }
        requirePermission(Permissions.LOOTBOX_USE);
        allowExtraArguments();
    }

    @Override
    protected void run(MysticCommandSender sender) {
        String[] args = sender.args();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> player(sender).ifPresent(player -> module.ui().openCatalog(player));
            case "preview" -> preview(sender, args);
            case "history" -> history(sender, args);
            case "pending" -> player(sender).ifPresent(player -> module.ui().openPending(player));
            case "key", "keys" -> key(sender, args);
            case "publish" -> publish(sender, args);
            case "rollback" -> rollback(sender, args);
            case "disable", "enable" -> toggle(sender, args, "disable".equals(sub));
            case "simulate" -> simulate(sender, args);
            case "crate", "crates" -> crate(sender, args);
            case "reconcile" -> reconcile(sender, args);
            case "admin" -> {
                if (allowed(sender, Permissions.LOOTBOX_ADMIN)) {
                    player(sender).ifPresent(player -> module.ui().openManager(player));
                }
            }
            case "reload" -> {
                if (allowed(sender, Permissions.LOOTBOX_ADMIN)) {
                    module.onReload();
                    sender.replyKey("lootbox-reloaded", Map.of(
                            "pools", Integer.toString(module.registry().drafts().size()),
                            "keys", Integer.toString(module.registry().keyDrafts().size())));
                }
            }
            default -> usage(sender, USAGE);
        }
    }

    // ----- Player commands ----------------------------------------------------

    private void preview(MysticCommandSender sender, String[] args) {
        if (args.length < 2) {
            usage(sender, "/lootbox preview <id>");
            return;
        }
        player(sender).ifPresent(player -> module.ui().openPreview(player, args[1].toLowerCase(Locale.ROOT), null));
    }

    private void history(MysticCommandSender sender, String[] args) {
        Optional<PlayerRef> viewer = player(sender);
        if (viewer.isEmpty()) {
            return;
        }
        if (args.length < 2) {
            if (allowed(sender, Permissions.LOOTBOX_HISTORY)) {
                module.ui().openHistory(viewer.get(), viewer.get().getUuid(), viewer.get().getUsername());
            }
            return;
        }
        if (!allowed(sender, Permissions.LOOTBOX_AUDIT)) {
            return;
        }
        resolve(sender, args[1], (uuid, name) -> module.ui().openHistory(viewer.get(), uuid, name));
    }

    // ----- Keys ---------------------------------------------------------------

    private void key(MysticCommandSender sender, String[] args) {
        String action = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "give" -> keyGive(sender, args);
            case "revoke" -> {
                if (!allowed(sender, Permissions.LOOTBOX_KEYS_REVOKE)) {
                    return;
                }
                if (args.length < 3) {
                    usage(sender, "/lootbox key revoke <serial>");
                    return;
                }
                module.keys().revoke(args[2], sender.name()).thenAccept(revoked -> sender.reply(revoked
                        ? "&aRevoked key &f" + args[2] + "&a." : "&cNo spendable key with serial &f" + args[2] + "&c."));
            }
            case "info" -> {
                if (!allowed(sender, Permissions.LOOTBOX_KEYS)) {
                    return;
                }
                if (args.length < 3) {
                    usage(sender, "/lootbox key info <serial>");
                    return;
                }
                module.keys().serial(args[2]).thenAccept(serial -> sender.reply(describe(serial, args[2])));
            }
            case "list" -> {
                if (!allowed(sender, Permissions.LOOTBOX_KEYS)) {
                    return;
                }
                sender.reply("&d&lPublished keys &7(" + module.registry().keys().size() + ")");
                module.registry().keys().forEach(key -> sender.reply("&7- &f" + key.spec.id + " &8v" + key.version
                        + " &7base &f" + key.spec.baseItemId + " &7uses &f" + key.spec.uses));
            }
            case "publish" -> {
                if (!allowed(sender, Permissions.LOOTBOX_PUBLISH)) {
                    return;
                }
                if (args.length < 3) {
                    usage(sender, "/lootbox key publish <keyId>");
                    return;
                }
                String keyId = DraftValidator.normaliseKeyId(args[2]);
                module.registry().publishKey(keyId, sender.name()).thenAccept(result -> sender.reply(result.ok()
                        ? (result.unchanged() ? "&7Key &f" + keyId + " &7is unchanged (version " + result.revision() + ")."
                                : "&aPublished key &f" + keyId + " &aversion &f" + result.revision() + "&a.")
                        : "&cCould not publish key &f" + keyId + "&c: " + String.join("; ", result.problems())));
            }
            default -> usage(sender, "/lootbox key <give|revoke|info|list|publish> …");
        }
    }

    /** {@code /lootbox key give <player> <keyId> [count] [virtual] [source=…]} */
    private void keyGive(MysticCommandSender sender, String[] args) {
        if (!allowed(sender, Permissions.LOOTBOX_KEYS_GIVE)) {
            return;
        }
        if (args.length < 4) {
            usage(sender, "/lootbox key give <player> <keyId> [count] [virtual] [source=GAMEPLAY|QUEST|…]");
            return;
        }
        String keyId = DraftValidator.normaliseKeyId(args[3]);
        int count = 1;
        boolean virtual = false;
        GrantSource source = sender.isPlayer() ? GrantSource.ADMIN_GRANT
                : GrantSource.parse(module.config().keys.consoleGrantSource, GrantSource.ADMIN_GRANT);
        for (int i = 4; i < args.length; i++) {
            String arg = args[i].toLowerCase(Locale.ROOT);
            if (arg.equals("virtual")) {
                virtual = true;
            } else if (arg.startsWith("source=")) {
                source = GrantSource.parse(arg.substring(7), source);
            } else {
                try {
                    count = Math.max(1, Integer.parseInt(arg));
                } catch (NumberFormatException e) {
                    usage(sender, "/lootbox key give <player> <keyId> [count] [virtual] [source=…]");
                    return;
                }
            }
        }
        boolean asVirtual = virtual;
        GrantSource finalSource = source;
        int finalCount = count;
        resolve(sender, args[2], (uuid, name) -> module.keys()
                .grant(uuid, keyId, finalCount, asVirtual, finalSource, sender.name())
                .thenAccept(result -> {
                    if (!result.ok()) {
                        sender.reply("&cCould not give keys: " + result.problem());
                        return;
                    }
                    sender.replyKey("lootbox-key-given", Map.of("count", Integer.toString(finalCount),
                            "key", keyId, "player", name));
                    if (result.pending() > 0) {
                        sender.reply("&7" + result.pending() + " went to their pending inbox.");
                    }
                    core.platform().findPlayer(uuid).ifPresent(online -> core.getMessageService().sendKey(online,
                            "lootbox-key-received", Map.of("count", Integer.toString(finalCount), "key", keyId)));
                }));
    }

    private static String describe(KeySerial serial, String id) {
        if (serial == null) {
            return "&cNo key with serial &f" + id + "&c.";
        }
        return "&d" + serial.serial + " &7key &f" + serial.keyId + " &8v" + serial.defVersion + " &7state &f"
                + serial.state + " &7uses left &f" + serial.usesLeft + " &7owner &f" + serial.owner + " &7source &f"
                + serial.source + (serial.redeemedBy == null ? "" : " &7last opening &f" + serial.redeemedBy);
    }

    // ----- Pools --------------------------------------------------------------

    private void publish(MysticCommandSender sender, String[] args) {
        if (!allowed(sender, Permissions.LOOTBOX_PUBLISH)) {
            return;
        }
        if (args.length < 2) {
            usage(sender, "/lootbox publish <id>");
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        module.registry().publish(id, sender.name(), sender.hasPermission(Permissions.LOOTBOX_REWARDS_COMMAND))
                .thenAccept(result -> {
                    if (result.ok()) {
                        sender.replyKey(result.unchanged() ? "lootbox-publish-unchanged" : "lootbox-published",
                                Map.of("pool", id, "revision", Integer.toString(result.revision()), "hash", result.hash()));
                    } else {
                        sender.replyKey("lootbox-publish-failed", Map.of("pool", id,
                                "reason", String.join("; ", result.problems())));
                    }
                });
    }

    private void rollback(MysticCommandSender sender, String[] args) {
        if (!allowed(sender, Permissions.LOOTBOX_PUBLISH)) {
            return;
        }
        if (args.length < 3) {
            usage(sender, "/lootbox rollback <id> <revision>");
            return;
        }
        int revision;
        try {
            revision = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            usage(sender, "/lootbox rollback <id> <revision>");
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        module.registry().rollback(id, revision, sender.name()).thenAccept(result -> sender.reply(result.ok()
                ? "&aRepublished revision &f" + revision + " &aof &f" + id + " &aas revision &f" + result.revision()
                        + " &7(odds " + result.hash() + ")"
                : "&cRollback failed: " + String.join("; ", result.problems())));
    }

    private void toggle(MysticCommandSender sender, String[] args, boolean disable) {
        if (!allowed(sender, Permissions.LOOTBOX_ADMIN)) {
            return;
        }
        if (args.length < 2) {
            usage(sender, "/lootbox " + (disable ? "disable" : "enable") + " <id>");
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        module.registry().setDisabled(id, disable, sender.name()).thenAccept(changed -> sender.reply(changed
                ? (disable ? "&e" + id + " is now closed." : "&a" + id + " is open again.")
                : "&c" + id + " has never been published."));
    }

    private void simulate(MysticCommandSender sender, String[] args) {
        if (!allowed(sender, Permissions.LOOTBOX_SIMULATE)) {
            return;
        }
        if (args.length < 2) {
            usage(sender, "/lootbox simulate <id> [draws]");
            return;
        }
        LootBoxRevision revision = module.registry().active(args[1].toLowerCase(Locale.ROOT)).orElse(null);
        if (revision == null) {
            sender.reply("&c" + args[1] + " has no published revision to simulate.");
            return;
        }
        long trials = 100_000;
        if (args.length > 2) {
            try {
                trials = Long.parseLong(args[2]);
            } catch (NumberFormatException e) {
                usage(sender, "/lootbox simulate <id> [draws]");
                return;
            }
        }
        module.opening().simulate(revision, trials).thenAccept(counts ->
                module.ui().simulationLines(revision, counts).forEach(sender::reply));
    }

    // ----- Crates -------------------------------------------------------------

    private void crate(MysticCommandSender sender, String[] args) {
        String action = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "select", "info" -> {
                if (!allowed(sender, "select".equals(action) ? Permissions.LOOTBOX_CRATES_PLACE
                        : Permissions.LOOTBOX_CRATES_MANAGE)) {
                    return;
                }
                player(sender).ifPresent(player -> onWorld(sender, player, (store, entity) -> {
                    BlockTargets.Block block = BlockTargets.target(entity, store, module.config().crates.selectReach);
                    if (block == null) {
                        sender.reply("&cLook at a block within reach.");
                        return;
                    }
                    Optional<CrateInstance> crate = module.crates().at(block.world(), block.x(), block.y(), block.z());
                    var report = module.crates().check(block, crate.orElse(null));
                    sender.reply("&7Looking at &f" + block.key() + " &7at &f" + block.x() + "," + block.y() + ","
                            + block.z() + (crate.map(c -> " &d- crate " + c.crateId + " → " + c.lootboxId
                                    + (c.disabled ? " &c(disabled: " + c.disabledReason + ")" : "")).orElse(
                                    report.ok() ? " &a- can become a crate" : " &c- " + report.problem())));
                    if (crate.isEmpty()) {
                        report.warnings().forEach(warning -> sender.reply("&e! " + warning));
                    }
                }));
            }
            case "convert" -> {
                if (!allowed(sender, Permissions.LOOTBOX_CRATES_PLACE)) {
                    return;
                }
                if (args.length < 3) {
                    usage(sender, "/lootbox crate convert <pool> [crateId]");
                    return;
                }
                String lootboxId = args[2].toLowerCase(Locale.ROOT);
                if (module.registry().active(lootboxId).isEmpty()) {
                    sender.reply("&c" + lootboxId + " has no published revision; publish it first.");
                    return;
                }
                String crateId = args.length > 3 ? args[3] : null;
                player(sender).ifPresent(player -> onWorld(sender, player, (store, entity) -> {
                    CrateService.ConvertResult result = module.crates().convert(
                            BlockTargets.target(entity, store, module.config().crates.selectReach), lootboxId, crateId,
                            sender.name());
                    if (result.problem() != null) {
                        sender.replyKey("lootbox-crate-incompatible", Map.of("reason", result.problem()));
                        return;
                    }
                    sender.replyKey("lootbox-crate-converted", Map.of("crate", result.crate().crateId,
                            "pool", lootboxId));
                    if (result.warning() != null) {
                        sender.reply("&eNote: " + result.warning() + ".");
                    }
                }));
            }
            case "remove" -> {
                if (!allowed(sender, Permissions.LOOTBOX_CRATES_MANAGE)) {
                    return;
                }
                if (args.length > 2) {
                    sender.reply(module.crates().remove(args[2], sender.name()).map(c -> "&aRemoved crate &f" + c.crateId)
                            .orElse("&cNo crate called &f" + args[2]));
                    return;
                }
                player(sender).ifPresent(player -> onWorld(sender, player, (store, entity) -> {
                    BlockTargets.Block block = BlockTargets.target(entity, store, module.config().crates.selectReach);
                    Optional<CrateInstance> crate = block == null ? Optional.empty()
                            : module.crates().at(block.world(), block.x(), block.y(), block.z());
                    sender.reply(crate.flatMap(c -> module.crates().remove(c.crateId, sender.name()))
                            .map(c -> "&aRemoved crate &f" + c.crateId)
                            .orElse("&cLook at a crate, or name it: /lootbox crate remove <crateId>"));
                }));
            }
            case "enable" -> {
                if (!allowed(sender, Permissions.LOOTBOX_CRATES_MANAGE)) {
                    return;
                }
                if (args.length < 3) {
                    usage(sender, "/lootbox crate enable <crateId>");
                    return;
                }
                sender.reply(module.crates().get(args[2]).map(crate -> {
                    module.crates().enable(crate, sender.name());
                    return "&aCrate &f" + crate.crateId + " &ais active again.";
                }).orElse("&cNo crate called &f" + args[2]));
            }
            case "verify" -> {
                if (!allowed(sender, Permissions.LOOTBOX_CRATES_MANAGE)) {
                    return;
                }
                player(sender).ifPresent(player -> onWorld(sender, player, (store, entity) -> {
                    var verified = module.crates().verify(store.getExternalData().getWorld());
                    sender.reply("&d&lCrates in this world &7(" + verified.checked() + ")");
                    verified.lines().forEach(line -> sender.reply("&7- " + line));
                }));
            }
            case "list" -> {
                if (!allowed(sender, Permissions.LOOTBOX_CRATES_MANAGE)) {
                    return;
                }
                sender.reply("&d&lCrates &7(" + module.crates().list().size() + ")");
                module.crates().list().forEach(crate -> sender.reply("&7- &f" + crate.crateId + " &7→ &f"
                        + crate.lootboxId + " &7at " + crate.position() + (crate.disabled ? " &c(disabled)" : "")));
            }
            case "rebind" -> {
                if (!allowed(sender, Permissions.LOOTBOX_CRATES_PLACE)) {
                    return;
                }
                if (args.length < 4) {
                    usage(sender, "/lootbox crate rebind <crateId> <pool>");
                    return;
                }
                String lootboxId = args[3].toLowerCase(Locale.ROOT);
                if (module.registry().active(lootboxId).isEmpty()) {
                    sender.reply("&c" + lootboxId + " has no published revision; publish it first.");
                    return;
                }
                sender.reply(module.crates().get(args[2]).map(crate -> {
                    String previous = crate.lootboxId;
                    module.crates().rebind(crate, lootboxId, sender.name());
                    return "&aCrate &f" + crate.crateId + " &anow opens &f" + lootboxId + " &7(was " + previous + ").";
                }).orElse("&cNo crate called &f" + args[2]));
            }
            case "edit" -> {
                if (!allowed(sender, Permissions.LOOTBOX_CRATES_MANAGE)) {
                    return;
                }
                player(sender).ifPresent(player -> onWorld(sender, player, (store, entity) -> {
                    Optional<CrateInstance> crate;
                    if (args.length > 2) {
                        crate = module.crates().get(args[2]);
                    } else {
                        BlockTargets.Block block = BlockTargets.target(entity, store, module.config().crates.selectReach);
                        crate = block == null ? Optional.empty()
                                : module.crates().at(block.world(), block.x(), block.y(), block.z());
                    }
                    if (crate.isEmpty()) {
                        sender.reply("&cLook at a crate, or name it: /lootbox crate edit <crateId>");
                        return;
                    }
                    core.platform().openPage(player, CrateEditorPage.forCrate(core, player, module, crate.get(), null));
                }));
            }
            default -> usage(sender, "/lootbox crate <select|convert|edit|rebind|remove|enable|verify|list|info> …");
        }
    }

    private void reconcile(MysticCommandSender sender, String[] args) {
        if (!allowed(sender, Permissions.LOOTBOX_RESTORE)) {
            return;
        }
        if (args.length < 3 || !(args[2].equalsIgnoreCase("regrant") || args[2].equalsIgnoreCase("dismiss"))) {
            usage(sender, "/lootbox reconcile <openingId> <regrant|dismiss>");
            return;
        }
        module.opening().reconcile(args[1], args[2].equalsIgnoreCase("regrant"), sender.name())
                .thenAccept(result -> sender.reply("&7Reconcile &f" + args[1] + "&7: &f" + result));
    }

    // ----- Helpers ------------------------------------------------------------

    private interface WorldAction {
        void run(com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> store,
                com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> entity);
    }

    private void onWorld(MysticCommandSender sender, PlayerRef player, WorldAction action) {
        boolean dispatched = core.platform().runOnEntityThread(player, (store, entity, world) -> action.run(store, entity));
        if (!dispatched) {
            sender.reply("&cYou need to be in a world for that.");
        }
    }

    private Optional<PlayerRef> player(MysticCommandSender sender) {
        Optional<PlayerRef> player = sender.player();
        if (player.isEmpty()) {
            sender.replyKey("player-only");
        }
        return player;
    }

    private boolean allowed(MysticCommandSender sender, String node) {
        if (!sender.isPlayer() || sender.hasPermission(node)) {
            return true;
        }
        sender.replyKey("no-permission");
        return false;
    }

    private void resolve(MysticCommandSender sender, String name, BiConsumer<UUID, String> action) {
        Optional<PlayerRef> online = core.platform().findPlayerByName(name);
        if (online.isPresent()) {
            action.accept(online.get().getUuid(), online.get().getUsername());
            return;
        }
        core.getPlayerProfileService().resolveUuid(name).thenAccept(resolved -> {
            if (resolved.isPresent()) {
                action.accept(resolved.get(), name);
            } else {
                sender.replyKey("player-not-found");
            }
        });
    }

    private static void usage(MysticCommandSender sender, String usage) {
        sender.replyKey("lootbox-usage", Map.of("usage", usage));
    }
}
