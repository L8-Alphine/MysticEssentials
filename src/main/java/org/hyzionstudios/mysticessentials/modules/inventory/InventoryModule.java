package org.hyzionstudios.mysticessentials.modules.inventory;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionProvider;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Inventory management: clear (self / others / all-with-protection), inventory
 * snapshots (join, leave, death, timed, and automatic pre-restore/pre-clear
 * backups), and snapshot restore through a Custom UI
 * ({@code /inventory restore <player>}).
 *
 * <p>All ECS inventory access runs on the owning player's world thread. Death
 * has no plugin event in 0.5.6, so a poll watches online players for the
 * {@code DeathComponent} and snapshots on the first sighting per death.
 * Snapshots are stored through the {@code StorageService} under the
 * {@code inventory_snapshots} namespace keyed by player UUID.</p>
 */
public final class InventoryModule extends AbstractMysticModule {

    private static final String NAMESPACE = "inventory_snapshots";
    private static final Type SNAPSHOT_LIST_TYPE = new TypeToken<ArrayList<InventorySnapshot>>() {
    }.getType();
    /** Death-poll cadence; also bounds how late a death snapshot can be. */
    private static final long DEATH_POLL_MS = 2000L;

    private InventoryConfig config = new InventoryConfig();
    private ScheduledFuture<?> deathPollTask;
    private ScheduledFuture<?> timedTask;
    /** Players whose current death has already been snapshotted. */
    private final Set<UUID> deathHandled = ConcurrentHashMap.newKeySet();

    public InventoryModule() {
        super("inventory", "Inventory", "1.0.0");
    }

    @Override
    public void onEnable() {
        config = core.configManager().loadModuleConfig(id(), InventoryConfig.class, new InventoryConfig());
        registerCommand(new ClearInventoryCommand());
        registerCommand(new InventoryCommand());
        registerEvent(
                com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent.class,
                (com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent event) -> {
                    if (config.snapshotOnJoin) {
                        snapshot(event.getPlayerRef(), "Join");
                    }
                });
        registerEvent(
                com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent.class,
                (com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent event) -> {
                    deathHandled.remove(event.getPlayerRef().getUuid());
                    if (config.snapshotOnLeave) {
                        snapshot(event.getPlayerRef(), "Leave");
                    }
                });
        if (config.snapshotOnDeath) {
            deathPollTask = core.scheduler().runRepeating(this::pollDeaths,
                    DEATH_POLL_MS, DEATH_POLL_MS, TimeUnit.MILLISECONDS);
        }
        if (config.timedSnapshotMinutes > 0) {
            long minutes = config.timedSnapshotMinutes;
            timedTask = core.scheduler().runRepeating(this::timedSnapshots,
                    minutes, minutes, TimeUnit.MINUTES);
        }
    }

    @Override
    public void onReload() {
        config = core.configManager().loadModuleConfig(id(), InventoryConfig.class, new InventoryConfig());
    }

    @Override
    public void onDisable() {
        if (deathPollTask != null) {
            deathPollTask.cancel(false);
            deathPollTask = null;
        }
        if (timedTask != null) {
            timedTask.cancel(false);
            timedTask = null;
        }
        deathHandled.clear();
    }

    // ----- Snapshot capture -----------------------------------------------------

    /** Watches for the DeathComponent (no death event exists in 0.5.6). */
    private void pollDeaths() {
        for (PlayerRef player : core.platform().onlinePlayers()) {
            UUID uuid = player.getUuid();
            core.platform().runOnEntityThread(player, (store, entity, world) -> {
                boolean dead = store.getComponent(entity, DeathComponent.getComponentType()) != null;
                if (dead && deathHandled.add(uuid)) {
                    captureOnThread(player, "Death");
                } else if (!dead) {
                    deathHandled.remove(uuid);
                }
            });
        }
    }

    private void timedSnapshots() {
        for (PlayerRef player : core.platform().onlinePlayers()) {
            snapshot(player, "Timed");
        }
    }

    /** Captures a snapshot on the player's world thread and persists it. */
    public CompletableFuture<Boolean> snapshot(PlayerRef player, String cause) {
        CompletableFuture<Boolean> outcome = new CompletableFuture<>();
        boolean dispatched = core.platform().runOnEntityThread(player, (store, entity, world) ->
                outcome.complete(captureOnThread(player, cause)));
        if (!dispatched) {
            outcome.complete(false);
        }
        return outcome;
    }

    /** MUST run on the player's world thread. */
    private boolean captureOnThread(PlayerRef player, String cause) {
        try {
            Inventory inventory = resolveInventory(player);
            if (inventory == null) {
                return false;
            }
            InventorySnapshot snapshot = InventorySnapshot.create(cause);
            for (Map.Entry<String, ItemContainer> section : sections(inventory).entrySet()) {
                List<InventorySnapshot.SlotItem> slots = captureContainer(section.getValue());
                if (!slots.isEmpty()) {
                    snapshot.sections.put(section.getKey(), slots);
                }
            }
            persist(player.getUuid(), snapshot);
            return true;
        } catch (Throwable t) {
            core.log(Level.WARNING, "[inventory] Snapshot (" + cause + ") failed for "
                    + player.getUsername() + ": " + t);
            return false;
        }
    }

    private Inventory resolveInventory(PlayerRef player) {
        var ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            return null;
        }
        Player entity = ref.getStore().getComponent(ref, Player.getComponentType());
        return entity == null ? null : entity.getInventory();
    }

    private static Map<String, ItemContainer> sections(Inventory inventory) {
        Map<String, ItemContainer> sections = new LinkedHashMap<>();
        sections.put("hotbar", inventory.getHotbar());
        sections.put("storage", inventory.getStorage());
        sections.put("armor", inventory.getArmor());
        sections.put("utility", inventory.getUtility());
        sections.put("tools", inventory.getTools());
        sections.put("backpack", inventory.getBackpack());
        sections.values().removeIf(java.util.Objects::isNull);
        return sections;
    }

    private static List<InventorySnapshot.SlotItem> captureContainer(ItemContainer container) {
        List<InventorySnapshot.SlotItem> slots = new ArrayList<>();
        for (short slot = 0; slot < container.getCapacity(); slot++) {
            ItemStack stack = container.getItemStack(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            InventorySnapshot.SlotItem item = new InventorySnapshot.SlotItem();
            item.slot = slot;
            item.itemId = stack.getItemId();
            item.quantity = stack.getQuantity();
            item.durability = stack.getDurability();
            item.maxDurability = stack.getMaxDurability();
            var metadata = stack.getMetadata();
            item.metadata = metadata == null || metadata.isEmpty() ? null : metadata.toJson();
            slots.add(item);
        }
        return slots;
    }

    // ----- Snapshot storage -------------------------------------------------------

    public CompletableFuture<List<InventorySnapshot>> snapshots(UUID player) {
        return core.getStorageService().load(NAMESPACE, player.toString()).thenApply(element -> {
            if (element == null) {
                return new ArrayList<InventorySnapshot>();
            }
            List<InventorySnapshot> list = Json.gson().fromJson(element, SNAPSHOT_LIST_TYPE);
            return list != null ? list : new ArrayList<InventorySnapshot>();
        }).exceptionally(t -> {
            // A truncated/corrupt file makes the load future itself fail (the
            // parse happens upstream, in the storage provider), so the guard has
            // to sit here rather than only around fromJson. Corrupt or
            // incompatible stored data must not stall the restore UI.
            core.log(Level.WARNING, "[inventory] Ignoring corrupt snapshot data for "
                    + player + ": " + t);
            return new ArrayList<InventorySnapshot>();
        });
    }

    private void persist(UUID player, InventorySnapshot snapshot) {
        snapshots(player).thenCompose(list -> {
            list.add(0, snapshot);
            int max = Math.max(1, config.maxSnapshotsPerPlayer);
            while (list.size() > max) {
                list.remove(list.size() - 1);
            }
            return core.getStorageService().save(NAMESPACE, player.toString(), Json.toTree(list));
        });
    }

    // ----- Clear & restore -----------------------------------------------------

    /** Clears a player's inventory (with a PreClear backup snapshot). */
    public CompletableFuture<Boolean> clearInventory(PlayerRef player) {
        CompletableFuture<Boolean> outcome = new CompletableFuture<>();
        boolean dispatched = core.platform().runOnEntityThread(player, (store, entity, world) -> {
            captureOnThread(player, "PreClear");
            Inventory inventory = resolveInventory(player);
            if (inventory == null) {
                outcome.complete(false);
                return;
            }
            for (ItemContainer container : sections(inventory).values()) {
                container.clear();
            }
            outcome.complete(true);
        });
        if (!dispatched) {
            outcome.complete(false);
        }
        return outcome;
    }

    /**
     * Clears every online player's inventory except those holding
     * {@code mysticessentials.inventory.protect}. @return cleared count.
     */
    public int clearAll() {
        int cleared = 0;
        for (PlayerRef player : core.platform().onlinePlayers()) {
            if (player.hasPermission(Permissions.INVENTORY_PROTECT)) {
                continue;
            }
            clearInventory(player);
            core.getMessageService().sendKey(player, "inventory-cleared-by-admin");
            cleared++;
        }
        return cleared;
    }

    /** Restores a snapshot onto an online player (with a PreRestore backup first). */
    public CompletableFuture<Boolean> restore(PlayerRef target, InventorySnapshot snapshot) {
        CompletableFuture<Boolean> outcome = new CompletableFuture<>();
        boolean dispatched = core.platform().runOnEntityThread(target, (store, entity, world) -> {
            try {
                captureOnThread(target, "PreRestore");
                Inventory inventory = resolveInventory(target);
                if (inventory == null) {
                    outcome.complete(false);
                    return;
                }
                Map<String, ItemContainer> sections = sections(inventory);
                for (ItemContainer container : sections.values()) {
                    container.clear();
                }
                for (Map.Entry<String, List<InventorySnapshot.SlotItem>> entry : snapshot.sections.entrySet()) {
                    ItemContainer container = sections.get(entry.getKey());
                    if (container == null) {
                        continue;
                    }
                    for (InventorySnapshot.SlotItem item : entry.getValue()) {
                        if (item.slot < 0 || item.slot >= container.getCapacity()) {
                            continue;
                        }
                        try {
                            container.setItemStackForSlot((short) item.slot, toItemStack(item));
                        } catch (Throwable t) {
                            core.log(Level.WARNING, "[inventory] Skipped restoring item '"
                                    + item.itemId + "': " + t);
                        }
                    }
                }
                outcome.complete(true);
            } catch (Throwable t) {
                core.log(Level.WARNING, "[inventory] Restore failed for "
                        + target.getUsername() + ": " + t);
                outcome.complete(false);
            }
        });
        if (!dispatched) {
            outcome.complete(false);
        }
        return outcome;
    }

    private static ItemStack toItemStack(InventorySnapshot.SlotItem item) {
        org.bson.BsonDocument metadata = item.metadata == null || item.metadata.isBlank()
                ? new org.bson.BsonDocument()
                : org.bson.BsonDocument.parse(item.metadata);
        return new ItemStack(item.itemId, Math.max(1, item.quantity),
                item.durability, item.maxDurability, metadata);
    }

    // ----- UI ------------------------------------------------------------------

    void openRestoreUi(PlayerRef viewer, UUID targetUuid, String targetName) {
        snapshots(targetUuid).thenAccept(snapshots -> {
            boolean opened = core.platform().openPage(viewer,
                    new InventoryPages.RestorePage(core, this, viewer, targetUuid, targetName, snapshots));
            if (!opened) {
                core.getMessageService().send(viewer,
                        "&cCould not open the restore UI for " + targetName + " — see the server log.");
            }
        }).exceptionally(t -> {
            // Without this, a failed snapshot load would leave the UI silently
            // never opening ("doesn't open or crash").
            core.log(Level.WARNING, "[inventory] Failed to open restore UI for " + targetName + ": " + t);
            core.getMessageService().send(viewer,
                    "&cCould not load " + targetName + "'s snapshots — see the server log.");
            return null;
        });
    }

    private SuggestionProvider onlinePlayerSuggestions() {
        return (commandSender, input, index, result) ->
                core.platform().onlinePlayers().forEach(p -> result.suggest(p.getUsername()));
    }

    // ----- Commands ----------------------------------------------------------

    /**
     * {@code /clearinventory} (alias {@code /clearinv}) clears your own
     * inventory; {@code /clearinventory <player|all>} clears another player's
     * (or everyone's, minus protected players).
     */
    private final class ClearInventoryCommand extends MysticCommand {
        ClearInventoryCommand() {
            super(InventoryModule.this.core, "clearinventory", "Clear your inventory.");
            addAliases("clearinv");
            requirePermission(Permissions.INVENTORY_CLEAR);
            addUsageVariant(new ClearTargetVariant());
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            clearInventory(sender.player().orElseThrow()).thenAccept(ok ->
                    sender.replyKey(ok ? "inventory-cleared" : "inventory-clear-failed"));
        }
    }

    private final class ClearTargetVariant extends MysticCommand {
        private final RequiredArg<String> target = withRequiredArg("player", "Player name or 'all'",
                ArgTypes.STRING).suggest((commandSender, input, index, result) -> {
                    result.suggest("all");
                    core.platform().onlinePlayers().forEach(p -> result.suggest(p.getUsername()));
                });

        ClearTargetVariant() {
            super(InventoryModule.this.core, "Clear a player's inventory (or all).");
            requirePermission(Permissions.INVENTORY_CLEAR_OTHERS);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            clearTarget(sender, sender.get(target));
        }
    }

    private void clearTarget(MysticCommandSender sender, String targetName) {
        if ("all".equalsIgnoreCase(targetName)) {
            if (!sender.hasPermission(Permissions.INVENTORY_CLEAR_ALL)) {
                sender.replyKey("no-permission");
                return;
            }
            int cleared = clearAll();
            sender.replyKey("inventory-clear-all", Map.of("count", Integer.toString(cleared)));
            return;
        }
        PlayerRef target = core.platform().findPlayerByName(targetName).orElse(null);
        if (target == null) {
            sender.replyKey("player-not-found");
            return;
        }
        clearInventory(target).thenAccept(ok -> {
            if (ok) {
                sender.replyKey("inventory-clear-other", Map.of("player", target.getUsername()));
                core.getMessageService().sendKey(target, "inventory-cleared-by-admin");
            } else {
                sender.replyKey("inventory-clear-other-failed");
            }
        });
    }

    /** {@code /inventory clear [player|all]} and {@code /inventory restore <player>} (opens the UI). */
    private final class InventoryCommand extends MysticCommand {
        InventoryCommand() {
            super(InventoryModule.this.core, "inventory", "Inventory management.");
            addAliases("inv");
            requirePermission(Permissions.INVENTORY_CLEAR);
            addSubCommand(new InventoryClearSubCommand());
            addSubCommand(new InventoryRestoreSubCommand());
        }

        @Override
        protected void run(MysticCommandSender sender) {
            sender.replyKey("inventory-usage");
        }
    }

    private final class InventoryClearSubCommand extends MysticCommand {
        InventoryClearSubCommand() {
            super(InventoryModule.this.core, "clear", "Clear your inventory.");
            addUsageVariant(new InventoryClearTargetVariant());
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            clearInventory(sender.player().orElseThrow()).thenAccept(ok ->
                    sender.replyKey(ok ? "inventory-cleared" : "inventory-clear-failed"));
        }
    }

    private final class InventoryClearTargetVariant extends MysticCommand {
        private final RequiredArg<String> target = withRequiredArg("player", "Player name or 'all'",
                ArgTypes.STRING).suggest((commandSender, input, index, result) -> {
                    result.suggest("all");
                    core.platform().onlinePlayers().forEach(p -> result.suggest(p.getUsername()));
                });

        InventoryClearTargetVariant() {
            super(InventoryModule.this.core, "Clear a player's inventory (or all).");
            requirePermission(Permissions.INVENTORY_CLEAR_OTHERS);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            clearTarget(sender, sender.get(target));
        }
    }

    private final class InventoryRestoreSubCommand extends MysticCommand {
        private final RequiredArg<String> target = withRequiredArg("player", "Target player",
                ArgTypes.STRING).suggest(onlinePlayerSuggestions());

        InventoryRestoreSubCommand() {
            super(InventoryModule.this.core, "restore", "Browse and restore a player's inventory snapshots.");
            requirePermission(Permissions.INVENTORY_RESTORE);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            String name = sender.get(target);
            PlayerRef viewer = sender.player().orElseThrow();
            PlayerRef online = core.platform().findPlayerByName(name).orElse(null);
            if (online != null) {
                openRestoreUi(viewer, online.getUuid(), online.getUsername());
                return;
            }
            // Offline players: browse snapshots by resolved UUID (restore needs them online).
            core.getPlayerProfileService().resolveUuid(name).thenAccept(resolved -> {
                if (resolved.isPresent()) {
                    openRestoreUi(viewer, resolved.get(), name.toLowerCase(Locale.ROOT));
                } else {
                    sender.replyKey("player-not-found");
                }
            });
        }
    }
}
