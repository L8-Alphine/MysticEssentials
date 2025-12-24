package com.alphine.mysticessentials.neoforge.npc;

import com.alphine.mysticessentials.npc.NpcPlatformAdapter;
import com.alphine.mysticessentials.npc.model.NpcDefinition;
import com.alphine.mysticessentials.npc.skin.NpcSkinService;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import com.mojang.authlib.GameProfile;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.util.UUID;

/**
 * Reference PLAYER-NPC implementation for NeoForge.
 *
 * This uses FakePlayerFactory to create a ServerPlayer-like entity.
 * Note: FakePlayer *by itself* is not automatically visible to clients;
 * depending on NeoForge version, you may still need extra packet logic.
 */
public final class NeoForgeNpcPlatform implements NpcPlatformAdapter {

    @Override
    public UUID spawnPlayerNpc(MinecraftServer server,
                               NpcDefinition def,
                               UUID previousId,
                               NpcSkinService.ResolvedSkin skin) {

        // 1) Despawn previous entity if any
        if (previousId != null) {
            for (ServerLevel lvl : server.getAllLevels()) {
                Entity e = lvl.getEntity(previousId);
                if (e != null) {
                    e.discard();
                }
            }
        }

        // 2) Resolve level
        ServerLevel level = resolveLevel(server, def.anchor.dimension);
        if (level == null) {
            System.err.println("[MysticEssentials] Failed to resolve NPC dimension: " + def.anchor.dimension);
            return null;
        }

        // 3) GameProfile
        String name = (def.type.playerProfile.name != null && !def.type.playerProfile.name.isBlank())
                ? def.type.playerProfile.name
                : def.name;

        GameProfile profile = new GameProfile(UUID.randomUUID(), name);

        // apply skin to profile before FakePlayer creation
        if (skin != null) NpcSkinService.applyToProfile(profile, skin);

        // 4) Create FakePlayer
        ServerPlayer npc = FakePlayerFactory.get(level, profile);

        // 5) Position & flags
        npc.setPos(def.anchor.x, def.anchor.y, def.anchor.z);
        npc.setYRot(def.anchor.yaw);
        npc.setXRot(def.anchor.pitch);

        npc.setInvulnerable(true);
        npc.setSilent(true);
        npc.setNoGravity(true);
        npc.addTag("mysticessentials:npc");
        npc.addTag("mysticessentials:npc_player");

        // FakePlayerFactory.get(level, profile) already adds the fake player to the world
        // so we don't need to call addFreshEntity here.
        return npc.getUUID();
    }

    private static ServerLevel resolveLevel(MinecraftServer server, String dimId) {
        ResourceLocation id = ResourceLocation.tryParse(dimId);
        if (id == null) return null;
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
        return server.getLevel(key);
    }
}
