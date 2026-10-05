package org.hyzionstudios.mysticessentials.modules.inventory;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

import org.bson.BsonDocument;
import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.platform.ItemStackMetadata;
import org.hyzionstudios.mysticessentials.platform.command.MysticArgTypes;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.SingleArgumentType;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Inventory management: clear (self / others / all-with-protection), inventory
 * snapshots (join, leave, death, timed, and automatic pre-restore/pre-clear
 * backups), and snapshot restore through a Custom UI
 * ({@code /inventory restore <player>}).
 *
 * <p>All ECS inventory access runs on the owning player's world thread. Death
 * has no plugin event, so {@link DeathSnapshotSystem} snapshots when the
 * {@code DeathComponent} is added, before the engine drops the lost items.
 * Snapshots are stored through the {@code StorageService} under the
 * {@code inventory_snapshots} namespace keyed by player UUID.</p>
 */
public final class InventoryModule extends AbstractMysticModule {

    private static final String NAMESPACE = "inventory_snapshots";
    private static final Type SNAPSHOT_LIST_TYPE = new TypeToken<ArrayList<InventorySnapshot>>() {
    }.getType();

    private InventoryConfig config = new InventoryConfig();
    private ScheduledFuture<?> timedTask;
    /**
     * The death listener stays registered for the plugin's lifetime: the entity
     * store registry rejects a second registration of the same system class, so
     * hot-disabling this module flips {@link #active} instead of unregistering.
     */
    private DeathSnapshotSystem deathSystem;
    private volatile boolean active;

    /** Online player names plus the {@code all} literal, for the clear commands. */
    private final SingleArgumentType<String> playerOrAllArg = MysticArgTypes.dynamic(commandSender -> {
        List<String> values = new ArrayList<>();
        values.add("all");
        values.addAll(MysticArgTypes.visiblePlayerNames(commandSender));
        return values;
    });

    public InventoryModule() {
        super("inventory", "Inventory", "1.0.0");
    }

    @Override
    public void onEnable() {
        config = core.configManager().loadModuleConfig(id(), InventoryConfig.class, new InventoryConfig());
        registerCommand(new ClearInventoryCommand());
        registerCommand(new InventoryCommand());
        registerEvent(
                PlayerConnectEvent.class,
                (PlayerConnectEvent event) -> {
                    if (config.snapshotOnJoin) {
                        snapshot(event.getPlayerRef(), "Join");
                    }
                });
        registerEvent(
                PlayerDisconnectEvent.class,
                (PlayerDisconnectEvent event) -> {
                    if (config.snapshotOnLeave) {
                        snapshot(event.getPlayerRef(), "Leave");
                    }
                });
        if (deathSystem == null) {
            DeathSnapshotSystem candidate = new DeathSnapshotSystem(this);
            if (core.platform().registerEntitySystem(candidate)) {
                deathSystem = candidate;
            } else {
                log("Could not install the death listener — no Death snapshots will be taken.");
            }
        }
        active = true;
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
        active = false;
        if (timedTask != null) {
            timedTask.cancel(false);
            timedTask = null;
        }
    }

    // ----- Snapshot capture -----------------------------------------------------

    /** Called by {@link DeathSnapshotSystem} on the player's world thread, before the death drop. */
    void onDeath(PlayerRef player) {
        if (active && config.snapshotOnDeath) {
            captureOnThread(player, "Death");
        }
    }

    private void timedSnapshots() {
        for (PlayerRef player : core.platform().onlinePlayers()) {
            snapshot(player, "Timed");
        }
    }

    /** Captures a snapshot on the player's world thread; completes once it is stored. */
    public CompletableFuture<Boolean> snapshot(PlayerRef player, String cause) {
        CompletableFuture<Boolean> outcome = new CompletableFuture<>();
        boolean dispatched = core.platform().runOnEntityThread(player, (store, entity, world) ->
                captureOnThread(player, cause).thenAccept(outcome::complete));
        if (!dispatched) {
            outcome.complete(false);
        }
        return outcome;
    }

    /**
     * Captures the inventory (MUST run on the player's world thread) and stores it.
     *
     * @return a future completing {@code true} once the snapshot is stored, or
     *         {@code false} when there was nothing to capture or the save failed
     */
    private CompletableFuture<Boolean> captureOnThread(PlayerRef player, String cause) {
        try {
            Map<String, ItemContainer> inventorySections = sections(player);
            if (inventorySections.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            InventorySnapshot snapshot = InventorySnapshot.create(cause);
            for (Map.Entry<String, ItemContainer> section : inventorySections.entrySet()) {
                List<InventorySnapshot.SlotItem> slots = captureContainer(section.getValue());
                if (!slots.isEmpty()) {
                    snapshot.sections.put(section.getKey(), slots);
                }
            }
            return persist(player.getUuid(), snapshot).handle((stored, failure) -> {
                if (failure != null) {
                    core.log(Level.WARNING, "[inventory] Saving the " + cause + " snapshot of "
                            + player.getUsername() + " failed: " + failure);
                    return false;
                }
                return true;
            });
        } catch (Throwable t) {
            core.log(Level.WARNING, "[inventory] Snapshot (" + cause + ") failed for "
                    + player.getUsername() + ": " + t);
            return CompletableFuture.completedFuture(false);
        }
    }

    private static Map<String, ItemContainer> sections(PlayerRef player) {
        Map<String, ItemContainer> sections = new LinkedHashMap<>();
        var ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            return sections;
        }
        var store = ref.getStore();
        putSection(sections, "hotbar",
                store.getComponent(ref, InventoryComponent.Hotbar.getComponentType()));
        putSection(sections, "storage",
                store.getComponent(ref, InventoryComponent.Storage.getComponentType()));
        putSection(sections, "armor",
                store.getComponent(ref, InventoryComponent.Armor.getComponentType()));
        putSection(sections, "utility",
                store.getComponent(ref, InventoryComponent.Utility.getComponentType()));
        putSection(sections, "tools",
                store.getComponent(ref, InventoryComponent.Tool.getComponentType()));
        putSection(sections, "backpack",
                store.getComponent(ref, InventoryComponent.Backpack.getComponentType()));
        return sections;
    }

    private static void putSection(Map<String, ItemContainer> sections, String name,
            InventoryComponent component) {
        if (component != null && component.getInventory() != null) {
            sections.put(name, component.getInventory());
        }
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
            item.metadata = ItemStackMetadata.toJson(stack);
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

    /** Prepends {@code snapshot} to the player's list in one atomic storage update. */
    private CompletableFuture<Void> persist(UUID player, InventorySnapshot snapshot) {
        return core.getStorageService().update(NAMESPACE, player.toString(), element -> {
            List<InventorySnapshot> list = element == null ? null
                    : Json.gson().fromJson(element, SNAPSHOT_LIST_TYPE);
            if (list == null) {
                list = new ArrayList<>();
            }
            list.add(0, snapshot);
            int max = Math.max(1, config.maxSnapshotsPerPlayer);
            while (list.size() > max) {
                list.remove(list.size() - 1);
            }
            return Json.toTree(list);
        }).thenApply(stored -> null);
    }

    // ----- Clear & restore -----------------------------------------------------

    /**
     * Stores a {@code cause} backup snapshot, then runs {@code change} on the
     * player's world thread, never before the backup is stored, so a failed save
     * cannot cost the player their items.
     *
     * @return {@code change}'s result, or {@code false} when the backup failed
     */
    private CompletableFuture<Boolean> afterBackup(PlayerRef player, String cause, BooleanSupplier change) {
        CompletableFuture<Boolean> outcome = new CompletableFuture<>();
        boolean dispatched = core.platform().runOnEntityThread(player, (store, entity, world) ->
                captureOnThread(player, cause).thenAccept(backedUp -> {
                    if (!backedUp) {
                        outcome.complete(false);
                        return;
                    }
                    boolean changing = core.platform().runOnEntityThread(player,
                            (changeStore, changeEntity, changeWorld) -> outcome.complete(change.getAsBoolean()));
                    if (!changing) {
                        outcome.complete(false);
                    }
                }));
        if (!dispatched) {
            outcome.complete(false);
        }
        return outcome;
    }

    /** Clears a player's inventory (after a PreClear backup snapshot is stored). */
    public CompletableFuture<Boolean> clearInventory(PlayerRef player) {
        return afterBackup(player, "PreClear", () -> {
            Map<String, ItemContainer> inventorySections = sections(player);
            if (inventorySections.isEmpty()) {
                return false;
            }
            for (ItemContainer container : inventorySections.values()) {
                container.clear();
            }
            return true;
        });
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

    /** Restores a snapshot onto an online player (after a PreRestore backup is stored). */
    public CompletableFuture<Boolean> restore(PlayerRef target, InventorySnapshot snapshot) {
        return afterBackup(target, "PreRestore", () -> {
            try {
                Map<String, ItemContainer> sections = sections(target);
                if (sections.isEmpty()) {
                    return false;
                }
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
                return true;
            } catch (Throwable t) {
                core.log(Level.WARNING, "[inventory] Restore failed for "
                        + target.getUsername() + ": " + t);
                return false;
            }
        });
    }

    private static ItemStack toItemStack(InventorySnapshot.SlotItem item) {
        // No stored metadata means none: an empty document would not stack with fresh items.
        BsonDocument metadata = item.metadata == null || item.metadata.isBlank()
                ? null
                : BsonDocument.parse(item.metadata);
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
                playerOrAllArg);

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
                playerOrAllArg);

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
                MysticArgTypes.PLAYER_NAME);

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
