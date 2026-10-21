package org.hyzionstudios.mysticessentials.modules.lootbox.hytale;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.hyzionstudios.mysticessentials.modules.lootbox.service.CrateRules;

import org.joml.Vector3i;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.StateData;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import com.hypixel.hytale.server.core.util.TargetUtil;

/**
 * Block reads for crates. Selection raycasts the look direction
 * ({@code TargetUtil.getTargetBlock}), which works on every block, rather than
 * relying on hit events: Phase 0 (S6) showed {@code DamageBlockEvent} is skipped
 * for many blocks and held items. MUST run on the world thread.
 */
public final class BlockTargets {

    /** A block in a loaded chunk, with its rotation and filler as the chunk stores them. */
    public record Block(World world, String worldId, int x, int y, int z, BlockType type, int rotation, int filler) {

        public String key() {
            return type.getId();
        }

        public boolean hasUse() {
            return useRoot(type) != null;
        }

        public boolean isEmpty() {
            return type == BlockType.EMPTY || "Empty".equalsIgnoreCase(type.getId());
        }

        /** What {@link CrateRules} needs to judge this block. */
        public CrateRules.BlockFacts facts() {
            return BlockTargets.facts(type, rotation, filler, isEmpty());
        }
    }

    private BlockTargets() {
    }

    /**
     * Whether the block asset {@code key} is loaded, is not an unknown
     * placeholder, and has a {@code Use} root that resolves. Reads asset maps
     * only, so it is safe off the world thread.
     */
    public static boolean usableBlockLoaded(String key) {
        int index = BlockType.getAssetMap().getIndex(key);
        if (index == AssetMapWithIndexes.NOT_FOUND) {
            return false;
        }
        BlockType type = BlockType.getAssetMap().getAsset(index);
        if (type == null || type.isUnknown()) {
            return false;
        }
        String root = useRoot(type);
        return root != null && RootInteraction.getAssetMap().getIndex(root) != AssetMapWithIndexes.NOT_FOUND;
    }

    /** @return the block the player looks at within {@code reach}, or {@code null}. */
    public static Block target(Ref<EntityStore> entity, Store<EntityStore> store, double reach) {
        Vector3i position = TargetUtil.getTargetBlock(entity, reach, store);
        if (position == null) {
            return null;
        }
        return at(store.getExternalData().getWorld(), position.x, position.y, position.z);
    }

    /** @return the block at a position, or {@code null} when its chunk is not loaded. */
    public static Block at(World world, int x, int y, int z) {
        ChunkStore chunkStore = world.getChunkStore();
        Ref<ChunkStore> section = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
        if (section == null || !section.isValid()) {
            return null;
        }
        BlockSection blocks = chunkStore.getStore().getComponent(section, BlockSection.getComponentType());
        if (blocks == null) {
            return null;
        }
        BlockType type = BlockType.getAssetMap().getAsset(blocks.get(x, y, z));
        return type == null ? null : new Block(world, worldId(world), x, y, z, type, blocks.getRotationIndex(x, y, z),
                blocks.getFiller(x, y, z));
    }

    /** The Use root interaction id of {@code type}, or {@code null}. */
    public static String useRoot(BlockType type) {
        Map<InteractionType, String> interactions = type == null ? null : type.getInteractions();
        String root = interactions == null ? null : interactions.get(InteractionType.Use);
        return root == null || root.isBlank() ? null : root;
    }

    /** The facts {@link CrateRules#check} judges, read from the block's asset. */
    @SuppressWarnings("deprecation") // IsDoor is the only door marker the asset has in 0.7.2.
    public static CrateRules.BlockFacts facts(BlockType type, int rotation, int filler, boolean empty) {
        StateData state = type.getState();
        Set<String> states = state == null ? null : state.getStateNames();
        int id = BlockType.getAssetMap().getIndex(type.getId());
        boolean multiCell = id != AssetMapWithIndexes.NOT_FOUND && FillerBlockUtil.multiCellFootprint(id, rotation) != null;
        return new CrateRules.BlockFacts(type.getId(), empty, type.isUnknown(), useRoot(type),
                type.getBlockEntity() != null, type.isDoor(), type.getBench() != null,
                type.getSeats() != null || type.getBeds() != null, type.getRailConfig(rotation) != null,
                type.isTrigger(), type.isState() || (states != null && !states.isEmpty()),
                type.getFallingBlockSettings() != null, multiCell, filler != 0,
                type.getConnectedBlockRuleSet() != null);
    }

    /** The world's UUID as text, falling back to its name (the portals convention). */
    public static String worldId(World world) {
        try {
            if (world != null && world.getWorldConfig() != null) {
                UUID uuid = world.getWorldConfig().getUuid();
                if (uuid != null) {
                    return uuid.toString();
                }
            }
        } catch (Throwable ignored) {
            // Falls back to the name.
        }
        return world == null ? "" : world.getName();
    }
}
