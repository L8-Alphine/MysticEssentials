package com.alphine.mysticessentials.fabric;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.fabric.platform.FabricModInfoService;
import com.alphine.mysticessentials.inv.InvseeSessions;
import com.alphine.mysticessentials.storage.PlayerDataStore;
import com.alphine.mysticessentials.util.Teleports;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MysticEssentialsFabric implements ModInitializer {

    private int afkTickAccum = 0;
    private final Map<UUID, net.minecraft.world.phys.Vec3> afkLastPos = new HashMap<>();
    private final Map<UUID, float[]> afkLastRot = new HashMap<>();

    @Override
    public void onInitialize() {
        // Mod info service
        MysticEssentialsCommon.get().setModInfoService(new FabricModInfoService());

        // Lifecycle
        ServerLifecycleEvents.SERVER_STARTING.register(MysticEssentialsCommon.get()::serverStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> MysticEssentialsCommon.get().serverStopping());

        // Commands
        CommandRegistrationCallback.EVENT.register((dispatcher, regAccess, env) -> {
            var common = MysticEssentialsCommon.get();
            Path cfgDir = FabricLoader.getInstance().getConfigDir()
                    .resolve(MysticEssentialsCommon.MOD_ID).normalize();
            common.ensureCoreServices(cfgDir);
            common.registerCommands(dispatcher);
        });

        // God mode: block damage & death
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof ServerPlayer p) || !MysticEssentialsCommon.get().god.isGod(p.getUUID())
        );
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, damageSource, damageAmount) ->
                !(entity instanceof ServerPlayer p) || !MysticEssentialsCommon.get().god.isGod(p.getUUID())
        );

        // Last location on disconnect + stop any invsee tracking + cancel warmup
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer p = handler.player;
            var common = MysticEssentialsCommon.get();
            common.onPlayerQuit(p);

            var l = new PlayerDataStore.LastLoc();
            l.dim = p.serverLevel().dimension().location().toString();
            l.x = p.getX(); l.y = p.getY(); l.z = p.getZ();
            l.yaw = p.getYRot(); l.pitch = p.getXRot();
            l.when = System.currentTimeMillis();
            common.pdata.setLast(p.getUUID(), l);

            InvseeSessions.close(p);
            common.warmups.cancel(p, net.minecraft.network.chat.Component.empty());
        });

        // Last death
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer p) {
                var l = new PlayerDataStore.LastLoc();
                l.dim = p.serverLevel().dimension().location().toString();
                l.x = p.getX(); l.y = p.getY(); l.z = p.getZ();
                l.yaw = p.getYRot(); l.pitch = p.getXRot();
                l.when = System.currentTimeMillis();
                MysticEssentialsCommon.get().pdata.setDeath(p.getUUID(), l);
            }
        });

        // Once-per-second AFK tick
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // warmups: tick every server tick
            MysticEssentialsCommon.get().warmups.tick(server);

            // invsee sessions: tick to push container updates
            InvseeSessions.tick(server);

            if (++afkTickAccum >= 20) {
                afkTickAccum = 0;
                MysticEssentialsCommon.get().serverTick1s(server);
            }
        });

        // Per-world tick: AFK movement + freeze + invsee guard (non-invasive)
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            var common = MysticEssentialsCommon.get();
            var punish = common.punish;

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
                    common.onPlayerMove(sp);
                    afkLastPos.put(id, curPos);
                    afkLastRot.put(id, new float[]{yRot, xRot});
                }

                // --- Freeze: cancel movement (Fabric: no hurtMarked touch) ---
                if (punish.isFrozen(sp.getUUID())) {
                    sp.setDeltaMovement(0, 0, 0);
                }

                // If they closed back to personal inventory, ensure invsee is cleaned up
                if (sp.containerMenu == sp.inventoryMenu) {
                    InvseeSessions.close(sp);
                }
            }
        });

        // Enforce jails + cancel warmup + close invsee on world change
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
            var common = MysticEssentialsCommon.get();

            // close any invsee session to avoid UI race/flicker on dimension swap
            InvseeSessions.close(player);

            // treat as movement for warmup cancellation
            common.warmups.onWorldChange(player);

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

        // Chat mute + AFK ping
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            ServerPlayer p = sender;
            var ps = MysticEssentialsCommon.get().punish;

            var om = ps.getMute(p.getUUID());
            if (om.isEmpty()) {
                // mark AFK activity only when message is allowed
                MysticEssentialsCommon.get().onPlayerChat(p);

                String raw = (message.unsignedContent() != null)
                        ? message.unsignedContent().getString()
                        : message.signedContent();
                if (!raw.isBlank() && !raw.startsWith("/")) {
                    MysticEssentialsCommon.get().onPlayerChat(p);
                    com.alphine.mysticessentials.util.AfkPingUtil.handleChatMention(p.getServer(), p, raw);
                }
            }

            var m = om.get();
            if (m.until == null || System.currentTimeMillis() < m.until) {
                long rem = m.until == null ? -1 : (m.until - System.currentTimeMillis());
                p.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "§cYou are muted" + (rem > 0 ? " §7(" +
                                        com.alphine.mysticessentials.util.DurationUtil.fmtRemaining(rem) + ")" : " §7(permanent)") + "."),
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

        // Kick on join if banned (uuid/ip)
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
}
