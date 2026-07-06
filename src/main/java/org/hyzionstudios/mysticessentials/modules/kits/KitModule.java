package org.hyzionstudios.mysticessentials.modules.kits;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.api.model.PlayerProfile;
import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionProvider;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Kits: named item bundles with per-kit cooldowns (including single-use),
 * required-online-time gating, optional per-kit permissions
 * ({@code mysticessentials.kit.<name>}), and optional economy cost. The kit
 * named by {@code firstJoinKit} is granted automatically on first join.
 *
 * <p>Items are given on the player's world thread via the verified
 * {@code Player.giveItem} (overflow drops at the player's feet, matching the
 * builtin {@code /give}). Last-claim timestamps live in the player profile
 * under {@code moduleData.kits}.</p>
 */
public final class KitModule extends AbstractMysticModule {

    private static final String DATA_KEY = "kits";

    private KitConfig config = new KitConfig();

    public KitModule() {
        super("kits", "Kits", "1.0.0");
    }

    @Override
    public void onEnable() {
        config = core.configManager().loadModuleConfig(id(), KitConfig.class, new KitConfig());
        registerCommand(new KitCommand());
        core.platform().onEvent(
                com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent.class,
                (com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent event) ->
                        onJoin(event.getPlayerRef()));
    }

    @Override
    public void onReload() {
        config = core.configManager().loadModuleConfig(id(), KitConfig.class, new KitConfig());
    }

    @Override
    public void onDisable() {
        // Claims persist through the profile service; nothing to flush.
    }

    private void onJoin(PlayerRef player) {
        String kitName = config.firstJoinKit;
        if (kitName == null || kitName.isBlank()) {
            return;
        }
        core.getPlayerProfileService().load(player.getUuid(), player.getUsername()).thenAccept(profile -> {
            if (!profile.isFirstJoin()) {
                return;
            }
            KitConfig.Kit kit = findKit(kitName).orElse(null);
            if (kit == null) {
                log("firstJoinKit '" + kitName + "' is not defined in modules/kits/config.json");
                return;
            }
            giveItems(player, kit, kitName);
            recordClaim(player.getUuid(), normalize(kitName));
            core.getMessageService().sendKey(player, "kit-claimed", Map.of("kit", kitName));
        });
    }

    // ----- Claim pipeline -----------------------------------------------------

    Optional<KitConfig.Kit> findKit(String name) {
        if (name == null || config.kits == null) {
            return Optional.empty();
        }
        String id = normalize(name);
        for (Map.Entry<String, KitConfig.Kit> entry : config.kits.entrySet()) {
            if (normalize(entry.getKey()).equals(id)) {
                return Optional.ofNullable(entry.getValue());
            }
        }
        return Optional.empty();
    }

    static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).trim();
    }

    private void claim(MysticCommandSender sender, String kitName) {
        claimPlayer(sender.player().orElseThrow(), sender.uuid(), kitName, sender::replyKey);
    }

    void claimFromUi(PlayerRef player, String kitName) {
        claimPlayer(player, player.getUuid(), kitName,
                (key, params) -> core.getMessageService().sendKey(player, key, params));
    }

    private void claimPlayer(PlayerRef player, UUID playerId, String kitName, MessageResponder reply) {
        KitConfig.Kit kit = findKit(kitName).orElse(null);
        if (kit == null) {
            reply.reply("kit-unknown", Map.of("kit", kitName));
            return;
        }
        String id = normalize(kitName);
        if (kit.requirePermission && !player.hasPermission(Permissions.kit(id))) {
            reply.reply("kit-no-access", Map.of("kit", id));
            return;
        }
        long onlineSeconds = totalOnlineSeconds(playerId);
        if (kit.requiredOnlineSeconds > 0 && onlineSeconds < kit.requiredOnlineSeconds) {
            reply.reply("kit-need-playtime",
                    Map.of("duration", formatDuration(kit.requiredOnlineSeconds - onlineSeconds)));
            return;
        }
        if (!player.hasPermission(Permissions.KIT_BYPASS_COOLDOWN)) {
            Long lastClaim = lastClaim(playerId, id);
            if (lastClaim != null) {
                if (kit.cooldownSeconds < 0) {
                    reply.reply("kit-once", Map.of("kit", id));
                    return;
                }
                long readyAt = lastClaim + kit.cooldownSeconds * 1000L;
                long now = System.currentTimeMillis();
                if (kit.cooldownSeconds > 0 && now < readyAt) {
                    reply.reply("kit-cooldown", Map.of(
                            "kit", id,
                            "duration", formatDuration((readyAt - now) / 1000L)));
                    return;
                }
            }
        }
        if (kit.cost > 0) {
            if (!core.getEconomyService().has(playerId, kit.cost)) {
                reply.reply("kit-cannot-afford", Map.of("cost", Double.toString(kit.cost)));
                return;
            }
            core.getEconomyService().withdraw(playerId, kit.cost);
        }
        giveItems(player, kit, id);
        recordClaim(playerId, id);
        reply.reply("kit-claimed", Map.of("kit", id));
    }

    private interface MessageResponder {
        void reply(String key, Map<String, String> params);
    }

    /** Gives the kit's items on the player's world thread (overflow drops like /give). */
    private void giveItems(PlayerRef player, KitConfig.Kit kit, String kitName) {
        boolean dispatched = core.platform().runOnEntityThread(player, (store, entity, world) -> {
            for (KitConfig.KitItem item : kit.items) {
                if (item == null || item.itemId == null || item.itemId.isBlank()) {
                    continue;
                }
                try {
                    ItemStack stack = new ItemStack(item.itemId, Math.max(1, item.quantity));
                    Player.giveItem(stack, entity, store);
                } catch (Throwable t) {
                    core.log(Level.WARNING, "[kits] Kit '" + kitName + "': cannot give item '"
                            + item.itemId + "': " + t);
                }
            }
        });
        if (!dispatched) {
            log("Could not give kit '" + kitName + "' to " + player.getUsername() + " (invalid entity).");
        }
    }

    // ----- Claim bookkeeping (profile moduleData) --------------------------------

    Long lastClaim(UUID player, String kitId) {
        JsonObject data = kitData(player);
        return data != null && data.has(kitId) ? data.get(kitId).getAsLong() : null;
    }

    private void recordClaim(UUID player, String kitId) {
        JsonObject data = kitData(player);
        if (data != null) {
            data.addProperty(kitId, System.currentTimeMillis());
            core.getPlayerProfileService().getCached(player).ifPresent(core.getPlayerProfileService()::save);
        }
    }

    private JsonObject kitData(UUID player) {
        return core.getPlayerProfileService().getCached(player)
                .map(profile -> profile.getModuleData().computeIfAbsent(DATA_KEY, k -> new JsonObject()))
                .orElse(null);
    }

    /** Total playtime = persisted playtime + the current session so far. */
    long totalOnlineSeconds(UUID player) {
        PlayerProfile profile = core.getPlayerProfileService().getCached(player).orElse(null);
        if (profile == null) {
            return 0;
        }
        long session = 0;
        try {
            session = Math.max(0, Instant.now().getEpochSecond()
                    - Instant.parse(profile.getLastJoinDate()).getEpochSecond());
        } catch (RuntimeException ignored) {
            // Missing/invalid join date: count persisted playtime only.
        }
        return profile.getTotalPlaytimeSeconds() + session;
    }

    static String formatDuration(long seconds) {
        long s = Math.max(0, seconds);
        long days = s / 86400;
        long hours = (s % 86400) / 3600;
        long minutes = (s % 3600) / 60;
        long secs = s % 60;
        StringBuilder result = new StringBuilder();
        if (days > 0) {
            result.append(days).append("d ");
        }
        if (hours > 0) {
            result.append(hours).append("h ");
        }
        if (minutes > 0) {
            result.append(minutes).append("m ");
        }
        if (result.isEmpty() || secs > 0) {
            result.append(secs).append("s");
        }
        return result.toString().trim();
    }

    /** Suggests kit names the sender may claim. */
    private SuggestionProvider kitSuggestions() {
        return (commandSender, input, index, result) -> {
            if (config.kits == null) {
                return;
            }
            for (Map.Entry<String, KitConfig.Kit> entry : config.kits.entrySet()) {
                KitConfig.Kit kit = entry.getValue();
                if (kit == null || (kit.requirePermission
                        && !commandSender.hasPermission(Permissions.kit(entry.getKey())))) {
                    continue;
                }
                result.suggest(normalize(entry.getKey()));
            }
        };
    }

    private void listKits(MysticCommandSender sender) {
        if (config.kits == null || config.kits.isEmpty()) {
            sender.replyKey("kit-none");
            return;
        }
        sender.replyKey("kit-list-header");
        for (Map.Entry<String, KitConfig.Kit> entry : config.kits.entrySet()) {
            KitConfig.Kit kit = entry.getValue();
            if (kit == null) {
                continue;
            }
            String id = normalize(entry.getKey());
            if (kit.requirePermission && !sender.hasPermission(Permissions.kit(id))) {
                continue;
            }
            StringBuilder meta = new StringBuilder();
            if (kit.cooldownSeconds < 0) {
                meta.append(" &8(one-time)");
            } else if (kit.cooldownSeconds > 0) {
                meta.append(" &8(every ").append(formatDuration(kit.cooldownSeconds)).append(")");
            }
            if (kit.cost > 0) {
                meta.append(" &8(cost ").append(kit.cost).append(")");
            }
            sender.replyKey("kit-list-entry", Map.of(
                    "kit", id,
                    "meta", meta.toString(),
                    "description", kit.description == null ? "" : " &7- " + kit.description));
        }
    }

    Map<String, KitConfig.Kit> visibleKits(PlayerRef player) {
        Map<String, KitConfig.Kit> visible = new LinkedHashMap<>();
        if (config.kits == null) {
            return visible;
        }
        for (Map.Entry<String, KitConfig.Kit> entry : config.kits.entrySet()) {
            KitConfig.Kit kit = entry.getValue();
            String id = normalize(entry.getKey());
            if (kit != null && canAccess(player, id, kit)) {
                visible.put(id, kit);
            }
        }
        return visible;
    }

    boolean canAccess(PlayerRef player, String id, KitConfig.Kit kit) {
        return kit == null || !kit.requirePermission || player.hasPermission(Permissions.kit(id));
    }

    String statusText(PlayerRef player, String id, KitConfig.Kit kit) {
        if (kit == null) {
            return "Unavailable";
        }
        if (!canAccess(player, id, kit)) {
            return "Permission required";
        }
        long onlineSeconds = totalOnlineSeconds(player.getUuid());
        if (kit.requiredOnlineSeconds > 0 && onlineSeconds < kit.requiredOnlineSeconds) {
            return "Needs " + formatDuration(kit.requiredOnlineSeconds - onlineSeconds) + " playtime";
        }
        Long last = lastClaim(player.getUuid(), id);
        if (last != null && !player.hasPermission(Permissions.KIT_BYPASS_COOLDOWN)) {
            if (kit.cooldownSeconds < 0) {
                return "Already claimed";
            }
            long readyAt = last + kit.cooldownSeconds * 1000L;
            long remaining = (readyAt - System.currentTimeMillis()) / 1000L;
            if (kit.cooldownSeconds > 0 && remaining > 0) {
                return "Ready in " + formatDuration(remaining);
            }
        }
        return kit.cost > 0 ? "Ready, costs " + kit.cost : "Ready";
    }

    void openKitUi(PlayerRef player, String selectedKit) {
        core.platform().openPage(player, new KitPages.KitListPage(core, this, player, selectedKit));
    }

    void openKitPreviewUi(PlayerRef player, String kitName) {
        core.platform().openPage(player, new KitPages.KitPreviewPage(core, this, player, kitName));
    }

    // ----- Commands ----------------------------------------------------------

    /** {@code /kit} lists kits; {@code /kit <name>} claims; {@code /kit give <player> <kit>} (admin). */
    private final class KitCommand extends MysticCommand {
        KitCommand() {
            super(KitModule.this.core, "kit", "Claim item kits.");
            addAliases("kits");
            requirePermission(Permissions.KIT_USE);
            addUsageVariant(new KitClaimVariant());
            addSubCommand(new KitListCommand());
            addSubCommand(new KitGiveCommand());
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (sender.isPlayer()) {
                openKitUi(sender.player().orElseThrow(), null);
            } else {
                listKits(sender);
            }
        }
    }

    private final class KitClaimVariant extends MysticCommand {
        private final RequiredArg<String> kit = withRequiredArg("kit", "Kit name", ArgTypes.STRING)
                .suggest(kitSuggestions());

        KitClaimVariant() {
            super(KitModule.this.core, "Claim a kit.");
            requirePermission(Permissions.KIT_USE);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            claim(sender, sender.get(kit));
        }
    }

    private final class KitListCommand extends MysticCommand {
        KitListCommand() {
            super(KitModule.this.core, "list", "List available kits.");
        }

        @Override
        protected void run(MysticCommandSender sender) {
            listKits(sender);
        }
    }

    /** Admin: gives a kit to another player, ignoring cooldowns/cost/gating. */
    private final class KitGiveCommand extends MysticCommand {
        private final RequiredArg<String> target = withRequiredArg("player", "Target player",
                ArgTypes.STRING).suggest((commandSender, input, index, result) ->
                        core.platform().onlinePlayers().forEach(p -> result.suggest(p.getUsername())));
        private final RequiredArg<String> kit = withRequiredArg("kit", "Kit name", ArgTypes.STRING)
                .suggest(kitSuggestions());

        KitGiveCommand() {
            super(KitModule.this.core, "give", "Give a kit to a player.");
            requirePermission(Permissions.KIT_ADMIN);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            String targetName = sender.get(target);
            PlayerRef targetRef = core.platform().findPlayerByName(targetName).orElse(null);
            if (targetRef == null) {
                sender.replyKey("player-not-found");
                return;
            }
            String kitName = sender.get(kit);
            KitConfig.Kit found = findKit(kitName).orElse(null);
            if (found == null) {
                sender.replyKey("kit-unknown", Map.of("kit", kitName));
                return;
            }
            giveItems(targetRef, found, normalize(kitName));
            core.getMessageService().sendKey(targetRef, "kit-claimed",
                    Map.of("kit", normalize(kitName)));
            sender.replyKey("kit-given", Map.of(
                    "kit", normalize(kitName),
                    "player", targetRef.getUsername()));
        }
    }
}
