package org.hyzionstudios.mysticessentials.modules.lootbox.hytale;

import java.util.Map;
import java.util.UUID;

import org.joml.Vector3i;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;

/**
 * Block reads for crates. Selection raycasts the look direction
 * ({@code TargetUtil.getTargetBlock}), which works on every block, rather than
 * relying on hit events: Phase 0 (S6) showed {@code DamageBlockEvent} is skipped
 * for many blocks and held items. MUST run on the world thread.
 */
public final class BlockTargets {

    /** A block in a loaded chunk. */
    public record Block(World world, String worldId, int x, int y, int z, BlockType type) {

        public String key() {
            return type.getId();
        }

        public boolean hasUse() {
            Map<InteractionType, String> interactions = type.getInteractions();
            return interactions != null && interactions.get(InteractionType.Use) != null;
        }

        public boolean isEmpty() {
            return type == BlockType.EMPTY || "Empty".equalsIgnoreCase(type.getId());
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
        Map<InteractionType, String> interactions = type.getInteractions();
        String root = interactions == null ? null : interactions.get(InteractionType.Use);
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
        return type == null ? null : new Block(world, worldId(world), x, y, z, type);
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
