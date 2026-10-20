package org.hyzionstudios.mysticessentials.modules.lootbox.hytale;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Stream;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.CrateInstance;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.VariantRules;

import com.hypixel.hytale.assetstore.AssetLoadResult;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

/**
 * Generated block variants (P2-J): the RAW design of {@code phase0-spike-results.md}
 * §3, so a plain block with no Use interaction can become a crate. Behind
 * {@code crates.generatedVariants} (off by default) and <b>not yet tested in
 * game</b>: the spike proved the server side (registration, inheritance, boot
 * loading), but nobody has seen a swapped block in a client.
 *
 * <ol>
 *   <li>One directory pack, {@code mods/MysticEssentialsGenerated/}, with its
 *       manifest. The engine loads it at boot like any pack in {@code mods/};
 *       when it didn't (first run, or {@code DefaultModsEnabled} off) it is
 *       registered at runtime with {@code AssetModule.registerPack}.</li>
 *   <li>Each variant is our own minimal document from
 *       {@link VariantRules#document}, written to
 *       {@code Server/Item/Block/Blocks/MysticCrate/<key>.json} (folders
 *       created). {@code writeAssetToDisk} is never used.</li>
 *   <li>It goes live with {@code AssetStore.loadAssetsFromPaths}, the boot path.</li>
 *   <li>The block is swapped with {@code BlockOperations.setBlock}, keeping its
 *       rotation and filler; the crate records the original key, and removing
 *       the crate swaps it back.</li>
 *   <li>At enable every recorded variant must resolve and not be unknown;
 *       variants no crate records are unloaded and their files deleted.</li>
 * </ol>
 *
 * <p>Chunk saves store block keys, so a variant whose asset goes missing loads
 * as an "unknown" placeholder that keeps its key; the save is not damaged and
 * the block resolves once the asset is back.</p>
 */
public final class GeneratedAssetService {

    /** The BlockType store's folder inside a pack, as the S2 spike's documents used it. */
    static final String BLOCK_STORE_PATH = "Server/Item/Block/Blocks";
    /** Matches the plugin manifest's ServerVersion. */
    static final String SERVER_VERSION = ">=0.7.0 <0.8.0";
    /**
     * Single-cell blocks only (the rules refuse the rest), so filler handling is
     * off; no particles for a swap that should be invisible.
     */
    static final int SWAP_SETTINGS = SetBlockSettings.NO_SEND_PARTICLES | SetBlockSettings.NO_SET_FILLER
            | SetBlockSettings.NO_BREAK_FILLER;

    private final MysticCore core;
    private final Path packDir;

    public GeneratedAssetService(MysticCore core) {
        this(core, PluginManager.MODS_PATH.resolve(VariantRules.PACK_FOLDER));
    }

    GeneratedAssetService(MysticCore core, Path packDir) {
        this.core = core;
        this.packDir = packDir;
    }

    public Path packDir() {
        return packDir;
    }

    // ----- Pack ---------------------------------------------------------------

    /** The generated pack when the engine has it, else {@code null}. */
    public AssetPack loadedPack() {
        Path manifest = packDir.resolve("manifest.json");
        if (!Files.isRegularFile(manifest)) {
            return null;
        }
        try {
            return AssetModule.get().getAssetPack(new PluginIdentifier(readManifest(manifest)).toString());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The generated pack, creating its folder and manifest and registering it
     * at runtime when the engine did not load it at boot.
     */
    public synchronized AssetPack ensurePack() throws IOException {
        Path manifestPath = packDir.resolve("manifest.json");
        if (!Files.isRegularFile(manifestPath)) {
            Files.createDirectories(packDir);
            Files.writeString(manifestPath, VariantRules.manifest(SERVER_VERSION), StandardCharsets.UTF_8);
            core.log(Level.INFO, "[lootbox] created the generated block pack at " + packDir);
        }
        AssetPack pack = loadedPack();
        if (pack == null) {
            PluginManifest manifest = readManifest(manifestPath);
            String packId = new PluginIdentifier(manifest).toString();
            if (!AssetModule.get().registerPack(packId, packDir, manifest, AssetPack.PackSource.MODS)) {
                throw new IOException("the engine refused to register " + packId);
            }
            pack = AssetModule.get().getAssetPack(packId);
            core.log(Level.INFO, "[lootbox] registered the generated block pack " + packId + " at runtime. If it "
                    + "isn't loaded at the next boot, check that DefaultModsEnabled (or the mod config) allows packs "
                    + "in mods/, or generated crates will show as unknown blocks until it is.");
        }
        if (pack == null) {
            throw new IOException("the generated pack is missing after registering it");
        }
        if (pack.isImmutable()) {
            throw new IOException("the generated pack " + pack.getName() + " is read-only");
        }
        return pack;
    }

    private static PluginManifest readManifest(Path manifestPath) throws IOException {
        try (Reader reader = Files.newBufferedReader(manifestPath, StandardCharsets.UTF_8)) {
            RawJsonReader json = new RawJsonReader(reader, RawJsonReader.READ_BUFFER.get());
            return PluginManifest.CODEC.decodeJson(json, ExtraInfo.THREAD_LOCAL.get());
        }
    }

    // ----- Variants -----------------------------------------------------------

    Path variantsDir() {
        return packDir.resolve(BLOCK_STORE_PATH).resolve(VariantRules.VARIANT_FOLDER);
    }

    Path variantFile(String variantKey) {
        return variantsDir().resolve(variantKey + ".json");
    }

    /** The loaded, usable block type for {@code key}, or {@code null}. */
    public static BlockType usable(String key) {
        if (!BlockTargets.usableBlockLoaded(key)) {
            return null;
        }
        return BlockType.getAssetMap().getAsset(BlockType.getAssetMap().getIndex(key));
    }

    /**
     * Makes the look-alike of {@code originalKey} live: writes its document when
     * it is missing or differs, and loads it the way boot does. Asset work only;
     * the crate editor and commands call it on the world thread before the swap.
     *
     * @return the variant's block type
     * @throws IOException when the file can't be written or the engine refuses the document
     */
    public BlockType ensureVariant(String originalKey) throws IOException {
        String key = VariantRules.variantKey(originalKey);
        BlockType loaded = usable(key);
        if (loaded != null) {
            return loaded;
        }
        AssetPack pack = ensurePack();
        Path file = variantFile(key);
        Files.createDirectories(file.getParent());
        String document = VariantRules.document(originalKey);
        if (!Files.isRegularFile(file) || !document.equals(Files.readString(file, StandardCharsets.UTF_8))) {
            Files.writeString(file, document, StandardCharsets.UTF_8);
        }
        AssetLoadResult<String, BlockType> result = BlockType.getAssetStore().loadAssetsFromPaths(pack.getName(),
                List.of(file));
        loaded = usable(key);
        if (loaded == null) {
            throw new IOException("the look-alike " + key + " did not load"
                    + (result.hasFailed() ? " (the engine refused it; see the server log)" : ""));
        }
        return loaded;
    }

    /** Variant keys with a document in the generated pack. */
    public List<String> variantsOnDisk() {
        List<String> keys = new ArrayList<>();
        Path dir = variantsDir();
        if (!Files.isDirectory(dir)) {
            return keys;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .map(name -> name.substring(0, name.length() - ".json".length()))
                    .forEach(keys::add);
        } catch (IOException e) {
            core.log(Level.WARNING, "[lootbox] could not list " + dir + ": " + e);
        }
        return keys;
    }

    /**
     * Unloads and deletes every variant no crate records. Only call it with the
     * full crate list (after the index loaded), or a variant still in use
     * would go.
     *
     * @return the keys removed
     */
    public List<String> cleanUp(Collection<CrateInstance> crates) {
        List<String> removed = new ArrayList<>();
        for (String key : VariantRules.unused(variantsOnDisk(), crates)) {
            Path file = variantFile(key);
            try {
                AssetPack pack = loadedPack();
                if (pack != null && BlockType.getAssetMap().getIndex(key) != AssetMapWithIndexes.NOT_FOUND) {
                    BlockType.getAssetStore().removeAssetWithPaths(pack.getName(), List.of(file));
                }
                Files.deleteIfExists(file);
                removed.add(key);
            } catch (IOException | RuntimeException e) {
                core.log(Level.WARNING, "[lootbox] could not clean up the unused look-alike " + key + ": " + e);
            }
        }
        if (!removed.isEmpty()) {
            core.log(Level.INFO, "[lootbox] removed " + removed.size() + " unused generated block(s): " + removed);
        }
        return removed;
    }

    /**
     * Startup check: every generated crate's look-alike must resolve and not be
     * unknown. One that doesn't is regenerated from its document.
     *
     * @return the crates whose look-alike still does not load
     */
    public List<CrateInstance> verify(Collection<CrateInstance> crates) {
        List<CrateInstance> broken = new ArrayList<>();
        for (CrateInstance crate : crates) {
            if (!CrateInstance.KIND_GENERATED.equals(crate.kind) || usable(crate.blockKey) != null) {
                continue;
            }
            String original = crate.originalBlockKey != null ? crate.originalBlockKey
                    : VariantRules.originalOf(crate.blockKey);
            try {
                if (original == null || !VariantRules.variantKey(original).equals(crate.blockKey)) {
                    throw new IOException("its recorded keys don't match");
                }
                ensureVariant(original);
            } catch (IOException | RuntimeException e) {
                core.log(Level.WARNING, "[lootbox] crate " + crate.crateId + ": its look-alike " + crate.blockKey
                        + " does not load: " + e.getMessage());
                broken.add(crate);
            }
        }
        return broken;
    }

    // ----- Swapping -----------------------------------------------------------

    /**
     * Writes {@code key} at a block, keeping {@code rotation} and
     * {@code filler}, and reads it back. MUST run on the world thread.
     *
     * @return {@code true} when the block now has that key and rotation
     */
    public static boolean swap(World world, int x, int y, int z, String key, int rotation, int filler) {
        int index = BlockType.getAssetMap().getIndex(key);
        if (index == AssetMapWithIndexes.NOT_FOUND) {
            return false;
        }
        BlockType type = BlockType.getAssetMap().getAsset(index);
        ChunkStore chunkStore = world.getChunkStore();
        Ref<ChunkStore> section = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
        if (type == null || section == null || !section.isValid()) {
            return false;
        }
        BlockOperations.setBlock(chunkStore, section, x, y, z, index, type, rotation, filler, SWAP_SETTINGS);
        BlockTargets.Block after = BlockTargets.at(world, x, y, z);
        return after != null && after.key().equals(key) && after.rotation() == rotation;
    }
}
