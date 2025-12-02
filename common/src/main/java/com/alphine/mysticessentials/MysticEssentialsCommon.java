package com.alphine.mysticessentials;

import com.alphine.mysticessentials.chat.ChatStateService;
import com.alphine.mysticessentials.chat.MotdService;
import com.alphine.mysticessentials.chat.PrivateMessageService;
import com.alphine.mysticessentials.chat.SystemMessageService;
import com.alphine.mysticessentials.chat.platform.CommonPlayer;
import com.alphine.mysticessentials.commands.CommandRegistrar;
import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.platform.ModInfoService;
import com.alphine.mysticessentials.storage.*;
import com.alphine.mysticessentials.teleport.*;
import com.alphine.mysticessentials.util.AfkService;
import com.alphine.mysticessentials.util.GodService;
import com.alphine.mysticessentials.util.MessagesUtil;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

public final class MysticEssentialsCommon {
    private static final MysticEssentialsCommon I = new MysticEssentialsCommon();
    public static MysticEssentialsCommon get() { return I; }

    public static final String MOD_ID = "mysticessentials";
    private static String version = "UNKNOWN";

    private ModInfoService modInfo;

    private java.nio.file.Path configDir;

    // Services/stores
    public MEConfig cfg;
    public HomesStore homes;
    public WarpsStore warps;
    public SpawnStore spawn;
    public PlayerDataStore pdata;
    public AfkService afk;
    public AfkPoolsStore afkPools;
    public PunishStore punish;
    public AuditLogStore audit;
    public KitStore kits;
    public KitPlayerStore kitsPlayers;
    public final WarmupManager warmups = new WarmupManager();
    public final CooldownManager cooldowns = new CooldownManager();
    public final TpaManager tpas = new TpaManager();
    public final GodService god = new GodService();
    public final PrivateMessageService privateMessages = new PrivateMessageService();
    public final SystemMessageService systemMessages = new SystemMessageService();

    // per-player chat state (active channel, etc.)
    public final ChatStateService chatState = new ChatStateService();

    // Mod info service
    public void setModInfoService(ModInfoService svc) { this.modInfo = svc; }
    public ModInfoService getModInfoService() { return modInfo; }

    private MysticEssentialsCommon() {
        loadVersion();
    }

    public void serverStarting(MinecraftServer server) {
        Path cfgDir = server.getServerDirectory()
                .resolve("config").resolve(MOD_ID).normalize();
        ensureCoreServices(cfgDir);

        cooldowns.updateFromConfig();

        // Re-mirror store -> config and notify AFK service
        cfg.afk.pools.clear();
        cfg.afk.pools.putAll(afkPools.viewAll());
        afk.reloadPools();
    }

    public void ensureCoreServices(Path cfgDir) {
        this.configDir = cfgDir;

        if (cfg == null) {
            cfg = MEConfig.load(cfgDir);
            if (MEConfig.INSTANCE == null) MEConfig.INSTANCE = cfg;
        }

        // Initialize messages AFTER config is available so we know the locale.
        try {
            String locale = (cfg.locale == null || cfg.locale.isBlank()) ? "en_us" : cfg.locale;
            MessagesUtil.init(cfgDir, locale);
        } catch (Throwable t) {
            System.err.println("[MysticEssentials] Failed to initialize MessagesUtil: " + t.getMessage());
        }

        if (pdata == null)      pdata = new PlayerDataStore(cfgDir);
        if (homes == null)      homes = new HomesStore(pdata);
        if (warps == null)      warps = new WarpsStore(cfgDir);
        if (spawn == null)      spawn = new SpawnStore(cfgDir);
        if (afkPools == null)   afkPools = new AfkPoolsStore(cfgDir);

        // mirror pools -> config only if empty (prevents duplicates if called twice)
        if (cfg.afk != null && cfg.afk.pools != null && cfg.afk.pools.isEmpty()) {
            cfg.afk.pools.putAll(afkPools.viewAll());
        }

        if (afk == null)        afk = new AfkService(cfg, pdata);
        if (punish == null)     punish = new PunishStore(cfgDir);
        if (audit == null)      audit  = new AuditLogStore(cfgDir);
        if (kits == null)       kits   = new KitStore(cfgDir);
        if (kitsPlayers == null) kitsPlayers = new KitPlayerStore(pdata);
    }

    public void registerCommands(CommandDispatcher<CommandSourceStack> d) {
        CommandRegistrar.registerAll(this, d);
    }

    public void serverStopping() {
        if (homes != null) homes.save();
        if (warps != null) warps.save();
        if (spawn != null) spawn.save();
        if (pdata != null) pdata.save();
        if (punish != null) punish.save();
        if (afkPools != null) afkPools.save();
    }

    // Call this from /mereload
    public int reloadAll() {
        int n = 0;

        // Reload main MEConfig from disk
        if (MEConfig.INSTANCE != null) {
            MEConfig.INSTANCE.reload();
            this.cfg = MEConfig.INSTANCE;
            n++;
        }

        // Reload chat configs (channels, tags, mention, replacements,
        //    private messages, broadcast, shout, etc.)
        try {
            if (this.configDir != null && cfg != null && cfg.chat != null && cfg.chat.files != null) {
                com.alphine.mysticessentials.config.ChatConfigManager
                        .loadAll(this.configDir, cfg.chat.files);
                n++;
            }
        } catch (Throwable t) {
            System.err.println("[MysticEssentials] Failed to reload chat configs: " + t.getMessage());
        }

        // Reload message files (messages/<locale>.json)
        try {
            MessagesUtil.reload();
            n++;
        } catch (Throwable ignored) {}

        // Re-apply cooldown config
        try {
            this.cooldowns.updateFromConfig();
            n++;
        } catch (Throwable ignored) {}

        // Re-mirror AFK pools store -> config and notify AFK service
        if (afkPools != null && cfg != null) {
            cfg.afk.pools.clear();
            cfg.afk.pools.putAll(afkPools.viewAll());
            n++;
        }
        if (afk != null) {
            afk.reloadPools();
            n++;
        }

        return n;
    }

    /** Call once per second from Fabric/NeoForge adapters. */
    public void serverTick1s(MinecraftServer server) {
        if (afk != null) afk.tick(server);
    }

    private boolean featuresEnabled() {
        return cfg != null && cfg.features != null && cfg.features.enableAfkSystem;
    }

    public java.nio.file.Path getConfigDir() { return configDir; }

    /** Forward activity events to AFK service. */
    public void onPlayerMove(ServerPlayer p){ if (featuresEnabled() && afk != null) afk.markActiveMovement(p); }
    public void onPlayerInteract(ServerPlayer p){ if (featuresEnabled() && afk != null) afk.markActiveInteraction(p); }
    public void onPlayerChat(ServerPlayer p){ if (featuresEnabled() && afk != null) afk.markActiveChat(p); }
    public void onPlayerJoin(ServerPlayer p){
        if (featuresEnabled() && afk != null)
            afk.onJoin(p);
        // Always handled here so both Fabric + NeoForge get the same MOTD behavior
        MotdService.sendJoinMotd(p);
    }

    public void onPlayerQuit(ServerPlayer p){
        if (featuresEnabled() && afk != null) afk.onQuit(p.getUUID());
        privateMessages.onQuit(p);
        chatState.clear(p.getUUID());
    }

    /**
     * Lightweight wrapper used where we only need CommonPlayer for
     * permission checks (e.g. /channel aliases).
     *
     * NOTE: for full chat sending we use platform-specific wrappers;
     * this one is intentionally minimal.
     */
    public CommonPlayer wrapPlayer(ServerPlayer sp) {
        return new CommonPlayer() {
            @Override
            public UUID getUuid() {
                return sp.getUUID();
            }

            @Override
            public String getName() {
                return sp.getGameProfile().getName();
            }

            @Override
            public String getWorldId() {
                return sp.serverLevel().dimension().location().toString();
            }

            @Override
            public double getX() {
                return sp.getX();
            }

            @Override
            public double getY() {
                return sp.getY();
            }

            @Override
            public double getZ() {
                return sp.getZ();
            }

            @Override
            public boolean hasPermission(String permission) {
                return Perms.has(sp, permission, 0);
            }

            @Override
            public void sendChatMessage(String miniMessageString) {
                // This wrapper is currently not used for sending real chat;
                // if it ever is, you'd want to route through your Adventure
                // platform instance. For now, basic literal fallback:
                sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(miniMessageString));
            }

            @Override
            public void playSound(String soundId, float volume, float pitch) {
                // Optional: resolve sound safely, or just no-op
                // This wrapper is only used for permissions, so it's ok to skip.
            }
        };
    }

    private void loadVersion() {
        try (InputStream in = MysticEssentialsCommon.class
                .getClassLoader()
                .getResourceAsStream("mysticessentials-common.properties")) {

            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                version = props.getProperty("version", "UNKNOWN");
            }

        } catch (IOException ignored) {}
    }

    public static String getVersion() {
        return version;
    }
}
