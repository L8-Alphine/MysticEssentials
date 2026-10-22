package org.hyzionstudios.mysticessentials.modules.lootbox.command;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.message.MysticText;
import org.hyzionstudios.mysticessentials.modules.lootbox.LootBoxModule;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.GrantSource;
import org.hyzionstudios.mysticessentials.modules.lootbox.hytale.BlockTargets;
import org.hyzionstudios.mysticessentials.modules.lootbox.hytale.CrateVisuals;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.CrateInstance;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeySerial;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.ItemChance;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.CrateService;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.MotionRules;
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
 *   <li>no args → catalog; {@code preview <id>}; {@code history [player]}; {@code pending};
 *       {@code motion [reduced|full|default]};</li>
 *   <li>{@code key give|revoke|info|list|publish|grants …}; players' {@code key deposit} and
 *       {@code key withdraw <keyId> [count]};</li>
 *   <li>{@code publish|rollback|disable|enable …}; {@code simulate <id> [draws]};
 *       {@code chance <id> <itemId>}; {@code providers};</li>
 *   <li>{@code crate select|convert|edit|rebind|remove|info|verify|enable …};</li>
 *   <li>{@code crate effects [on|off]} for players;</li>
 *   <li>{@code reconcile <openingId> regrant|dismiss}; {@code admin}; {@code audit}; {@code reload}.</li>
 * </ul>
 *
 * <p>Every staff branch checks its own node here, on the server.</p>
 */
public final class LootBoxCommand extends MysticCommand {

    private static final String USAGE =
            "/lootbox [preview <id>|history|pending|motion|key …|publish <id>|crate …|admin|audit]";

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
            case "motion" -> motion(sender, args);
            case "key", "keys" -> key(sender, args);
            case "publish" -> publish(sender, args);
            case "rollback" -> rollback(sender, args);
            case "disable", "enable" -> toggle(sender, args, "disable".equals(sub));
            case "simulate" -> simulate(sender, args);
            case "chance" -> chance(sender, args);
            case "providers" -> providers(sender);
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
            case "audit" -> {
                if (allowed(sender, Permissions.LOOTBOX_AUDIT)) {
                    player(sender).ifPresent(player -> org.hyzionstudios.mysticessentials.modules.lootbox.ui
                            .AuditHistoryPage.openFor(core, player, module));
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

    /** {@code /lootbox motion [reduced|full|default]}: shows or sets the player's reduced-motion choice (P2-L). */
    private void motion(MysticCommandSender sender, String[] args) {
        Optional<PlayerRef> player = player(sender);
        if (player.isEmpty()) {
            return;
        }
        UUID id = player.get().getUuid();
        if (args.length < 2) {
            module.opening().describeMotion(id)
                    .thenAccept(setting -> sender.replyKey("lootbox-motion-status", Map.of("motion", setting)));
            return;
        }
        MotionRules.Choice choice = MotionRules.parse(args[1]);
        if (choice == null) {
            usage(sender, "/lootbox motion [reduced|full|default]");
            return;
        }
        module.opening().setMotion(id, choice)
                .thenAccept(setting -> sender.replyKey("lootbox-motion-set", Map.of("motion", setting)));
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
                if (org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyBatch.isBatch(args[2])) {
                    module.keys().batch(args[2]).thenAccept(batch -> sender.reply(describeBatch(batch, args[2])));
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
            case "grants" -> {
                if (!allowed(sender, Permissions.LOOTBOX_KEYS)) {
                    return;
                }
                if (args.length < 3) {
                    usage(sender, "/lootbox key grants <keyId>");
                    return;
                }
                String keyId = DraftValidator.normaliseKeyId(args[2]);
                if (module.registry().key(keyId).isEmpty()) {
                    sender.reply("&c" + keyId + " is not a published key.");
                    return;
                }
                player(sender).ifPresent(player -> org.hyzionstudios.mysticessentials.modules.lootbox.ui.KeyGrantsPage
                        .openLater(core, player, module, keyId));
            }
            case "deposit" -> {
                if (allowed(sender, Permissions.LOOTBOX_USE)) {
                    player(sender).ifPresent(player -> module.keys().conversions().deposit(player, null)
                            .thenAccept(result -> replyConversion(sender, result)));
                }
            }
            case "withdraw" -> keyWithdraw(sender, args);
            default -> usage(sender, "/lootbox key <give|revoke|info|list|publish|grants|deposit|withdraw> …");
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
                .grant(uuid, name, keyId, finalCount, asVirtual, finalSource, sender.name())
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

    /** {@code /lootbox key withdraw <keyId> [count]} */
    private void keyWithdraw(MysticCommandSender sender, String[] args) {
        if (!allowed(sender, Permissions.LOOTBOX_USE)) {
            return;
        }
        if (args.length < 3) {
            usage(sender, "/lootbox key withdraw <keyId> [count]");
            return;
        }
        String keyId = DraftValidator.normaliseKeyId(args[2].toLowerCase(Locale.ROOT));
        int count = 1;
        if (args.length > 3) {
            try {
                count = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                usage(sender, "/lootbox key withdraw <keyId> [count]");
                return;
            }
        }
        int finalCount = count;
        player(sender).ifPresent(player -> module.keys().conversions().withdraw(player, keyId, finalCount, null)
                .thenAccept(result -> replyConversion(sender, result)));
    }

    private static void replyConversion(MysticCommandSender sender,
            org.hyzionstudios.mysticessentials.modules.lootbox.service.ConversionService.Result result) {
        switch (result.status()) {
            case DEPOSITED -> sender.replyKey("lootbox-key-deposited", Map.of("count", Integer.toString(result.count()),
                    "key", result.keyId(), "balance", Integer.toString(result.balance())));
            case WITHDRAWN -> {
                sender.replyKey("lootbox-key-withdrawn", Map.of("count", Integer.toString(result.count()),
                        "key", result.keyId(), "balance", Integer.toString(result.balance())));
                if (result.pending() > 0) {
                    sender.replyKey("lootbox-key-withdrawn-pending", Map.of("count", Integer.toString(result.pending())));
                }
            }
            case PENDING, BUSY, DUPLICATE -> sender.reply("&e" + result.message());
            default -> sender.reply("&c" + result.message());
        }
    }

    private static String describeBatch(org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyBatch batch,
            String id) {
        if (batch == null) {
            return "&cNo key batch &f" + id + "&c.";
        }
        return "&d" + batch.batch + " &7stackable key &f" + batch.keyId + " &8v" + batch.defVersion + " &7"
                + (batch.revoked ? "&crevoked" : "&factive") + " &7left &f" + batch.remaining + "&7/&f" + batch.issued
                + " &7in use &f" + batch.reservations.size() + " &7owner &f" + batch.owner + " &7source &f" + batch.source;
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

    /**
     * {@code /lootbox chance <id> <itemId>} (P3-WP6): the exact chance that one
     * opening gives at least one {@code itemId}, through the main reward, a bundle
     * or a bonus roll. A player gets their own odds; the console the published ones.
     */
    private void chance(MysticCommandSender sender, String[] args) {
        if (args.length < 3) {
            usage(sender, "/lootbox chance <id> <itemId>");
            return;
        }
        LootBoxRevision revision = module.registry().active(args[1].toLowerCase(Locale.ROOT)).orElse(null);
        if (revision == null) {
            sender.replyKey("lootbox-unknown-pool", Map.of("pool", args[1]));
            return;
        }
        String itemId = args[2];
        PlayerRef viewer = sender.player().orElse(null);
        module.opening().tableFor(viewer, revision).thenAccept(table -> {
            ItemChance.Chance chance = ItemChance.of(table, revision, ItemChance.containsItem(itemId));
            var formatter = module.opening().formatter();
            String shown = chance.isZero() ? "not possible" : formatter.percent(chance.numerator(),
                    chance.denominator()) + " (" + formatter.oneIn(chance.numerator(), chance.denominator()) + ")";
            sender.reply("&7Chance of getting &f" + itemId + " &7from one opening of &f"
                    + MysticText.stripMarkup(revision.spec.displayName) + "&7: &6" + shown + " &8(odds "
                    + table.oddsId() + (viewer == null ? ", published odds" : ", your odds") + ")");
        });
    }

    /**
     * {@code /lootbox providers}: the reward and condition providers other plugins
     * registered (P3-WP1), and every published pool that can't be opened because
     * one it needs is missing.
     */
    private void providers(MysticCommandSender sender) {
        if (!allowed(sender, Permissions.LOOTBOX_ADMIN)) {
            return;
        }
        var integrations = module.integrations();
        sender.reply("&d&lLootBox providers");
        integrations.rewardProviders().forEach(provider -> sender.reply("&7- reward &f" + provider.id() + " &8("
                + provider.kind() + ", " + provider.guarantee() + ")"));
        integrations.conditionProviders().forEach(provider -> sender.reply("&7- condition &f" + provider.type()));
        int invalid = 0;
        for (LootBoxRevision revision : module.registry().publishedPools()) {
            java.util.List<String> missing = integrations.missing(revision.spec);
            if (!missing.isEmpty()) {
                invalid++;
                sender.reply("&c" + revision.lootboxId + " &7can't be opened: missing " + String.join(", ", missing));
            }
        }
        if (invalid == 0) {
            sender.reply("&aEvery published pool has the providers it needs.");
        }
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
                            CrateVisuals.defaults(module.config().crates), sender.name());
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
                    boolean force = args.length > 3 && args[3].equalsIgnoreCase("force");
                    CrateInstance named = module.crates().get(args[2]).orElse(null);
                    if (named == null) {
                        sender.reply("&cNo crate called &f" + args[2]);
                        return;
                    }
                    // A generated crate puts its original block back, which needs its world's thread.
                    var world = CrateInstance.KIND_GENERATED.equals(named.kind)
                            ? core.platform().world(named.worldId).or(() -> core.platform().world(named.worldName))
                                    .orElse(null)
                            : null;
                    if (world == null) {
                        sender.reply(removed(module.crates().remove(named.crateId, null, force, sender.name())));
                    } else {
                        world.execute(() -> sender.reply(removed(module.crates().remove(named.crateId, world, force,
                                sender.name()))));
                    }
                    return;
                }
                player(sender).ifPresent(player -> onWorld(sender, player, (store, entity) -> {
                    BlockTargets.Block block = BlockTargets.target(entity, store, module.config().crates.selectReach);
                    Optional<CrateInstance> crate = block == null ? Optional.empty()
                            : module.crates().at(block.world(), block.x(), block.y(), block.z());
                    sender.reply(crate.map(c -> removed(module.crates().remove(c.crateId, block.world(), false,
                            sender.name()))).orElse("&cLook at a crate, or name it: /lootbox crate remove <crateId>"));
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
            case "effects" -> {
                if (!allowed(sender, Permissions.LOOTBOX_USE)) {
                    return;
                }
                player(sender).ifPresent(player -> {
                    boolean on = module.visuals().effectsOn(player.getUuid());
                    if (args.length < 3) {
                        sender.replyKey("lootbox-crate-effects-status", Map.of("state", on ? "on" : "off"));
                        return;
                    }
                    String choice = args[2].toLowerCase(Locale.ROOT);
                    if (!choice.equals("on") && !choice.equals("off")) {
                        usage(sender, "/lootbox crate effects <on|off>");
                        return;
                    }
                    module.visuals().setEffects(player.getUuid(), choice.equals("on"));
                    sender.replyKey(choice.equals("on") ? "lootbox-crate-effects-on" : "lootbox-crate-effects-off");
                });
            }
            default -> usage(sender, "/lootbox crate <select|convert|edit|rebind|remove|enable|verify|list|info|effects> …");
        }
    }

    private static String removed(CrateService.RemoveResult result) {
        return switch (result) {
            case CrateService.Removed done -> "&aRemoved crate &f" + done.crate().crateId
                    + (done.note() == null ? "" : " &7(" + done.note() + ")");
            case CrateService.NotRemoved not -> "&cNot removed: " + not.reason() + ".";
        };
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
