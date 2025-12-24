package com.alphine.mysticessentials.npc;

import com.alphine.mysticessentials.npc.model.NpcDefinition;
import com.alphine.mysticessentials.npc.skin.NpcSkinService;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

public interface NpcPlatformAdapter {

    /**
     * Spawn or update a PLAYER-type NPC.
     *
     * @param server     server instance
     * @param def        NPC definition
     * @param existingId previously spawned entity UUID (may be null)
     * @param skin       resolved skin (may be null if resolution failed or is disabled)
     * @return UUID of the backing entity (or null if spawn failed)
     */
    UUID spawnPlayerNpc(MinecraftServer server,
                        NpcDefinition def,
                        UUID existingId,
                        NpcSkinService.ResolvedSkin skin);
}
