package com.alphine.mysticessentials.fabric;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.chat.ChatModule;
import com.alphine.mysticessentials.chat.placeholder.LuckPermsPlaceholders;
import com.alphine.mysticessentials.chat.platform.CommonPlayer;
import com.alphine.mysticessentials.chat.platform.CommonServer;
import com.alphine.mysticessentials.chat.redis.RedisClientAdapter;
import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.fabric.platform.FabricModInfoService;
import com.alphine.mysticessentials.fabric.redis.LettuceRedisClientAdapter;
import com.alphine.mysticessentials.inv.InvseeSessions;
import com.alphine.mysticessentials.storage.PlayerDataStore;
import com.alphine.mysticessentials.update.ModrinthUpdateChecker;
import com.alphine.mysticessentials.util.Teleports;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MysticEssentialsFabric implements ModInitializer {

    private final Map<UUID, net.minecraft.world.phys.Vec3> afkLastPos = new HashMap<>();
    private final Map<UUID, float[]> afkLastRot = new HashMap<>();
    private int afkTickAccum = 0;
    private RedisClientAdapter redisAdapter;

    private final Map<UUID, Long> afkLastMoveCheckMs = new HashMap<>();
    private static final double AFK_MOVE_THRESHOLD = 0.35D;          // blocks
    private static final double AFK_MOVE_THRESHOLD_SQ = AFK_MOVE_THRESHOLD * AFK_MOVE_THRESHOLD;
    private static final long AFK_MOVE_DEBOUNCE_MS = 250L;
    private static final float AFK_ROT_THRESHOLD = 1.0f;             // degrees

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
                server.sendSystemMessage(net.minecraft.network.chat.Component.literal(plainText));
            }
        };
    }

    private static CommonPlayer wrapPlayer(ServerPlayer p) {
        return new WrappedCommonPlayer(p);
    }

    @Override
    public void onInitialize() {
        // Mod info service
        MysticEssentialsCommon.get().setModInfoService(new FabricModInfoService());

        // Lifecycle
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            // core services + config
            MysticEssentialsCommon.get().serverStarting(server);

            MEConfig cfg = MysticEssentialsCommon.get().cfg;
            RedisClientAdapter adapter = null;

            if (cfg != null && cfg.chat != null && cfg.chat.redis != null && cfg.chat.redis.enabled) {
                this.redisAdapter = new LettuceRedisClientAdapter(cfg.chat.redis);
                adapter = this.redisAdapter;
            }

            // init chat module (creates ChatPipeline with or without Redis)
            ChatModule.init(adapter);

            // Initialize auto-broadcast scheduler (MiniMessage -> players via CommonServer)
            CommonServer cs = wrapServer(server);
            MysticEssentialsCommon.get().initBroadcasts(mini -> {
                for (CommonPlayer cp : cs.getOnlinePlayers()) {
                    cp.sendChatMessage(mini);
                }
            });

            // --- Modrinth update check (Fabric) ---
            String currentVersion = MysticEssentialsCommon.getVersion();
            ModrinthUpdateChecker.checkForUpdatesAsync(
                    "JEA9OHq8",
                    currentVersion,
                    msg -> server.sendSystemMessage(Component.literal(msg))
            );
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            MysticEssentialsCommon.get().serverStopping();
            if (redisAdapter != null) {
                redisAdapter.shutdown();
                redisAdapter = null;
            }
        });

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

        // After-death respawn → global spawn *only if no bed/anchor is set*
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            // alive == false means this is a death respawn, not just a dimension change
            if (alive) return;

            // If the player has *no* personal respawn point (bed/anchor), fallback to plugin spawn
            if (newPlayer.getRespawnPosition() == null && !newPlayer.isRespawnForced()) {
                MysticEssentialsCommon.get().teleportToSpawnIfSet(newPlayer);
            }
        });

        // Last location on disconnect + stop any invsee tracking + cancel warmup
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer p = handler.player;
            var common = MysticEssentialsCommon.get();
            // System quit message
            CommonServer cs = wrapServer(server);
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

            InvseeSessions.close(p);
            common.warmups.cancel(p, net.minecraft.network.chat.Component.empty());

            common.privateMessages.onQuit(p);
            afkLastPos.remove(p.getUUID());
            afkLastRot.remove(p.getUUID());
            afkLastMoveCheckMs.remove(p.getUUID());
        });

        // Last death
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer p) {
                var l = new PlayerDataStore.LastLoc();
                l.dim = p.serverLevel().dimension().location().toString();
                l.x = p.getX();
                l.y = p.getY();
                l.z = p.getZ();
                l.yaw = p.getYRot();
                l.pitch = p.getXRot();
                l.when = System.currentTimeMillis();
                MysticEssentialsCommon.get().pdata.setDeath(p.getUUID(), l);

                // System death message
                String deathMsg = p.getCombatTracker().getDeathMessage().getString();
                CommonServer cs = wrapServer(p.getServer());
                CommonPlayer cp = wrapPlayer(p);
                MysticEssentialsCommon.get().systemMessages.broadcastDeath(cs, cp, deathMsg);
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
                long nowMs = System.currentTimeMillis();

                // debounce
                long lastCheck = afkLastMoveCheckMs.getOrDefault(id, 0L);
                if (nowMs - lastCheck >= AFK_MOVE_DEBOUNCE_MS) {
                    afkLastMoveCheckMs.put(id, nowMs);

                    var curPos = sp.position();
                    var lastPos = afkLastPos.get(id);

                    float yRot = sp.getYRot(), xRot = sp.getXRot();
                    var lastRot = afkLastRot.get(id);

                    boolean moved = false;
                    if (lastPos == null) {
                        moved = true;
                    } else {
                        // Vec3 has distanceToSqr(Vec3)
                        moved = curPos.distanceToSqr(lastPos) >= AFK_MOVE_THRESHOLD_SQ;
                    }

                    boolean rotated = false;
                    if (lastRot == null) {
                        rotated = true;
                    } else {
                        rotated = Math.abs(lastRot[0] - yRot) >= AFK_ROT_THRESHOLD
                                || Math.abs(lastRot[1] - xRot) >= AFK_ROT_THRESHOLD;
                    }

                    if (moved || rotated) {
                        common.onPlayerMove(sp);
                        afkLastPos.put(id, curPos);
                        afkLastRot.put(id, new float[]{yRot, xRot});
                    }
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

        // Chat mute + AFK ping + custom chat pipeline
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            ServerPlayer p = sender;
            var common = MysticEssentialsCommon.get();
            var ps = common.punish;

            var om = ps.getMute(p.getUUID());

            // --- MUTE GATE ---
            if (om.isPresent()) {
                var m = om.get();
                if (m.until == null || System.currentTimeMillis() < m.until) {
                    long rem = m.until == null ? -1 : (m.until - System.currentTimeMillis());
                    p.displayClientMessage(
                            net.minecraft.network.chat.Component.literal(
                                    "§cYou are muted" + (rem > 0 ? " §7(" +
                                            com.alphine.mysticessentials.util.DurationUtil.fmtRemaining(rem) + ")" : " §7(permanent)") + "."),
                            false
                    );
                    // Block message entirely (no AFK mark, no chat pipeline)
                    return false;
                } else {
                    ps.unmute(p.getUUID());
                }
            }

            // --- AFK activity + AFK ping util (your old behavior) ---
            String raw = (message.unsignedContent() != null)
                    ? message.unsignedContent().getString()
                    : message.signedContent();

            common.onPlayerChat(p); // mark AFK activity
            if (!raw.isBlank() && !raw.startsWith("/")) {
                com.alphine.mysticessentials.util.AfkPingUtil.handleChatMention(p.getServer(), p, raw);
            }

            // --- Custom chat pipeline (common) ---
            boolean handled = com.alphine.mysticessentials.fabric.chat.FabricChatBridge
                    .handleAllowChat(message, p);

            // If handled by MysticEssentials, cancel vanilla handling
            return !handled;
        });

        // Kick on join if banned (uuid/ip)
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer p = handler.player;
            var common = MysticEssentialsCommon.get();
            var ps = common.punish;

            boolean firstJoin = common.pdata.getLast(p.getUUID()).isEmpty();

            // identity + playtime
            common.pdata.setIdentityBasic(
                    p.getUUID(),
                    p.getGameProfile().getName(), // username
                    null                          // don't touch nickname here
            );
            common.pdata.setLastIp(p.getUUID(), p.getIpAddress());
            common.pdata.markLogin(p.getUUID());

            // AFK + other join logic
            common.onPlayerJoin(p);

            // Ban Checks
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

            // If still here, they're allowed to play → handle spawn
            if (firstJoin) {
                common.teleportToSpawnIfSet(p);
            }

            CommonServer cs = wrapServer(server);
            CommonPlayer cp = wrapPlayer(p);
            common.systemMessages.broadcastJoin(cs, cp);
        });
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
            // TODO: real perms later; not needed for system messages
            return handle.hasPermissions(2);
        }

        @Override
        public void sendChatMessage(String miniMessageString) {
            // 1) pb4 placeholders, using the RECEIVER as context (NO LuckPerms here)
            String withPlaceholders =
                    com.alphine.mysticessentials.fabric.placeholder.FabricPlaceholders
                            .applyViewer(handle, miniMessageString);

            // 2) MiniMessage → Adventure → vanilla Component
            Object adv = com.alphine.mysticessentials.chat.ChatText.mm(withPlaceholders);

            net.minecraft.network.chat.Component vanilla =
                    com.alphine.mysticessentials.util.AdventureComponentBridge
                            .advToNative(adv, handle.registryAccess());

            handle.displayClientMessage(vanilla, false);
        }

        @Override
        public String applySenderPlaceholders(String input) {
            // Only LuckPerms – viewer pb4 is handled later when sending
            return LuckPermsPlaceholders.apply(handle, input);
        }

        @Override
        public void playSound(String soundId, float volume, float pitch) {
            // Not used by system messages; implement with registry lookup if needed
        }
    }
}
