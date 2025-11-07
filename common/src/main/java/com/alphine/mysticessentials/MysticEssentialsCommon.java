package com.alphine.mysticessentials;

import com.alphine.mysticessentials.commands.CommandRegistrar;
import com.alphine.mysticessentials.platform.ModInfoService;
import com.alphine.mysticessentials.storage.*;
import com.alphine.mysticessentials.teleport.*;
import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.util.AfkService;
import com.alphine.mysticessentials.util.GodService;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

public final class MysticEssentialsCommon {
    private static final MysticEssentialsCommon I = new MysticEssentialsCommon();
    private ModInfoService modInfo;
    public static MysticEssentialsCommon get() { return I; }

    public static final String MOD_ID = "mysticessentials";

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

    // Mod info service
    public void setModInfoService(ModInfoService svc) { this.modInfo = svc; }
    public ModInfoService getModInfoService() { return modInfo; }

    private MysticEssentialsCommon() {}

    public void serverStarting(MinecraftServer server) {
        Path cfgDir = server.getServerDirectory()
                .resolve("config").resolve(MOD_ID).normalize();
        ensureCoreServices(cfgDir);

        // Re-mirror store -> config and notify AFK service
        cfg.afk.pools.clear();
        cfg.afk.pools.putAll(afkPools.viewAll());
        afk.reloadPools();
    }

    public void ensureCoreServices(Path cfgDir) {
        if (cfg == null) {
            cfg = MEConfig.load(cfgDir);
            if (MEConfig.INSTANCE == null) MEConfig.INSTANCE = cfg;
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
        if (MEConfig.INSTANCE != null) { MEConfig.INSTANCE.reload(); n++; }
        try { this.cooldowns.updateFromConfig(); n++; } catch (Throwable ignored) {}

        // Re-mirror store -> config and notify AFK service
        if (afkPools != null && cfg != null) {
            cfg.afk.pools.clear();
            cfg.afk.pools.putAll(afkPools.viewAll());
            n++;
        }
        if (afk != null) { afk.reloadPools(); n++; }

        return n;
    }

    /** Call once per second from Fabric/NeoForge adapters. */
    public void serverTick1s(MinecraftServer server) {
        if (afk != null) afk.tick(server);
    }

    private boolean featuresEnabled() { return cfg != null && cfg.features != null && cfg.features.enableAfkSystem; }

    /** Forward activity events to AFK service. */
    public void onPlayerMove(ServerPlayer p){ if (featuresEnabled() && afk != null) afk.markActiveMovement(p); }
    public void onPlayerInteract(ServerPlayer p){ if (featuresEnabled() && afk != null) afk.markActiveInteraction(p); }
    public void onPlayerChat(ServerPlayer p){ if (featuresEnabled() && afk != null) afk.markActiveChat(p); }
    public void onPlayerJoin(ServerPlayer p){ if (featuresEnabled() && afk != null) afk.onJoin(p); }
    public void onPlayerQuit(ServerPlayer p){ if (featuresEnabled() && afk != null) afk.onQuit(p.getUUID()); }
}
