package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

import org.hyzionstudios.mysticessentials.modules.lootbox.model.CrateInstance;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * The engine-free half of generated block variants (P2-J, the RAW design in
 * {@code phase0-spike-results.md} §3): names, the documents we write, which
 * plain blocks may get a look-alike, and which generated files nothing uses.
 * Checked by {@code verifyLootBoxCrates}.
 *
 * <p>A variant is our own minimal document,
 * {@code {"Parent": <original>, "Interactions": {"Use": {"Interactions": [{"Type": "MysticCrate"}]}}}},
 * in the directory pack {@code mods/MysticEssentialsGenerated/}. Because it
 * names its original as {@code Parent}, it looks like the original and follows
 * later changes to it. The engine's {@code writeAssetToDisk} is never used: its
 * flattened output fails validation at boot (Phase 0, S2).</p>
 */
public final class VariantRules {

    /** Appended to the original block key; the spike's {@code _LBSpikeCrate} keys loaded the same way. */
    public static final String SUFFIX = "_MysticCrate";
    /** The generated pack's folder under the server's {@code mods/}. */
    public static final String PACK_FOLDER = "MysticEssentialsGenerated";
    public static final String PACK_GROUP = "org.hyzionstudios";
    public static final String PACK_NAME = "MysticEssentialsGenerated";
    /** Variant documents live in {@code Server/<BlockType store path>/<this>/<key>.json}. */
    public static final String VARIANT_FOLDER = "MysticCrate";
    /** The interaction type every variant's Use runs ({@code CrateInteraction.TYPE_ID}). */
    public static final String INTERACTION_TYPE = "MysticCrate";

    private VariantRules() {
    }

    public static boolean isVariant(String key) {
        return key != null && key.endsWith(SUFFIX) && key.length() > SUFFIX.length();
    }

    public static String variantKey(String originalKey) {
        return originalKey + SUFFIX;
    }

    /** The original block of {@code variantKey}, or {@code null} when it is not a variant key. */
    public static String originalOf(String variantKey) {
        return isVariant(variantKey) ? variantKey.substring(0, variantKey.length() - SUFFIX.length()) : null;
    }

    /**
     * Why {@code block} (a plain block without a Use interaction) can't get a
     * generated look-alike, or {@code null} when it can. Only single-cell
     * blocks with nothing that would be lost or rebuilt by the swap qualify.
     */
    public static String ineligible(CrateRules.BlockFacts block) {
        String key = block.key() == null ? "" : block.key();
        if (key.isBlank() || key.startsWith("*")) {
            return "it is an engine-generated block state";
        }
        if (isVariant(key)) {
            return "it is already a generated look-alike";
        }
        if (block.blockEntity()) {
            return "it keeps its own data, which the swap would reset";
        }
        if (block.multiCell() || block.fillerPart()) {
            return "it spans more than one block";
        }
        if (block.stateful()) {
            return "it has block states that would swap it back";
        }
        if (block.connected()) {
            return "it connects to its neighbours, which would rebuild it";
        }
        return null;
    }

    /** Our minimal variant document: a {@code Parent} and an inline Use, nothing else. */
    public static String document(String originalKey) {
        JsonObject interaction = new JsonObject();
        interaction.addProperty("Type", INTERACTION_TYPE);
        JsonArray interactions = new JsonArray();
        interactions.add(interaction);
        JsonObject use = new JsonObject();
        use.add("Interactions", interactions);
        JsonObject roots = new JsonObject();
        roots.add("Use", use);
        JsonObject document = new JsonObject();
        document.addProperty("Parent", originalKey);
        document.add("Interactions", roots);
        return new GsonBuilder().setPrettyPrinting().create().toJson(document) + "\n";
    }

    /** The generated pack's manifest. No {@code Main}: it is assets only. */
    public static String manifest(String serverVersion) {
        JsonObject author = new JsonObject();
        author.addProperty("Name", "MysticEssentials");
        JsonArray authors = new JsonArray();
        authors.add(author);
        JsonObject manifest = new JsonObject();
        manifest.addProperty("Group", PACK_GROUP);
        manifest.addProperty("Name", PACK_NAME);
        manifest.addProperty("Version", "1.0.0");
        manifest.addProperty("ServerVersion", serverVersion == null || serverVersion.isBlank() ? "*" : serverVersion);
        manifest.addProperty("Description", "Block look-alikes MysticEssentials generates so plain blocks can be "
                + "LootBox crates. Managed by the plugin; don't edit or delete while crates use them.");
        manifest.add("Authors", authors);
        return new GsonBuilder().setPrettyPrinting().create().toJson(manifest) + "\n";
    }

    /** Variant keys some crate still records, so their files must stay. */
    public static Set<String> inUse(Collection<CrateInstance> crates) {
        Set<String> used = new TreeSet<>();
        for (CrateInstance crate : crates) {
            if (CrateInstance.KIND_GENERATED.equals(crate.kind) && crate.blockKey != null) {
                used.add(crate.blockKey);
            }
        }
        return used;
    }

    /** Generated variant keys on disk that no crate records. */
    public static Set<String> unused(Collection<String> onDisk, Collection<CrateInstance> crates) {
        Set<String> used = inUse(crates);
        Set<String> unused = new TreeSet<>();
        for (String key : onDisk) {
            if (isVariant(key) && !used.contains(key)) {
                unused.add(key);
            }
        }
        return unused;
    }
}
