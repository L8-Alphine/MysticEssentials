package com.alphine.mysticessentials.neoforge;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.chat.ChatModule;
import com.alphine.mysticessentials.chat.placeholder.LuckPermsPlaceholders;
import com.alphine.mysticessentials.chat.platform.CommonPlayer;
import com.alphine.mysticessentials.chat.platform.CommonServer;
import com.alphine.mysticessentials.chat.redis.RedisClientAdapter;
import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.inv.InvseeSessions;
import com.alphine.mysticessentials.neoforge.placeholder.NeoForgePlaceholders;
import com.alphine.mysticessentials.neoforge.platform.NeoForgeModInfoService;
import com.alphine.mysticessentials.neoforge.redis.LettuceRedisClientAdapter;
import com.alphine.mysticessentials.storage.PlayerDataStore;
import com.alphine.mysticessentials.update.ModrinthUpdateChecker;
import com.alphine.mysticessentials.util.Teleports;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Mod(MysticEssentialsCommon.MOD_ID)
public class MysticEssentialsNeoForge {

    private final Map<UUID, net.minecraft.world.phys.Vec3> afkLastPos = new HashMap<>();
    private final Map<UUID, float[]> afkLastRot = new HashMap<>();
    // --- AFK movement tracking ---
    private int afkTickAccum = 0;
    private RedisClientAdapter redisAdapter;

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
        NeoForge.EVENT_BUS.addListener(this::onPlayerRespawn);
        NeoForge.EVENT_BUS.addListener(this::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(this::onPlayerContainerClose);
        NeoForge.EVENT_BUS.addListener(this::onChat);
        NeoForge.EVENT_BUS.addListener(this::onPlayerTickPost);
        NeoForge.EVENT_BUS.addListener(this::onPlayerChangedDimension);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(this::onDamagePost);
    }

    private static CommonServer wrapServer(MinecraftServer server) {
        return new CommonServer() {
            @Override
            public String getServerName() {
                return server.getServerModName();
            }

            @Override
            public Iterable<? extends CommonPlayer> getOnlinePlayers() {
                java.util.List<CommonPlayer> list = new java.util.ArrayList<>();
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    list.add(wrapPlayer(p));
                }
                return list;
            }

            @Override
            public void runCommandAsConsole(String cmd) {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
            }

            @Override
            public void runCommandAsPlayer(CommonPlayer player, String cmd) {
                if (player instanceof WrappedCommonPlayer(ServerPlayer handle)) {
                    server.getCommands().performPrefixedCommand(handle.createCommandSourceStack(), cmd);
                }
            }

            @Override
            public void logToConsole(String plainText) {
                server.sendSystemMessage(Component.literal(plainText));
            }
        };
    }

    private static CommonPlayer wrapPlayer(ServerPlayer p) {
        return new WrappedCommonPlayer(p);
    }

    private void onServerAboutToStart(ServerAboutToStartEvent e) {
        MysticEssentialsCommon.get().serverStarting(e.getServer());

        var cfg = MEConfig.INSTANCE;
        RedisClientAdapter adapter = null;

        if (cfg != null && cfg.chat != null && cfg.chat.redis != null && cfg.chat.redis.enabled) {
            // create Lettuce adapter
            this.redisAdapter = new LettuceRedisClientAdapter(cfg.chat.redis);
            adapter = this.redisAdapter;
        }

        // initialize the common chat module (will create ChatPipeline)
        ChatModule.init(adapter);

        // Initialize auto-broadcast scheduler (MiniMessage -> players via CommonServer)
        CommonServer cs = wrapServer(e.getServer());
        MysticEssentialsCommon.get().initBroadcasts(mini -> {
            for (CommonPlayer cp : cs.getOnlinePlayers()) {
                cp.sendChatMessage(mini);
            }
        });

        // --- Modrinth update check (NeoForge) ---
        String currentVersion = MysticEssentialsCommon.getVersion();
        ModrinthUpdateChecker.checkForUpdatesAsync(
                "JEA9OHq8",
                currentVersion,
                msg -> e.getServer().sendSystemMessage(Component.literal(msg))
        );
    }

    public void onRegisterCommands(RegisterCommandsEvent e) {
        var common = MysticEssentialsCommon.get();
        Path cfgDir = FMLPaths.CONFIGDIR.get()
                .resolve(MysticEssentialsCommon.MOD_ID).normalize();
        common.ensureCoreServices(cfgDir);
        common.registerCommands(e.getDispatcher());
    }

    private void onServerStopping(ServerStoppingEvent e) {
        MysticEssentialsCommon.get().serverStopping();
        if (redisAdapter != null) {
            redisAdapter.shutdown();
            redisAdapter = null;
        }
    }

    // GOD mode: cancel raw incoming damage instead of toggling invulnerable flag
    private void onIncomingDamage(LivingIncomingDamageEvent evt) {
        if (!(evt.getEntity() instanceof ServerPlayer p)) return;
        if (MysticEssentialsCommon.get().god.isGod(p.getUUID())) {
            evt.setCanceled(true);
        }
    }

    // On logout: playtime tracking, last loc, cancel warmups, stop invsee
    private void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        var common = MysticEssentialsCommon.get();
        // System quit message
        CommonServer cs = wrapServer(p.getServer());
        CommonPlayer cp = wrapPlayer(p);
        common.systemMessages.broadcastQuit(cs, cp);

        common.onPlayerQuit(p);

        // Playtime tracking
        common.pdata.markLogout(p.getUUID());

        var l = new PlayerDataStore.LastLoc();
        l.dim = p.serverLevel().dimension().location().toString();
        l.x = p.getX();
        l.y = p.getY();
        l.z = p.getZ();
        l.yaw = p.getYRot();
        l.pitch = p.getXRot();
        l.when = System.currentTimeMillis();
        common.pdata.setLast(p.getUUID(), l);

        // stop tracking any invsee session
        InvseeSessions.close(p);

        // be safe: cancel warmup for leaver
        common.warmups.cancel(p, Component.empty());

        common.privateMessages.onQuit(p);
    }

    private void onPlayerDeath(LivingDeathEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        var l = new PlayerDataStore.LastLoc();
        l.dim = p.serverLevel().dimension().location().toString();
        l.x = p.getX();
        l.y = p.getY();
        l.z = p.getZ();
        l.yaw = p.getYRot();
        l.pitch = p.getXRot();
        l.when = System.currentTimeMillis();
        MysticEssentialsCommon.get().pdata.setDeath(p.getUUID(), l);

        // System death message using combat tracker message
        String deathMsg = p.getCombatTracker().getDeathMessage().getString();
        CommonServer cs = wrapServer(p.getServer());
        CommonPlayer cp = wrapPlayer(p);
        MysticEssentialsCommon.get().systemMessages.broadcastDeath(cs, cp, deathMsg);
    }

    private void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;

        // Only override when the player has no bed / anchor
        if (p.getRespawnPosition() == null && !p.isRespawnForced()) {
            MysticEssentialsCommon.get().teleportToSpawnIfSet(p);
        }
    }

    private void onServerTickPost(ServerTickEvent.Post e) {
        // warmups: tick every server tick (END/POST)
        MysticEssentialsCommon.get().warmups.tick(e.getServer());

        // invsee: push container updates for open viewers
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
    private void onChat(ServerChatEvent e) {
        ServerPlayer p = e.getPlayer();
        var common = MysticEssentialsCommon.get();
        var ps = common.punish;

        // --- MUTE GATE ---
        var om = ps.getMute(p.getUUID());
        if (om.isPresent()) {
            var m = om.get();
            if (m.until == null || System.currentTimeMillis() < m.until) {
                e.setCanceled(true);
                long rem = m.until == null ? -1 : (m.until - System.currentTimeMillis());
                p.displayClientMessage(
                        Component.literal("§cYou are muted" +
                                (rem > 0 ? " §7(" + com.alphine.mysticessentials.util.DurationUtil.fmtRemaining(rem) + ")" : " §7(permanent)") + "."),
                        false
                );
                return; // do NOT mark AFK chat on blocked messages
            } else {
                ps.unmute(p.getUUID());
            }
        }

        // --- AFK tracking + AFK ping ---
        String raw = e.getMessage().getString();
        common.onPlayerChat(p);
        com.alphine.mysticessentials.util.AfkPingUtil.handleChatMention(p.getServer(), p, raw);

        // --- Custom chat pipeline ---
        boolean handled = com.alphine.mysticessentials.neoforge.chat.NeoForgeChatBridge
                .handleChat(e);

        if (handled) {
            e.setCanceled(true); // cancel vanilla chat handling
        }
    }

    private void onPlayerTickPost(PlayerTickEvent.Post e) {
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

        // Freeze: cancel movement and mark for motion sync
        var ps = MysticEssentialsCommon.get().punish;
        if (ps.isFrozen(p.getUUID())) {
            p.setDeltaMovement(0, 0, 0);
            p.hurtMarked = true;
        }
    }

    // Cancel warmups + enforce jails on dimension change; also close invsee
    private void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;

        // close any invsee session to avoid UI race/flicker
        InvseeSessions.close(p);

        // cancel warmup if any (moving worlds counts as movement)
        MysticEssentialsCommon.get().warmups.onWorldChange(p);

        var common = MysticEssentialsCommon.get();
        var ps = common.punish;
        var oj = ps.getJailed(p.getUUID());
        if (oj.isEmpty()) return;

        var name = oj.get();
        var opt = ps.getJail(name);
        if (opt.isEmpty()) return;

        var pt = opt.get();
        var id = ResourceLocation.tryParse(pt.dim);
        if (id == null) return;

        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
        ServerLevel lvl = Objects.requireNonNull(p.getServer()).getLevel(key);
        if (lvl != null) {
            Teleports.pushBackAndTeleport(p, lvl, pt.x, pt.y, pt.z, pt.yaw, pt.pitch, common.pdata);
        }
    }

    // UUID/IP ban check on login; identity + playtime tracking
    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent e) {
        var p = (ServerPlayer) e.getEntity();
        var common = MysticEssentialsCommon.get();
        var ps = common.punish;

        boolean firstJoin = common.pdata.getLast(p.getUUID()).isEmpty();

        // ID tracking + Playtime marking
        common.pdata.setIdentityBasic(
                p.getUUID(),
                p.getGameProfile().getName(), // username
                null                          // nickname handled elsewhere
        );
        common.pdata.setLastIp(p.getUUID(), p.getIpAddress());
        common.pdata.markLogin(p.getUUID());

        // AFK essentials
        common.onPlayerJoin(p);

        // UUID ban
        var ub = ps.getUuidBan(p.getUUID());
        if (ub.isPresent()) {
            var b = ub.get();
            if (b.until == null || System.currentTimeMillis() < b.until) {
                p.connection.disconnect(Component.literal("§cBanned: §f" + b.reason));
                return;
            } else ps.unbanUuid(p.getUUID());
        }
        // IP ban
        String ip = p.getIpAddress();
        var ib = ps.getIpBan(ip);
        if (ib.isPresent()) {
            var b = ib.get();
            if (b.until == null || System.currentTimeMillis() < b.until) {
                p.connection.disconnect(Component.literal("§cIP Banned: §f" + b.reason));
            } else ps.unbanIp(ip);
        }

        // If still here, handle spawn for first join
        if (firstJoin) {
            common.teleportToSpawnIfSet(p);
        }

        CommonServer cs = wrapServer(p.getServer());
        CommonPlayer cp = wrapPlayer(p);
        common.systemMessages.broadcastJoin(cs, cp);
    }

    // Warmups: only cancel after real damage was applied
    private void onDamagePost(LivingDamageEvent.Post e) {
        if (e.getEntity() instanceof ServerPlayer sp) {
            MysticEssentialsCommon.get().warmups.onDamaged(sp);
        }
    }

    private record WrappedCommonPlayer(ServerPlayer handle) implements CommonPlayer {

        @Override
        public java.util.UUID getUuid() {
            return handle.getUUID();
        }

        @Override
        public String getName() {
            return handle.getGameProfile().getName();
        }

        @Override
        public String getWorldId() {
            return handle.serverLevel().dimension().location().toString();
        }

        @Override
        public double getX() {
            return handle.getX();
        }

        @Override
        public double getY() {
            return handle.getY();
        }

        @Override
        public double getZ() {
            return handle.getZ();
        }

        @Override
        public boolean hasPermission(String permission) {
            // TODO: wire real perms; for system messages we don't use this
            return handle.hasPermissions(2);
        }

        @Override
        public void sendChatMessage(String miniMessageString) {
            // 1) Apply placeholders with the RECEIVER as context (NO LuckPerms)
            String withPlaceholders =
                    NeoForgePlaceholders.applyViewer(handle, miniMessageString);

            // 2) MiniMessage → Adventure → vanilla
            Object adv = com.alphine.mysticessentials.chat.ChatText.mm(withPlaceholders);

            net.minecraft.network.chat.Component vanilla =
                    com.alphine.mysticessentials.util.AdventureComponentBridge
                            .advToNative(adv, handle.registryAccess());

            handle.displayClientMessage(vanilla, false);
        }

        @Override
        public String applySenderPlaceholders(String input) {
            return LuckPermsPlaceholders.apply(handle, input);
        }


        @Override
        public void playSound(String soundId, float volume, float pitch) {
            // Not used by system messages; implement if needed later
        }
    }
}
