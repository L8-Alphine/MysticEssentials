package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

import com.hypixel.hytale.server.core.universe.world.World;

/**
 * Crate instances and their position index (§4.6). The index maps world →
 * packed block position → crate id, so dispatching a use never scans crates. It
 * is loaded in full at enable and does not depend on chunks being loaded; block
 * identity is checked when a crate is used.
 *
 * <p>Methods that read a block take a {@link BlockTargets.Block} the caller read
 * on the world thread; the rest only touch the in-memory index and storage. The
 * decisions themselves (compatibility, repair, protection) are in
 * {@link CrateRules}.</p>
 */
public final class CrateService {

    /** See {@link CrateRules#SHIPPED_PREFIX}. */
    public static final String SHIPPED_PREFIX = CrateRules.SHIPPED_PREFIX;

    /** Outcome of a conversion; {@code warnings} are for blocks that work but deserve a heads-up. */
    public record ConvertResult(CrateInstance crate, String problem, List<String> warnings) {

        /** The warnings as one sentence, or {@code null} when there are none. */
        public String warning() {
            return warnings == null || warnings.isEmpty() ? null : String.join(" ", warnings);
        }
    }

    /** Outcome of a crate editor save. */
    public sealed interface SaveResult permits Saved, Conflict, Refused {
    }

    /** Saved; {@code hash} is the new {@link CrateRules#settingsHash}. */
    public record Saved(CrateInstance crate, String hash) implements SaveResult {
    }

    /** Someone saved this crate since the editor loaded it; {@code current} is their version. */
    public record Conflict(CrateInstance current) implements SaveResult {
    }

    public record Refused(String reason) implements SaveResult {
    }

    /** What {@link #verify} found in one world. */
    public record Verified(List<String> lines, int checked, int ok, int newlyDisabled, int unloaded) {
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
                        put(crate.normalise());
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

    public boolean exists(String crateId) {
        return crateId != null && crates.containsKey(crateId);
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

    /** Whether {@code crate} sits in {@code world}, by UUID or (for older data) by name. */
    public static boolean inWorld(CrateInstance crate, World world) {
        return world != null && (BlockTargets.worldId(world).equals(crate.worldId)
                || world.getName().equals(crate.worldName));
    }

    // ----- Conversion ---------------------------------------------------------

    /**
     * The compatibility report for {@code block} (bible §09 step 03), counting a
     * crate already on it, except {@code ignoring}.
     */
    public CrateRules.Report check(BlockTargets.Block block, CrateInstance ignoring) {
        if (block == null) {
            return CrateRules.check(null, false);
        }
        CrateInstance existing = at(block.world(), block.x(), block.y(), block.z()).orElse(null);
        boolean taken = existing != null && (ignoring == null || !existing.crateId.equals(ignoring.crateId));
        return CrateRules.check(block.facts(), taken);
    }

    /**
     * Turns {@code block} into a crate for {@code lootboxId} with {@code visuals}
     * (the server's defaults for a command conversion). MUST run on the world thread.
     */
    public ConvertResult convert(BlockTargets.Block block, String lootboxId, String requestedId,
            CrateInstance.Visuals visuals, String actor) {
        CrateInstance template = new CrateInstance();
        template.crateId = requestedId;
        template.lootboxId = lootboxId;
        template.visuals = visuals == null ? new CrateInstance.Visuals() : visuals;
        return create(block, template, actor);
    }

    /**
     * Turns {@code block} into a crate with {@code template}'s pool and settings
     * (the crate editor's Create). The block is judged again here, so a block
     * that changed since it was selected is refused. MUST run on the world
     * thread. The caller checks that the pool is published.
     */
    public ConvertResult create(BlockTargets.Block block, CrateInstance template, String actor) {
        CrateRules.Report report = check(block, null);
        if (!report.ok()) {
            return new ConvertResult(null, report.problem(), List.of());
        }
        String requested = template.crateId == null ? "" : template.crateId.trim().toLowerCase(java.util.Locale.ROOT);
        String crateId = requested.isEmpty() ? CrateRules.suggestId(template.lootboxId, crates::containsKey) : requested;
        if (!CrateRules.CRATE_ID.matcher(crateId).matches() || crates.containsKey(crateId)) {
            return new ConvertResult(null, "crate id '" + crateId + "' is invalid or taken (3-48 of a-z, 0-9, _)",
                    List.of());
        }
        CrateInstance crate = template.copy();
        crate.crateId = crateId;
        crate.worldId = block.worldId();
        crate.worldName = block.world().getName();
        crate.x = block.x();
        crate.y = block.y();
        crate.z = block.z();
        crate.blockKey = block.key();
        crate.kind = report.kind();
        crate.disabled = false;
        crate.disabledReason = "";
        crate.createdBy = actor;
        crate.createdAt = System.currentTimeMillis();
        crate.updatedBy = actor;
        crate.updatedAt = crate.createdAt;
        crate.normalise();
        synchronized (this) {
            if (crates.containsKey(crateId) || at(block.world(), block.x(), block.y(), block.z()).isPresent()) {
                return new ConvertResult(null, "that block or crate id was taken a moment ago", List.of());
            }
            put(crate);
        }
        save(crate);
        audit.record(actor, "CRATE_CONVERT", crateId, crate.lootboxId + " at " + crate.position() + " block "
                + crate.blockKey + " (" + crate.kind + ", " + crate.openBehavior + ")");
        core.getEventBus().publish(new CrateConvertedEvent(crateId, crate.lootboxId, crate.worldId, crate.x, crate.y,
                crate.z, crate.blockKey, actor));
        return new ConvertResult(crate, null, report.warnings());
    }

    // ----- Editing ------------------------------------------------------------

    /**
     * Applies the editor's settings (pool, name, behaviour, protection, visuals) to the
     * live crate, unless another save changed them since the editor loaded
     * {@code expectedHash} ({@code null} overwrites). Position, block and status
     * are not editor settings and are left alone.
     */
    public SaveResult update(String crateId, CrateInstance edited, String expectedHash, String actor) {
        CrateInstance live = crates.get(crateId);
        if (live == null) {
            return new Refused("the crate was removed meanwhile");
        }
        CrateInstance next = edited.copy();
        List<String> changes = new ArrayList<>();
        synchronized (this) {
            if (expectedHash != null && !expectedHash.equals(CrateRules.settingsHash(live))) {
                return new Conflict(live.copy());
            }
            if (!Objects.equals(live.lootboxId, next.lootboxId)) {
                changes.add("pool " + live.lootboxId + " -> " + next.lootboxId);
                audit.record(actor, "CRATE_REBIND", crateId, live.lootboxId + " -> " + next.lootboxId);
                live.lootboxId = next.lootboxId;
            }
            if (!Objects.equals(live.name, next.name)) {
                changes.add("name");
                live.name = next.name;
            }
            if (!Objects.equals(live.openBehavior, next.openBehavior)) {
                changes.add("behaviour " + next.openBehavior);
                live.openBehavior = next.openBehavior;
            }
            if (live.protection.preventPlayerBreak != next.protection.preventPlayerBreak
                    || live.protection.preventDamage != next.protection.preventDamage) {
                changes.add("protection break=" + next.protection.preventPlayerBreak + " damage="
                        + next.protection.preventDamage);
                live.protection.preventPlayerBreak = next.protection.preventPlayerBreak;
                live.protection.preventDamage = next.protection.preventDamage;
            }
            CrateInstance.Visuals was = live.visuals;
            CrateInstance.Visuals now = next.visuals;
            if (!was.idleParticle.equals(now.idleParticle) || !was.openParticle.equals(now.openParticle)
                    || !was.openSound.equals(now.openSound) || was.nameplate != now.nameplate) {
                changes.add("visuals idle=" + now.idleParticle + " open=" + now.openParticle + " sound="
                        + now.openSound + " nameplate=" + now.nameplate);
                live.visuals = now;
            }
            if (changes.isEmpty()) {
                return new Saved(live.copy(), CrateRules.settingsHash(live));
            }
            live.updatedBy = actor;
            live.updatedAt = System.currentTimeMillis();
        }
        save(live);
        audit.record(actor, expectedHash == null ? "CRATE_OVERWRITE" : "CRATE_UPDATE", crateId,
                String.join("; ", changes));
        return new Saved(live.copy(), CrateRules.settingsHash(live));
    }

    /** Binds {@code crate} to another published pool (the caller checks it is published). */
    public void rebind(CrateInstance crate, String lootboxId, String actor) {
        String previous;
        synchronized (this) {
            previous = crate.lootboxId;
            crate.lootboxId = lootboxId;
            crate.updatedBy = actor;
            crate.updatedAt = System.currentTimeMillis();
        }
        save(crate);
        audit.record(actor, "CRATE_REBIND", crate.crateId, previous + " -> " + lootboxId);
    }

    // ----- Repair -------------------------------------------------------------

    /** The repair wizard's options for {@code crate}; {@code current} is its block now, or {@code null}. */
    public CrateRules.RepairPlan repairPlan(CrateInstance crate, BlockTargets.Block current) {
        if (current == null) {
            return CrateRules.repair(crate, null, null);
        }
        return CrateRules.repair(crate, current.facts(), check(current, crate));
    }

    /**
     * Carries out one repair after checking the block again. MUST run on the
     * crate's world thread with {@code current} read there.
     *
     * @return {@code null} when done, else why not
     */
    public String repair(CrateInstance crate, BlockTargets.Block current, CrateRules.RepairAction action,
            String actor) {
        CrateRules.RepairPlan plan = repairPlan(crate, current);
        if (!plan.actions().contains(action)) {
            return plan.summary();
        }
        switch (action) {
            case REENABLE -> enable(crate, actor);
            case REBIND_BLOCK -> {
                CrateRules.Report report = check(current, crate);
                String previous;
                synchronized (this) {
                    previous = crate.blockKey;
                    crate.blockKey = current.key();
                    crate.kind = report.kind();
                    crate.disabled = false;
                    crate.disabledReason = "";
                    crate.updatedBy = actor;
                    crate.updatedAt = System.currentTimeMillis();
                }
                save(crate);
                audit.record(actor, "CRATE_REPAIR", crate.crateId, "block " + previous + " -> " + current.key());
            }
            case REMOVE -> remove(crate.crateId, actor);
        }
        return null;
    }

    // ----- Status -------------------------------------------------------------

    public Optional<CrateInstance> remove(String crateId, String actor) {
        CrateInstance crate = crates.remove(crateId);
        if (crate == null) {
            return Optional.empty();
        }
        Map<Long, String> world = index.get(crate.worldId);
        if (world != null) {
            world.remove(pack(crate.x, crate.y, crate.z), crateId);
        }
        storage.deleteCrate(crateId);
        audit.record(actor, "CRATE_REMOVE", crateId, crate.position());
        core.getEventBus().publish(new CrateRemovedEvent(crateId, crate.lootboxId, "removed", actor));
        return Optional.of(crate);
    }

    /** Disables a crate whose block changed or was destroyed. Keeps it in the index so it can be repaired. */
    public void disable(CrateInstance crate, String reason, String actor) {
        synchronized (this) {
            if (crate.disabled && reason.equals(crate.disabledReason)) {
                return;
            }
            crate.disabled = true;
            crate.disabledReason = reason;
        }
        save(crate);
        audit.record(actor, "CRATE_DISABLE", crate.crateId, reason + " at " + crate.position());
        core.getEventBus().publish(new CrateRemovedEvent(crate.crateId, crate.lootboxId, reason, actor));
        core.getEventBus().publish(new OpeningFlaggedEvent(null, crate.lootboxId, "crate disabled", reason));
    }

    public void enable(CrateInstance crate, String actor) {
        synchronized (this) {
            crate.disabled = false;
            crate.disabledReason = "";
        }
        save(crate);
        audit.record(actor, "CRATE_ENABLE", crate.crateId, crate.position());
    }

    /**
     * Checks every crate in {@code world} whose chunk is loaded, and disables
     * those whose block changed. MUST run on the world thread.
     */
    public Verified verify(World world) {
        List<String> lines = new ArrayList<>();
        int checked = 0;
        int ok = 0;
        int disabled = 0;
        int unloaded = 0;
        for (CrateInstance crate : list()) {
            if (!inWorld(crate, world)) {
                continue;
            }
            checked++;
            BlockTargets.Block block = BlockTargets.at(world, crate.x, crate.y, crate.z);
            if (block == null) {
                unloaded++;
                lines.add(crate.crateId + ": chunk not loaded");
            } else if (!block.key().equals(crate.blockKey)) {
                boolean wasDisabled = crate.disabled;
                disable(crate, "block is now " + block.key(), "verify");
                if (!wasDisabled) {
                    disabled++;
                }
                lines.add(crate.crateId + ": DISABLED, block changed to " + block.key());
            } else {
                ok++;
                lines.add(crate.crateId + ": ok" + (crate.disabled ? " (disabled: " + crate.disabledReason + ")" : ""));
            }
        }
        return new Verified(lines, checked, ok, disabled, unloaded);
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
