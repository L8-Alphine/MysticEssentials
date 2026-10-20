package org.hyzionstudios.mysticessentials.modules.lootbox;

import java.util.List;
import java.util.Set;

import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.CrateInstance;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.CrateRules;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.CrateRules.BlockFacts;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.CrateRules.RepairAction;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.CrateRules.Spot;

/**
 * Engine-free checks of the Phase 2 crate logic (P2-G onwards): the
 * compatibility report, the repair wizard's decisions, protection that can only
 * tighten, the editor's settings hash, crate ids, and old crate documents
 * loading with the new defaults.
 */
public final class LootBoxCratesTest {

    private LootBoxCratesTest() {
    }

    public static void main(String[] args) {
        compatibilityRefusesWhatCannotWork();
        compatibilityWarnsAboutInteractiveBlocks();
        repairOffersWhatTheSpotAllows();
        protectionOnlyTightens();
        settingsHashFollowsEditableFields();
        crateIdsAreSuggestedAndChecked();
        schemaOneDocumentsLoad();
        System.out.println("LootBoxCratesTest: all checks passed.");
    }

    // ----- Compatibility ------------------------------------------------------

    private static BlockFacts plain(String key) {
        return new BlockFacts(key, false, false, null, false, false, false, false, false, false, false, false);
    }

    private static BlockFacts chest() {
        return new BlockFacts("Furniture_Crude_Chest_Small", false, false, "*Chest_Use", true, false, false, false, false,
                false, true, false);
    }

    private static BlockFacts shipped() {
        return new BlockFacts(CrateRules.SHIPPED_PREFIX, false, false, "*MysticEssentials_LootBox_Crate_Use", false,
                false, false, false, false, false, false, false);
    }

    private static void compatibilityRefusesWhatCannotWork() {
        require(!CrateRules.check(null, false).ok(), "nothing targeted was accepted");
        require(!CrateRules.check(new BlockFacts("Empty", true, false, null, false, false, false, false, false, false,
                false, false), false).ok(), "air was accepted");
        require(!CrateRules.check(new BlockFacts("Gone", false, true, "*Use", false, false, false, false, false, false,
                false, false), false).ok(), "an unknown placeholder was accepted");
        require(!CrateRules.check(plain("Rock_Stone"), false).ok(), "a block without Use was accepted");
        require(CrateRules.check(plain("Rock_Stone"), false).problem().contains("no Use interaction"),
                "the no-Use refusal does not say why");
        require(!CrateRules.check(chest(), true).ok(), "a block that is already a crate was accepted");
        require(!CrateRules.check(new BlockFacts("Sand", false, false, "*Use", false, false, false, false, false, false,
                false, true), false).ok(), "a falling block was accepted");
        CrateRules.Report kit = CrateRules.check(shipped(), false);
        require(kit.ok() && CrateInstance.KIND_SHIPPED.equals(kit.kind()) && kit.warnings().isEmpty(),
                "the shipped crate block is not a clean SHIPPED_BLOCK");
    }

    private static void compatibilityWarnsAboutInteractiveBlocks() {
        CrateRules.Report chest = CrateRules.check(chest(), false);
        require(chest.ok() && CrateInstance.KIND_INTERACTIVE.equals(chest.kind()), "a chest was refused");
        require(chest.warnings().stream().anyMatch(w -> w.contains("own data")), "no warning about a chest's contents");
        require(chest.warnings().stream().anyMatch(w -> w.contains("states")), "no warning about block states");
        CrateRules.Report door = CrateRules.check(new BlockFacts("Door_Wood", false, false, "*Door_Use", false, true,
                false, false, false, false, false, false), false);
        require(door.ok() && door.warnings().size() == 1 && door.warnings().get(0).contains("door"),
                "a door's warning is wrong: " + door.warnings());
        CrateRules.Report portal = CrateRules.check(new BlockFacts("Portal_Device", false, false, "*Portal_Use", false,
                false, false, false, false, false, false, false), false);
        require(portal.ok() && portal.warnings().stream().anyMatch(w -> w.contains("portal")), "no portal warning");
        CrateRules.Report bench = CrateRules.check(new BlockFacts("Bench_Work", false, false, "*Bench_Use", false,
                false, true, true, true, true, false, false), false);
        require(bench.warnings().size() == 4, "bench, seat, rail and trigger should each warn: " + bench.warnings());
    }

    // ----- Repair -------------------------------------------------------------

    private static CrateInstance crate(String blockKey, boolean disabled) {
        CrateInstance crate = new CrateInstance();
        crate.crateId = "avalon_supply_1";
        crate.lootboxId = "avalon_supply";
        crate.worldId = "w";
        crate.worldName = "default";
        crate.blockKey = blockKey;
        crate.disabled = disabled;
        crate.disabledReason = disabled ? "block is now Empty" : "";
        return crate.normalise();
    }

    private static void repairOffersWhatTheSpotAllows() {
        CrateInstance off = crate("Furniture_Crude_Chest_Small", true);
        CrateRules.RepairPlan unchecked = CrateRules.repair(off, null, null);
        require(unchecked.spot() == Spot.UNCHECKED && unchecked.actions().equals(List.of(RepairAction.REMOVE)),
                "an unreadable spot must only offer Remove");

        CrateRules.RepairPlan back = CrateRules.repair(off, chest(), CrateRules.check(chest(), false));
        require(back.spot() == Spot.MATCHES && back.actions().contains(RepairAction.REENABLE)
                && !back.actions().contains(RepairAction.REBIND_BLOCK), "a matching block must offer Re-enable only");

        BlockFacts other = new BlockFacts("Furniture_Royal_Chest", false, false, "*Chest_Use", true, false, false, false,
                false, false, false, false);
        CrateRules.RepairPlan changed = CrateRules.repair(off, other, CrateRules.check(other, false));
        require(changed.spot() == Spot.CHANGED_COMPATIBLE && changed.actions().contains(RepairAction.REBIND_BLOCK)
                && !changed.actions().contains(RepairAction.REENABLE), "a compatible new block must offer Use This Block");

        BlockFacts air = new BlockFacts("Empty", true, false, null, false, false, false, false, false, false, false,
                false);
        CrateRules.RepairPlan gone = CrateRules.repair(off, air, CrateRules.check(air, false));
        require(gone.spot() == Spot.CHANGED_INCOMPATIBLE && gone.actions().equals(List.of(RepairAction.REMOVE)),
                "a destroyed block must only offer Remove");
        require(gone.summary().contains("nothing"), "the summary should say the spot is empty: " + gone.summary());

        CrateRules.RepairPlan fine = CrateRules.repair(crate("Furniture_Crude_Chest_Small", false), chest(),
                CrateRules.check(chest(), false));
        require(!fine.actions().contains(RepairAction.REENABLE), "an active crate was offered Re-enable");
    }

    // ----- Protection ---------------------------------------------------------

    private static void protectionOnlyTightens() {
        CrateInstance crate = crate("Furniture_Crude_Chest_Small", false);
        require(CrateRules.preventsBreak(crate, true) && CrateRules.preventsDamage(crate, true),
                "the server's protection did not apply");
        require(!CrateRules.preventsBreak(crate, false) && !CrateRules.preventsDamage(crate, false),
                "a default crate protected beyond the server");
        crate.protection.preventPlayerBreak = true;
        require(CrateRules.preventsBreak(crate, false) && !CrateRules.preventsDamage(crate, false),
                "the crate's break protection did not tighten the server's");
        crate.protection.preventDamage = true;
        require(CrateRules.preventsDamage(crate, false), "the crate's damage protection did not tighten the server's");
        crate.protection.preventPlayerBreak = false;
        crate.protection.preventDamage = false;
        require(CrateRules.preventsBreak(crate, true) && CrateRules.preventsDamage(crate, true),
                "a crate switched the server's protection off");
        crate.protection = null;
        require(CrateRules.preventsBreak(crate, true) && !CrateRules.preventsBreak(crate, false),
                "a crate without protection settings broke the rule");
    }

    // ----- Editor -------------------------------------------------------------

    private static void settingsHashFollowsEditableFields() {
        CrateInstance crate = crate("Furniture_Crude_Chest_Small", false);
        String hash = CrateRules.settingsHash(crate);
        require(hash.equals(CrateRules.settingsHash(crate.copy())), "a copy hashes differently");
        CrateInstance status = crate.copy();
        status.disabled = true;
        status.disabledReason = "block is now Empty";
        status.updatedAt = 42;
        require(hash.equals(CrateRules.settingsHash(status)), "a status change counted as an editor conflict");
        for (java.util.function.Consumer<CrateInstance> change : List.<java.util.function.Consumer<CrateInstance>>of(
                c -> c.lootboxId = "other_pool", c -> c.name = "Royal", c -> c.openBehavior = CrateInstance.OPEN_DIRECT_CONFIRM,
                c -> c.protection.preventPlayerBreak = true, c -> c.protection.preventDamage = true,
                c -> c.visuals.idleParticle = "Dust_Sparkles", c -> c.visuals.openParticle = "Block_Break_Treasure",
                c -> c.visuals.openSound = "SFX_Chest_Wooden_Open", c -> c.visuals.nameplate = true)) {
            CrateInstance edited = crate.copy();
            change.accept(edited);
            require(!hash.equals(CrateRules.settingsHash(edited)), "an editable change kept the same hash");
        }
    }

    private static void crateIdsAreSuggestedAndChecked() {
        Set<String> taken = Set.of("avalon_supply_1", "avalon_supply_2");
        require("avalon_supply_3".equals(CrateRules.suggestId("avalon_supply", taken::contains)),
                "the suggested id is not the next free one");
        require("crate_1".equals(CrateRules.suggestId(null, id -> false)), "no pool should suggest crate_1");
        String suggested = CrateRules.suggestId("a".repeat(48), id -> false);
        require(CrateRules.CRATE_ID.matcher(suggested).matches(), "a long pool id produced an invalid crate id: "
                + suggested);
        require(!CrateRules.CRATE_ID.matcher("Bad-Id").matches() && !CrateRules.CRATE_ID.matcher("ab").matches(),
                "invalid crate ids were accepted");
    }

    // ----- Schema -------------------------------------------------------------

    private static void schemaOneDocumentsLoad() {
        String v1 = """
                {"schema":1,"crateId":"old_crate","worldId":"uuid","worldName":"default","x":1,"y":64,"z":-3,
                 "blockKey":"Furniture_Crude_Chest_Small","lootboxId":"Example_Supply","kind":"INTERACTIVE","name":"",
                 "disabled":false,"disabledReason":"","createdBy":"console","createdAt":1}
                """;
        CrateInstance loaded = Json.gson().fromJson(Json.parse(v1), CrateInstance.class).normalise();
        require(CrateInstance.OPEN_PREVIEW_THEN_CONFIRM.equals(loaded.openBehavior) && !loaded.directConfirm(),
                "a schema 1 crate did not default to preview first");
        require(loaded.protection != null && !loaded.protection.preventPlayerBreak && !loaded.protection.preventDamage,
                "a schema 1 crate did not get the server's protection");
        require(loaded.schema == CrateInstance.SCHEMA, "the schema was not upgraded");
        require(loaded.visuals != null && loaded.visuals.idleParticle.isEmpty() && loaded.visuals.openParticle.isEmpty()
                && loaded.visuals.openSound.isEmpty() && !loaded.visuals.nameplate,
                "an old crate gained effects on load; existing crates must stay quiet after an update");
        require("example_supply".equals(loaded.lootboxId), "the pool id was not normalised");
        require("default 1,64,-3".equals(loaded.position()), "position changed: " + loaded.position());

        CrateInstance nulled = Json.gson().fromJson(Json.parse(
                "{\"crateId\":\"x_crate\",\"openBehavior\":null,\"protection\":null,\"name\":null,\"kind\":null,"
                        + "\"visuals\":{\"idleParticle\":null,\"openSound\":\" SFX_Chest_Wooden_Open \"}}"),
                CrateInstance.class).normalise();
        require(nulled.protection != null && nulled.name != null && CrateInstance.KIND_INTERACTIVE.equals(nulled.kind)
                && CrateInstance.OPEN_PREVIEW_THEN_CONFIRM.equals(nulled.openBehavior), "explicit nulls were kept");
        require(nulled.visuals.idleParticle.isEmpty() && "SFX_Chest_Wooden_Open".equals(nulled.visuals.openSound),
                "visual ids were not cleaned: " + nulled.visuals.openSound);

        CrateInstance direct = loaded.copy();
        direct.openBehavior = "direct_confirm";
        require(direct.normalise().directConfirm(), "a lower-case behaviour was not understood");
        CrateInstance roundTrip = Json.gson().fromJson(Json.toTree(direct), CrateInstance.class).normalise();
        require(roundTrip.directConfirm() && CrateRules.settingsHash(roundTrip).equals(CrateRules.settingsHash(direct)),
                "a schema 2 crate did not survive a storage round trip");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
