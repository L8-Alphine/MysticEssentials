package com.alphine.mysticessentials.neoforge;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.neoforge.platform.NeoForgeModInfoService;
import com.alphine.mysticessentials.storage.PlayerDataStore;
import com.alphine.mysticessentials.inv.InvseeSessions;
import com.alphine.mysticessentials.util.Teleports;

import net.minecraft.network.chat.Component;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.ServerChatEvent;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

@Mod(MysticEssentialsCommon.MOD_ID)
public class MysticEssentialsNeoForge {

    // --- AFK movement tracking ---
    private int afkTickAccum = 0;
    private final java.util.Map<java.util.UUID, net.minecraft.world.phys.Vec3> afkLastPos = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, float[]> afkLastRot = new java.util.HashMap<>();


    public MysticEssentialsNeoForge() {
        // Mod Info Service
        MysticEssentialsCommon.get().setModInfoService(new NeoForgeModInfoService());
        // GAME bus listeners
        NeoForge.EVENT_BUS.addListener(this::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);

        NeoForge.EVENT_BUS.addListener(this::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(this::onPlayerDeath);
        NeoForge.EVENT_BUS.addListener(this::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(this::onPlayerContainerClose);
        NeoForge.EVENT_BUS.addListener(this::onChat);
        NeoForge.EVENT_BUS.addListener(this::onPlayerTickPost);
        NeoForge.EVENT_BUS.addListener(this::onPlayerChangedDimension);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(this::onDamagePost);
    }

    private void onServerAboutToStart(ServerAboutToStartEvent e) {
        MysticEssentialsCommon.get().serverStarting(e.getServer());
    }

    private void onRegisterCommands(RegisterCommandsEvent e) {
        MysticEssentialsCommon.get().registerCommands(e.getDispatcher());
    }

    private void onServerStopping(ServerStoppingEvent e) {
        MysticEssentialsCommon.get().serverStopping();
    }

    // GOD mode: cancel raw incoming damage instead of toggling invulnerable flag
    private void onIncomingDamage(LivingIncomingDamageEvent evt) {
        if (!(evt.getEntity() instanceof ServerPlayer p)) return;
        if (MysticEssentialsCommon.get().god.isGod(p.getUUID())) {
            evt.setCanceled(true);
        }
    }

    private void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent e){
        if(!(e.getEntity() instanceof ServerPlayer p)) return;
        var pdata = MysticEssentialsCommon.get().pdata;
        // AFK essentials
        MysticEssentialsCommon.get().onPlayerQuit(p);
        var l = new PlayerDataStore.LastLoc();
        l.dim = p.serverLevel().dimension().location().toString();
        l.x=p.getX(); l.y=p.getY(); l.z=p.getZ(); l.yaw=p.getYRot(); l.pitch=p.getXRot(); l.when=System.currentTimeMillis();
        pdata.setLast(p.getUUID(), l);

        // also stop tracking any invsee session
        InvseeSessions.close(p);
    }

    private void onPlayerDeath(LivingDeathEvent e){
        if(!(e.getEntity() instanceof ServerPlayer p)) return;
        var pdata = MysticEssentialsCommon.get().pdata;
        var l = new PlayerDataStore.LastLoc();
        l.dim = p.serverLevel().dimension().location().toString();
        l.x=p.getX(); l.y=p.getY(); l.z=p.getZ(); l.yaw=p.getYRot(); l.pitch=p.getXRot(); l.when=System.currentTimeMillis();
        pdata.setDeath(p.getUUID(), l);
    }

    private void onServerTickPost(ServerTickEvent.Post e) {
        InvseeSessions.tick(e.getServer());

        // once per second for AFK service
        if (++afkTickAccum >= 20) {
            afkTickAccum = 0;
            MysticEssentialsCommon.get().serverTick1s(e.getServer());
        }
    }

    private void onPlayerContainerClose(PlayerContainerEvent.Close e) {
        if (e.getEntity() instanceof ServerPlayer sp) {
            MysticEssentialsCommon.get().onPlayerInteract(sp); // mark AFK activity
            InvseeSessions.close(sp);
        }
    }

    // Block chat if muted
    private void onChat(ServerChatEvent e){
        ServerPlayer p = e.getPlayer();

        // mark AFK activity on attempt
        MysticEssentialsCommon.get().onPlayerChat(p);

        // --- your mute gate first ---
        var ps = MysticEssentialsCommon.get().punish;
        var om = ps.getMute(p.getUUID());
        if (om.isPresent()){
            var m = om.get();
            if (m.until == null || System.currentTimeMillis() < m.until){
                e.setCanceled(true);
                long rem = m.until==null? -1 : (m.until - System.currentTimeMillis());
                p.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "§cYou are muted" + (rem>0 ? " §7(" +
                                        com.alphine.mysticessentials.util.DurationUtil.fmtRemaining(rem) + ")" : "§7 (permanent)") + "."),
                        false
                );
                return; // don't AFK-DM if blocked
            } else {
                ps.unmute(p.getUUID());
            }
        }

        // Afk ping processing
        Component comp = e.getMessage();
        String raw = comp.getString();

        // DM the pinger about AFK targets mentioned
        com.alphine.mysticessentials.util.AfkPingUtil.handleChatMention(p.getServer(), p, raw);
    }

    private void onPlayerTickPost(PlayerTickEvent.Post e){
        if (!(e.getEntity() instanceof ServerPlayer p)) return;

        // --- AFK: movement/rotation activity ---
        var id = p.getUUID();
        var curPos = p.position();
        var lastPos = afkLastPos.get(id);
        float yRot = p.getYRot(), xRot = p.getXRot();
        var lastRot = afkLastRot.get(id);

        boolean moved = lastPos == null || curPos.distanceToSqr(lastPos) > 0.0004D; // ~2cm
        boolean rotated = lastRot == null || Math.abs(lastRot[0] - yRot) > 0.1f || Math.abs(lastRot[1] - xRot) > 0.1f;

        if (moved || rotated) {
            MysticEssentialsCommon.get().onPlayerMove(p);
            afkLastPos.put(id, curPos);
            afkLastRot.put(id, new float[]{yRot, xRot});
        }

        // Freeze: cancel movement
        var ps = MysticEssentialsCommon.get().punish;
        if (ps.isFrozen(p.getUUID())){
            p.setDeltaMovement(0,0,0);
            p.hurtMarked = true;
        }
    }

    // Enforce jails on dimension change
    private void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent e){
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        var common = MysticEssentialsCommon.get();
        var ps = common.punish;
        var oj = ps.getJailed(p.getUUID());
        if (oj.isPresent()){
            var name = oj.get();
            var opt = ps.getJail(name);
            if (opt.isPresent()){
                var pt = opt.get();

                var id = ResourceLocation.tryParse(pt.dim);
                if (id != null) {
                    ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
                    ServerLevel lvl = p.getServer().getLevel(key);
                    if (lvl != null) {
                        Teleports.pushBackAndTeleport(p, lvl, pt.x, pt.y, pt.z, pt.yaw, pt.pitch, common.pdata);
                    }
                }
            }
        }
    }

    // Kick on login if banned
    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent e){
        var p = (ServerPlayer)e.getEntity();
        var ps = MysticEssentialsCommon.get().punish;

        // AFK essentials
        MysticEssentialsCommon.get().onPlayerJoin(p);

        // UUID ban
        var ub = ps.getUuidBan(p.getUUID());
        if (ub.isPresent()){
            var b = ub.get();
            if (b.until==null || System.currentTimeMillis()<b.until){
                p.connection.disconnect(net.minecraft.network.chat.Component.literal("§cBanned: §f"+b.reason));
                return;
            } else ps.unbanUuid(p.getUUID());
        }
        // IP ban
        String ip = p.getIpAddress();
        var ib = ps.getIpBan(ip);
        if (ib.isPresent()){
            var b = ib.get();
            if (b.until==null || System.currentTimeMillis()<b.until){
                p.connection.disconnect(net.minecraft.network.chat.Component.literal("§cIP Banned: §f"+b.reason));
            } else ps.unbanIp(ip);
        }
    }

    // Warmups: only cancel after real damage was applied
    private void onDamagePost(LivingDamageEvent.Post e){
        if (e.getEntity() instanceof ServerPlayer sp) {
            // If you only want to cancel on positive damage, you could check e.getAmount() > 0
            MysticEssentialsCommon.get().warmups.onDamaged(sp);
        }
    }
}
