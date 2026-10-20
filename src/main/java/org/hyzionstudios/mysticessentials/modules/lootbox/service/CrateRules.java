package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.hyzionstudios.mysticessentials.modules.lootbox.model.CrateInstance;

/**
 * The crate decisions that need no engine: whether a block may become a crate
 * and what to warn about (bible §09 step 03), which repairs a disabled crate
 * can take, the effective protection, and the settings hash the crate editor
 * uses to notice a second editor's save. Checked by {@code verifyLootBoxCrates}.
 */
public final class CrateRules {

    /**
     * Block keys of the crate blocks shipped in the Mystic Essentials asset pack;
     * also the key of the one shipped today
     * ({@code Server/Item/Items/MysticEssentials/MysticEssentials_LootBox_Crate.json}).
     */
    public static final String SHIPPED_PREFIX = "MysticEssentials_LootBox_Crate";

    /** Crate ids share the pool id pattern ({@code DraftValidator.LOOTBOX_ID}). */
    public static final Pattern CRATE_ID = Pattern.compile("[a-z0-9_]{3,48}");

    /**
     * What the engine says about a block, read on the world thread by
     * {@code BlockTargets} and judged here.
     *
     * @param key           block type key
     * @param empty         air or the engine's "Empty" block
     * @param unknown       the engine's placeholder for a missing asset
     * @param useRoot       the Use root interaction id, or {@code null}
     * @param blockEntity   keeps per-block data (a chest's contents, a bench's state)
     * @param door          declared a door
     * @param bench         a crafting bench
     * @param seat          has seats or beds
     * @param rail          a rail or other transport block
     * @param trigger       a trigger block
     * @param stateful      has block states that can swap its key (open, lit, …)
     * @param falls         becomes a falling block when unsupported
     * @param multiCell     its hitbox spans more than one block
     * @param fillerPart    this cell is a filler part of a multi-block
     * @param connected     it has connected-block rules (fences, panes)
     */
    public record BlockFacts(String key, boolean empty, boolean unknown, String useRoot, boolean blockEntity,
            boolean door, boolean bench, boolean seat, boolean rail, boolean trigger, boolean stateful,
            boolean falls, boolean multiCell, boolean fillerPart, boolean connected) {

        public boolean hasUse() {
            return useRoot != null && !useRoot.isBlank();
        }

        public boolean shipped() {
            return key != null && key.startsWith(SHIPPED_PREFIX);
        }
    }

    /**
     * The compatibility report for one block.
     *
     * @param kind     the crate kind it would become, or {@code null} when refused
     * @param problem  why it can't be a crate, or {@code null}
     * @param warnings things that work but deserve a heads-up
     */
    public record Report(String kind, String problem, List<String> warnings) {

        public boolean ok() {
            return problem == null;
        }
    }

    private CrateRules() {
    }

    // ----- Compatibility ------------------------------------------------------

    /** {@link #check(BlockFacts, boolean, boolean)} with generated variants off. */
    public static Report check(BlockFacts block, boolean alreadyCrate) {
        return check(block, alreadyCrate, false);
    }

    /**
     * Judges {@code block} (bible §09 step 03): refuses air, missing assets,
     * blocks that fall, existing crates and blocks players could not use;
     * warns about containers, doors, benches, seats, transport, triggers,
     * portals and blocks whose state changes their key.
     *
     * @param block              the facts, or {@code null} when nothing is targeted or the chunk is not loaded
     * @param alreadyCrate       another crate already sits on this block
     * @param generatedVariants  {@code crates.generatedVariants}: a plain block without Use may be swapped
     *                           for a generated look-alike ({@link VariantRules}) instead of being refused
     */
    public static Report check(BlockFacts block, boolean alreadyCrate, boolean generatedVariants) {
        if (block == null) {
            return refused("look at a block within reach");
        }
        if (block.empty()) {
            return refused("that's air");
        }
        if (block.unknown()) {
            return refused("that block's type is missing from the server (unknown placeholder)");
        }
        if (alreadyCrate) {
            return refused("that block is already a crate");
        }
        if (block.falls()) {
            return refused("this block falls when what's under it goes, which would break the crate. Use a block "
                    + "that stays put");
        }
        if (!block.hasUse()) {
            if (!generatedVariants) {
                return refused("this block has no Use interaction, so players couldn't open it. Use an interactive "
                        + "block (chest, door, bench) or a Mystic Essentials crate block");
            }
            String why = VariantRules.ineligible(block);
            if (why != null) {
                return refused("this plain block can't get a generated look-alike: " + why);
            }
            return new Report(CrateInstance.KIND_GENERATED, null, List.of(
                    "This plain block is swapped for a generated look-alike that players can use; removing the "
                            + "crate puts the original back.",
                    "Generated look-alikes are not yet tested in game: check the block still looks the same."));
        }
        if (VariantRules.isVariant(block.key())) {
            return new Report(CrateInstance.KIND_GENERATED, null, List.of(
                    "This is a generated look-alike left by an earlier crate; it becomes a crate again."));
        }
        List<String> warnings = new ArrayList<>();
        if (block.shipped()) {
            return new Report(CrateInstance.KIND_SHIPPED, null, warnings);
        }
        String root = block.useRoot().toLowerCase(Locale.ROOT);
        String key = block.key() == null ? "" : block.key().toLowerCase(Locale.ROOT);
        if (block.blockEntity()) {
            warnings.add("This block keeps its own data (a chest's contents, a bench's state); players can't reach "
                    + "it while it's a crate.");
        }
        if (block.door()) {
            warnings.add("This is a door: it stays shut while it's a crate.");
        }
        if (block.bench()) {
            warnings.add("This is a crafting bench: nobody can craft on it while it's a crate.");
        }
        if (block.seat()) {
            warnings.add("This block has seats or beds: nobody can sit or sleep on it while it's a crate.");
        }
        if (block.rail()) {
            warnings.add("This is a transport block (rail); carts may still use it, but players can't.");
        }
        if (block.trigger()) {
            warnings.add("This is a trigger block; whatever it triggers stops working on Use.");
        }
        if (root.contains("portal") || root.contains("teleport") || key.contains("portal")
                || key.contains("teleport")) {
            warnings.add("This looks like a portal or teleporter: it stops working while it's a crate.");
        }
        if (block.stateful()) {
            warnings.add("This block has states (open, lit, grown). If something changes its state, the crate "
                    + "turns itself off until you repair it.");
        }
        return new Report(CrateInstance.KIND_INTERACTIVE, null, warnings);
    }

    private static Report refused(String problem) {
        return new Report(null, problem, List.of());
    }

    // ----- Repair -------------------------------------------------------------

    /** What a disabled crate's spot holds now, compared with what the crate recorded. */
    public enum Spot {
        /** The chunk is not loaded, or the player is in another world: nothing can be checked. */
        UNCHECKED,
        /** The recorded block is back. */
        MATCHES,
        /** A different block that could be a crate. */
        CHANGED_COMPATIBLE,
        /** A different block that could not be a crate (including air). */
        CHANGED_INCOMPATIBLE
    }

    /** One action the repair wizard can offer. */
    public enum RepairAction {
        /** Turn the crate back on; offered when the recorded block is there again. */
        REENABLE,
        /** Record the block that is there now and turn the crate back on. */
        REBIND_BLOCK,
        /** Delete the crate (the block is left alone). */
        REMOVE
    }

    /**
     * The repair options for {@code crate}, given what its spot holds now.
     *
     * @param crate   the crate (disabled or not)
     * @param current the facts at its position, or {@code null} when they could not be read
     * @param report  {@link #check} for {@code current}, ignoring the crate itself
     */
    public record RepairPlan(Spot spot, List<RepairAction> actions, String summary) {
    }

    public static RepairPlan repair(CrateInstance crate, BlockFacts current, Report report) {
        if (current == null) {
            return new RepairPlan(Spot.UNCHECKED, List.of(RepairAction.REMOVE),
                    "The block can't be checked from here. Stand near " + crate.position()
                            + " (its chunk must be loaded) and open this again, or remove the crate.");
        }
        if (current.key() != null && current.key().equals(crate.blockKey) && !current.unknown()) {
            List<RepairAction> actions = crate.disabled ? List.of(RepairAction.REENABLE, RepairAction.REMOVE)
                    : List.of(RepairAction.REMOVE);
            return new RepairPlan(Spot.MATCHES, actions, crate.disabled
                    ? "The recorded block (" + crate.blockKey + ") is there again, so the crate can be turned back on."
                    : "The block matches what the crate recorded.");
        }
        if (report != null && report.ok()) {
            return new RepairPlan(Spot.CHANGED_COMPATIBLE, List.of(RepairAction.REBIND_BLOCK, RepairAction.REMOVE),
                    "The spot now holds " + current.key() + " instead of " + crate.blockKey
                            + ". It can be a crate: record it and turn the crate back on, or remove the crate.");
        }
        String why = report == null || report.problem() == null ? "it can't be a crate" : report.problem();
        return new RepairPlan(Spot.CHANGED_INCOMPATIBLE, List.of(RepairAction.REMOVE),
                "The spot now holds " + (current.empty() ? "nothing" : current.key()) + " instead of "
                        + crate.blockKey + ", and " + why + ". Put a suitable block back and check again, or "
                        + "remove the crate.");
    }

    // ----- Protection ---------------------------------------------------------

    /** Players can't break the crate when the server or the crate says so; a crate can only tighten. */
    public static boolean preventsBreak(CrateInstance crate, boolean serverDefault) {
        return serverDefault || (crate != null && crate.protection != null && crate.protection.preventPlayerBreak);
    }

    /** The crate takes no damage when the server or the crate says so; a crate can only tighten. */
    public static boolean preventsDamage(CrateInstance crate, boolean serverDefault) {
        return serverDefault || (crate != null && crate.protection != null && crate.protection.preventDamage);
    }

    // ----- Editor -------------------------------------------------------------

    /**
     * A hash of what the crate editor may change (pool, name, behaviour,
     * protection, visuals), so a save can refuse when another editor saved since this
     * one loaded (the pool editor's P2-D2 rule). Status changes made by the
     * server (disable on a block change) are not part of it.
     */
    public static String settingsHash(CrateInstance crate) {
        CrateInstance c = crate.copy();
        String text = String.join("\u0000", String.valueOf(c.lootboxId), String.valueOf(c.name),
                String.valueOf(c.openBehavior), Boolean.toString(c.protection.preventPlayerBreak),
                Boolean.toString(c.protection.preventDamage), c.visuals.idleParticle, c.visuals.openParticle,
                c.visuals.openSound, Boolean.toString(c.visuals.nameplate));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** A free crate id for {@code lootboxId}: {@code <pool>_<n>} with the lowest unused n from 1. */
    public static String suggestId(String lootboxId, java.util.function.Predicate<String> taken) {
        String base = lootboxId == null || lootboxId.isBlank() ? "crate" : lootboxId.toLowerCase(Locale.ROOT);
        if (base.length() > 40) {
            base = base.substring(0, 40);
        }
        for (int n = 1; n < 100_000; n++) {
            String id = base + "_" + n;
            if (!taken.test(id)) {
                return id;
            }
        }
        return base + "_" + Long.toString(System.currentTimeMillis() % 1_000_000_000L, 36);
    }
}
