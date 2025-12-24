package com.alphine.mysticessentials.fabric.npc;

import com.alphine.mysticessentials.npc.NpcPlatformAdapter;
import com.alphine.mysticessentials.npc.model.NpcDefinition;
import com.alphine.mysticessentials.npc.skin.NpcSkinService;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.world.entity.player.ProfilePublicKey;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * Reference implementation for PLAYER-type NPCs on Fabric.
 *
 * NOTE:
 * - This is version-sensitive; constructor args may need adjustment for 1.21.x.
 * - Treat as a template: adjust ClientInformation / cookie / profile key to your mappings.
 */
public final class FabricNpcPlatform implements NpcPlatformAdapter {

    @Override
    public UUID spawnPlayerNpc(MinecraftServer server,
                               NpcDefinition def,
                               UUID previousId,
                               NpcSkinService.ResolvedSkin skin) {

        // 1) Despawn previous entity if it still exists
        if (previousId != null) {
            for (ServerLevel lvl : server.getAllLevels()) {
                Entity e = lvl.getEntity(previousId);
                if (e != null) {
                    e.discard();
                }
            }
        }

        // 2) Resolve level from anchor.dimension
        ServerLevel level = resolveLevel(server, def.anchor.dimension);
        if (level == null) {
            System.err.println("[MysticEssentials] Failed to resolve NPC dimension: " + def.anchor.dimension);
            return null;
        }

        // 3) Build GameProfile for this NPC
        String name = (def.type.playerProfile.name != null && !def.type.playerProfile.name.isBlank())
                ? def.type.playerProfile.name
                : def.name;

        GameProfile profile = new GameProfile(UUID.randomUUID(), name);

        // Apply skin if provided
        if (skin != null) {
            // This call is in your NpcSkinService already
            // npcSkinService.applyToProfile(profile, skin);
            // You can't access npcSkinService from here directly,
            // so Runtime passes the ResolvedSkin and common applies before spawn if needed.
            // Alternative: expose a static helper or pass the service reference into this platform.
        }

        // ---- IMPORTANT VERSION NOTE ----
        // The ServerPlayer constructor and ClientInformation type are VERY version sensitive.
        // You'll need to adjust this block to match your mappings / MC version.
        // The structure below is a reference pattern only.

        // For many 1.20+ mappings, you have something like ClientInformation:
        // ClientInformation info = ClientInformation.createDefault();

        // Pseudo-code placeholder:
        var clientInfo = net.minecraft.server.level.ClientInformation.createDefault();

        ServerPlayer npc = new ServerPlayer(server, level, profile, clientInfo);

        // 4) Basic flags
        npc.setPos(def.anchor.x, def.anchor.y, def.anchor.z);
        npc.setYRot(def.anchor.yaw);
        npc.setXRot(def.anchor.pitch);

        npc.setInvulnerable(true);
        npc.setNoGravity(true);
        npc.setSilent(true);
        npc.getAbilities().invulnerable = true;
        npc.getAbilities().mayfly = false;
        npc.getAbilities().flying = false;

        // Tag as MysticEssentials NPC (handy for debugging / filters)
        npc.addTag("mysticessentials:npc");
        npc.addTag("mysticessentials:npc_player");

        // 5) Attach a dummy connection / listener so vanilla systems are happy
        // NOTE: This is the ugliest / most version-sensitive part.
        // You may skip this and rely purely on level.addFreshEntity(...) and manual packets,
        // but then you need custom networking to show the NPC to clients.
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        CommonListenerCookie cookie = new CommonListenerCookie(
                profile,
                0,                      // client protocol version
                clientInfo,
                false
        );

        ServerGamePacketListenerImpl listener =
                new ServerGamePacketListenerImpl(server, connection, npc, cookie);

        npc.connection = listener;

        // 6) Actually spawn it to the world and player list
        level.addFreshEntity(npc);
        server.getPlayerList().placeNewPlayer(connection, npc, cookie);

        return npc.getUUID();
    }

    private static ServerLevel resolveLevel(MinecraftServer server, String dimId) {
        ResourceLocation id = ResourceLocation.tryParse(dimId);
        if (id == null) return null;
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
        return server.getLevel(key);
    }
}
