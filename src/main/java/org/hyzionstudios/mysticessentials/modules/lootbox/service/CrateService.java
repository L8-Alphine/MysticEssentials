package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.event.CrateConvertedEvent;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.event.CrateRemovedEvent;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.event.OpeningFlaggedEvent;
import org.hyzionstudios.mysticessentials.modules.lootbox.hytale.BlockTargets;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.CrateInstance;
import org.hyzionstudios.mysticessentials.modules.lootbox.storage.LootBoxStorage;
import org.hyzionstudios.mysticessentials.modules.lootbox.validate.DraftValidator;

import com.hypixel.hytale.server.core.universe.world.World;

/**
 * Crate instances and their position index (§4.6). The index maps world →
 * packed block position → crate id, so dispatching a use never scans crates. It
 * is loaded in full at enable and does not depend on chunks being loaded; block
 * identity is checked when a crate is used.
 */
public final class CrateService {

    /**
     * Block keys of the crate blocks shipped in the Mystic Essentials asset pack;
     * also the key of the one shipped today
     * ({@code Server/Item/Items/MysticEssentials/MysticEssentials_LootBox_Crate.json}).
     */
    public static final String SHIPPED_PREFIX = "MysticEssentials_LootBox_Crate";

    /** Outcome of a conversion; {@code warning} is set for blocks that work but deserve a heads-up. */
    public record ConvertResult(CrateInstance crate, String problem, String warning) {
    }

    private final MysticCore core;
    private final LootBoxStorage storage;
    private final AuditService audit;
    private final Map<String, CrateInstance> crates = new ConcurrentHashMap<>();
    private final Map<String, Map<Long, String>> index = new ConcurrentHashMap<>();

    public CrateService(MysticCore core, LootBoxStorage storage, AuditService audit) {
        this.core = core;
        this.storage = storage;
        this.audit = audit;
    }

    public CompletableFuture<Void> load() {
        return storage.listCrates().thenCompose(ids -> {
            List<CompletableFuture<Void>> loads = new ArrayList<>();
            for (String id : ids) {
                loads.add(storage.loadCrate(id).thenAccept(crate -> {
                    if (crate != null && crate.crateId != null) {
                        put(crate);
                    }
                }));
            }
            return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new));
        });
    }

    public List<CrateInstance> list() {
        List<CrateInstance> all = new ArrayList<>(crates.values());
        all.sort((a, b) -> a.crateId.compareTo(b.crateId));
        return all;
    }

    public Optional<CrateInstance> get(String crateId) {
        return Optional.ofNullable(crateId == null ? null : crates.get(crateId));
    }

    /** The crate at a block, by world UUID and (for older data) world name. */
    public Optional<CrateInstance> at(World world, int x, int y, int z) {
        long packed = pack(x, y, z);
        String id = lookup(BlockTargets.worldId(world), packed);
        if (id == null && world != null) {
            id = lookup(world.getName(), packed);
        }
        return Optional.ofNullable(id == null ? null : crates.get(id));
    }

    private String lookup(String worldId, long packed) {
        Map<Long, String> world = index.get(worldId);
        return world == null ? null : world.get(packed);
    }

    /** Turns {@code block} into a crate for {@code lootboxId}. MUST run on the world thread. */
    public ConvertResult convert(BlockTargets.Block block, String lootboxId, String requestedId, String actor) {
        if (block == null) {
            return new ConvertResult(null, "look at a block within reach", null);
        }
        if (block.isEmpty()) {
            return new ConvertResult(null, "that's air", null);
        }
        if (block.type().isUnknown()) {
            return new ConvertResult(null, "that block's type is missing from the server (unknown placeholder)", null);
        }
        boolean shipped = block.key().startsWith(SHIPPED_PREFIX);
        if (!block.hasUse()) {
            return new ConvertResult(null, "this block has no Use interaction, so players couldn't open it. Use "
                    + "an interactive block (chest, door, bench) or a Mystic Essentials crate block. Plain blocks "
                    + "become supported in a later update", null);
        }
        if (at(block.world(), block.x(), block.y(), block.z()).isPresent()) {
            return new ConvertResult(null, "that block is already a crate", null);
        }
        String crateId = requestedId == null || requestedId.isBlank() ? lootboxId + "_" + Long.toString(
                System.currentTimeMillis() % 1_000_000_000L, 36) : requestedId.toLowerCase();
        if (!DraftValidator.LOOTBOX_ID.matcher(crateId).matches() || crates.containsKey(crateId)) {
            return new ConvertResult(null, "crate id '" + crateId + "' is invalid or taken", null);
        }
        CrateInstance crate = new CrateInstance();
        crate.crateId = crateId;
        crate.worldId = block.worldId();
        crate.worldName = block.world().getName();
        crate.x = block.x();
        crate.y = block.y();
        crate.z = block.z();
        crate.blockKey = block.key();
        crate.lootboxId = lootboxId;
        crate.kind = shipped ? CrateInstance.KIND_SHIPPED : CrateInstance.KIND_INTERACTIVE;
        crate.createdBy = actor;
        crate.createdAt = System.currentTimeMillis();
        put(crate);
        save(crate);
        audit.record(actor, "CRATE_CONVERT", crateId, lootboxId + " at " + crate.position() + " block " + crate.blockKey);
        core.getEventBus().publish(new CrateConvertedEvent(crateId, lootboxId, crate.worldId, crate.x, crate.y,
                crate.z, crate.blockKey, actor));
        String warning = shipped ? null
                : block.type().getBlockEntity() != null
                        ? "this block keeps its own data (a chest's contents, a bench's state); players can't "
                                + "reach it while it's a crate"
                        : null;
        return new ConvertResult(crate, null, warning);
    }

    public Optional<CrateInstance> remove(String crateId, String actor) {
        CrateInstance crate = crates.remove(crateId);
        if (crate == null) {
            return Optional.empty();
        }
        Map<Long, String> world = index.get(crate.worldId);
        if (world != null) {
            world.remove(pack(crate.x, crate.y, crate.z));
        }
        storage.deleteCrate(crateId);
        audit.record(actor, "CRATE_REMOVE", crateId, crate.position());
        core.getEventBus().publish(new CrateRemovedEvent(crateId, crate.lootboxId, "removed", actor));
        return Optional.of(crate);
    }

    /** Disables a crate whose block changed or was destroyed. Keeps it in the index so it can be repaired. */
    public void disable(CrateInstance crate, String reason, String actor) {
        if (crate.disabled && reason.equals(crate.disabledReason)) {
            return;
        }
        crate.disabled = true;
        crate.disabledReason = reason;
        save(crate);
        audit.record(actor, "CRATE_DISABLE", crate.crateId, reason + " at " + crate.position());
        core.getEventBus().publish(new CrateRemovedEvent(crate.crateId, crate.lootboxId, reason, actor));
        core.getEventBus().publish(new OpeningFlaggedEvent(null, crate.lootboxId, "crate disabled", reason));
    }

    public void enable(CrateInstance crate, String actor) {
        crate.disabled = false;
        crate.disabledReason = "";
        save(crate);
        audit.record(actor, "CRATE_ENABLE", crate.crateId, crate.position());
    }

    /**
     * Checks every crate in {@code world} whose chunk is loaded. MUST run on the
     * world thread. @return lines describing each crate's state.
     */
    public List<String> verify(World world) {
        List<String> lines = new ArrayList<>();
        String worldId = BlockTargets.worldId(world);
        for (CrateInstance crate : list()) {
            if (!worldId.equals(crate.worldId) && !world.getName().equals(crate.worldName)) {
                continue;
            }
            BlockTargets.Block block = BlockTargets.at(world, crate.x, crate.y, crate.z);
            if (block == null) {
                lines.add(crate.crateId + ": chunk not loaded");
            } else if (!block.key().equals(crate.blockKey)) {
                disable(crate, "block is now " + block.key(), "verify");
                lines.add(crate.crateId + ": DISABLED, block changed to " + block.key());
            } else {
                lines.add(crate.crateId + ": ok" + (crate.disabled ? " (disabled: " + crate.disabledReason + ")" : ""));
            }
        }
        return lines;
    }

    private void put(CrateInstance crate) {
        crates.put(crate.crateId, crate);
        index.computeIfAbsent(crate.worldId, id -> new ConcurrentHashMap<>()).put(pack(crate.x, crate.y, crate.z),
                crate.crateId);
    }

    private void save(CrateInstance crate) {
        storage.saveCrate(crate).exceptionally(t -> {
            core.log(Level.WARNING, "[lootbox] could not save crate " + crate.crateId + ": " + t);
            return null;
        });
    }

    /** 26 bits x, 26 bits z, 12 bits y: covers ±33M horizontally and any build height. */
    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }
}
