package com.alphine.mysticessentials.fabric;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.fabric.platform.FabricModInfoService;
import com.alphine.mysticessentials.inv.InvseeSessions;
import com.alphine.mysticessentials.storage.PlayerDataStore;
import com.alphine.mysticessentials.util.DurationUtil;
import com.alphine.mysticessentials.util.Teleports;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

public class MysticEssentialsFabric implements ModInitializer {

    private int afkTickAccum = 0;
    private final java.util.Map<java.util.UUID, net.minecraft.world.phys.Vec3> afkLastPos = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, float[]> afkLastRot = new java.util.HashMap<>();

    @Override
    public void onInitialize() {
        // mod info service
        MysticEssentialsCommon.get().setModInfoService(new FabricModInfoService());
        // lifecycle
        ServerLifecycleEvents.SERVER_STARTING.register(MysticEssentialsCommon.get()::serverStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> MysticEssentialsCommon.get().serverStopping());

        // commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) ->
                MysticEssentialsCommon.get().registerCommands(dispatcher)
        );

        // god mode: block damage & death
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer p && MysticEssentialsCommon.get().god.isGod(p.getUUID())) return false;
            return true;
        });
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, damageSource, damageAmount) -> {
            if (entity instanceof ServerPlayer p && MysticEssentialsCommon.get().god.isGod(p.getUUID())) return false;
            return true;
        });

        // last location on disconnect + stop any invsee tracking
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer p = handler.player;
            var pdata = MysticEssentialsCommon.get().pdata;
            MysticEssentialsCommon.get().onPlayerQuit(p);
            var l = new PlayerDataStore.LastLoc();
            l.dim = p.serverLevel().dimension().location().toString();
            l.x = p.getX(); l.y = p.getY(); l.z = p.getZ();
            l.yaw = p.getYRot(); l.pitch = p.getXRot();
            l.when = System.currentTimeMillis();
            pdata.setLast(p.getUUID(), l);
            InvseeSessions.close(p);
        });

        // last death
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer p) {
                var pdata = MysticEssentialsCommon.get().pdata;
                var l = new PlayerDataStore.LastLoc();
                l.dim = p.serverLevel().dimension().location().toString();
                l.x = p.getX(); l.y = p.getY(); l.z = p.getZ();
                l.yaw = p.getYRot(); l.pitch = p.getXRot();
                l.when = System.currentTimeMillis();
                pdata.setDeath(p.getUUID(), l);
            }
        });

        // once per second AFK tick
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++afkTickAccum >= 20) {
                afkTickAccum = 0;
                MysticEssentialsCommon.get().serverTick1s(server);
            }
        });

        // per-world tick: keep your freeze/invsee close AND add AFK movement detection
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            var punish = MysticEssentialsCommon.get().punish;

            for (ServerPlayer sp : world.players()) {
                // --- AFK movement/rotation activity ---
                var id = sp.getUUID();
                var curPos = sp.position();
                var lastPos = afkLastPos.get(id);
                float yRot = sp.getYRot(), xRot = sp.getXRot();
                var lastRot = afkLastRot.get(id);

                boolean moved = lastPos == null || curPos.distanceToSqr(lastPos) > 0.0004D;
                boolean rotated = lastRot == null || Math.abs(lastRot[0] - yRot) > 0.1f || Math.abs(lastRot[1] - xRot) > 0.1f;

                if (moved || rotated) {
                    MysticEssentialsCommon.get().onPlayerMove(sp);
                    afkLastPos.put(id, curPos);
                    afkLastRot.put(id, new float[]{yRot, xRot});
                }

                // --- existing freeze + invsee logic ---
                if (punish.isFrozen(sp.getUUID())) {
                    sp.setDeltaMovement(0, 0, 0);
                    sp.hurtMarked = true;
                }
                if (sp.containerMenu == sp.inventoryMenu) {
                    com.alphine.mysticessentials.inv.InvseeSessions.close(sp);
                }
            }
        });


        // close invsee when any screen handler closes
        ServerTickEvents.END_SERVER_TICK.register(
                com.alphine.mysticessentials.inv.InvseeSessions::tick
        );

        // enforce jails on world change
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
            var common = MysticEssentialsCommon.get();
            var ps = common.punish;
            var oj = ps.getJailed(player.getUUID());
            if (oj.isEmpty()) return;
            var name = oj.get();
            var opt = ps.getJail(name);
            if (opt.isEmpty()) return;

            var pt = opt.get();
            var id = ResourceLocation.tryParse(pt.dim);
            if (id == null) return;

            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
            ServerLevel lvl = player.getServer().getLevel(key);
            if (lvl != null) {
                Teleports.pushBackAndTeleport(player, lvl, pt.x, pt.y, pt.z, pt.yaw, pt.pitch, common.pdata);
            }
        });

        // chat mute
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            if (!(sender instanceof ServerPlayer p)) return true;

            var ps = MysticEssentialsCommon.get().punish;
            var om = ps.getMute(p.getUUID());
            if (om.isEmpty()) {
                // mark AFK activity only when message is allowed
                MysticEssentialsCommon.get().onPlayerChat(p);

                // PlayerChatMessage -> raw text
                String raw = (message.unsignedContent() != null)
                        ? message.unsignedContent().getString()
                        : message.signedContent();

                com.alphine.mysticessentials.util.AfkPingUtil.handleChatMention(p.getServer(), p, raw);
                return true;
            }

            var m = om.get();
            if (m.until == null || System.currentTimeMillis() < m.until) {
                long rem = m.until == null ? -1 : (m.until - System.currentTimeMillis());
                p.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "§cYou are muted" + (rem > 0 ? " §7(" + com.alphine.mysticessentials.util.DurationUtil.fmtRemaining(rem) + ")" : "§7 (permanent)") + "."),
                        false
                );
                return false;
            } else {
                ps.unmute(p.getUUID());
                MysticEssentialsCommon.get().onPlayerChat(p);
                String raw = (message.unsignedContent() != null)
                        ? message.unsignedContent().getString()
                        : message.signedContent();
                com.alphine.mysticessentials.util.AfkPingUtil.handleChatMention(p.getServer(), p, raw);
                return true;
            }
        });

        // kick on join if banned (uuid/ip)
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer p = handler.player;
            var ps = MysticEssentialsCommon.get().punish;
            MysticEssentialsCommon.get().onPlayerJoin(p);

            var ub = ps.getUuidBan(p.getUUID());
            if (ub.isPresent()) {
                var b = ub.get();
                if (b.until == null || System.currentTimeMillis() < b.until) {
                    p.connection.disconnect(net.minecraft.network.chat.Component.literal("§cBanned: §f" + b.reason));
                    return;
                } else ps.unbanUuid(p.getUUID());
            }

            String ip = p.getIpAddress();
            var ib = ps.getIpBan(ip);
            if (ib.isPresent()) {
                var b = ib.get();
                if (b.until == null || System.currentTimeMillis() < b.until) {
                    p.connection.disconnect(net.minecraft.network.chat.Component.literal("§cIP Banned: §f" + b.reason));
                } else ps.unbanIp(ip);
            }
        });
    }

    private static void freezeTick(MinecraftServer server) {
        var punish = MysticEssentialsCommon.get().punish;
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (punish.isFrozen(sp.getUUID())) {
                sp.setDeltaMovement(0, 0, 0);
                // no direct 'hurtMarked' on Fabric without an access widener; zeroing velocity each tick is enough
            }
        }
    }
}
