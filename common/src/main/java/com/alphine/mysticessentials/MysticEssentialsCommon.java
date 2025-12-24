package com.alphine.mysticessentials;

import com.alphine.mysticessentials.chat.*;
import com.alphine.mysticessentials.chat.platform.CommonPlayer;
import com.alphine.mysticessentials.commands.CommandRegistrar;
import com.alphine.mysticessentials.config.ChatConfigManager;
import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.hologram.HologramManager;
import com.alphine.mysticessentials.npc.NpcManager;
import com.alphine.mysticessentials.npc.NpcPlatformAdapter;
import com.alphine.mysticessentials.npc.skin.NpcSkinService;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.placeholders.BuiltinPlaceholders;
import com.alphine.mysticessentials.placeholders.LuckPermsBuiltinProvider;
import com.alphine.mysticessentials.placeholders.PlaceholderService;
import com.alphine.mysticessentials.platform.ModInfoService;
import com.alphine.mysticessentials.storage.*;
import com.alphine.mysticessentials.teleport.CooldownManager;
import com.alphine.mysticessentials.teleport.TpaManager;
import com.alphine.mysticessentials.teleport.WarmupManager;
import com.alphine.mysticessentials.util.AfkService;
import com.alphine.mysticessentials.util.GodService;
import com.alphine.mysticessentials.util.MessagesUtil;
import com.alphine.mysticessentials.util.Teleports;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

public final class MysticEssentialsCommon {
    public static final String MOD_ID = "mysticessentials";
    private static final MysticEssentialsCommon I = new MysticEssentialsCommon();
    private static volatile String version = "UNKNOWN";
    public final WarmupManager warmups = new WarmupManager();
    public final CooldownManager cooldowns = new CooldownManager();
    public final TpaManager tpas = new TpaManager();
    public final GodService god = new GodService();
    public final PrivateMessageService privateMessages = new PrivateMessageService();
    public final SystemMessageService systemMessages = new SystemMessageService();
    // per-player chat state (active channel, etc.)
    public final ChatStateService chatState = new ChatStateService();
    // Placeholder service
    public final PlaceholderService placeholders = new PlaceholderService();
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
    // Vaults
    public com.alphine.mysticessentials.vault.VaultStore vaultStore;
    public com.alphine.mysticessentials.vault.VaultService vaultService;
    public com.alphine.mysticessentials.vault.VaultOpen vaultOpen;
    public com.alphine.mysticessentials.vault.VaultSelectorUi vaultSelectorUi;
    // Holograms and NPCs
    public com.alphine.mysticessentials.hologram.HologramManager hologramManager;
    public com.alphine.mysticessentials.hologram.store.HologramStore holograms;

    public com.alphine.mysticessentials.npc.NpcManager npcManager;
    public com.alphine.mysticessentials.npc.store.NpcStore npcs;

    // NPC skin + platform
    public NpcSkinService npcSkinService;
    public NpcPlatformAdapter npcPlatform;


    private ModInfoService modInfo;
    private java.nio.file.Path configDir;
    private BroadcastScheduler broadcastScheduler;
    private transient MinecraftServer server;

    private MysticEssentialsCommon() {
    }

    public static MysticEssentialsCommon get() {
        return I;
    }

    public static String getVersion() {
        return version;
    }

    private static String safe(String v) {
        if (v == null || v.isBlank()) return "UNKNOWN";
        return v;
    }

    public ModInfoService getModInfoService() {
        return modInfo;
    }

    // Mod info service
    public void setModInfoService(ModInfoService svc) {
        this.modInfo = svc;
        refreshVersion();
    }

    public void serverStarting(MinecraftServer server) {
        this.server = server;
        Path cfgDir = server.getServerDirectory()
                .resolve("config").resolve(MOD_ID).normalize();
        System.out.println("[MysticEssentials] Config directory: " + cfgDir);
        System.out.println("[MysticEssentials] Loading config...");
        ensureCoreServices(cfgDir);

        // Placeholders
        System.out.println("[MysticEssentials] Loading Placeholders...");
        BuiltinPlaceholders.registerAll(placeholders);
        LuckPermsBuiltinProvider.registerAll(placeholders);
        System.out.println("[MysticEssentials] Placeholder Service initialized with "
                + placeholders.countRegistered() + " placeholders.");

        // Holograms
        if (cfg != null && cfg.holograms != null && cfg.holograms.enabled && hologramManager != null) {
            System.out.println("[MysticEssentials] Hologram Manager loading holograms...");
            hologramManager.reloadAll(server);          // spawns fresh + owns runtime UUIDs
        }
        if (cfg != null && cfg.npcs != null && cfg.npcs.enabled && npcManager != null) {
            System.out.println("[MysticEssentials] NPC Manager loading NPCs...");
            npcManager.reloadAll(server);
        }


        // Vault store needs registry access for ItemStack (1.21+)
        if (vaultStore instanceof com.alphine.mysticessentials.vault.store.JsonVaultStore js) {
            System.out.println("[MysticEssentials] Vault Store loading JSON...");
            js.setRegistryAccess(server.registryAccess());
        }

        cooldowns.updateFromConfig();
        System.out.println("[MysticEssentials] Cooldowns update...");

        // Re-mirror store -> config and notify AFK service
        System.out.println("[MysticEssentials] Afk Pools syncing...");
        cfg.afk.pools.clear();
        cfg.afk.pools.putAll(afkPools.viewAll());
        afk.reloadPools();
    }

    public void serverStarted(MinecraftServer server) {
        System.out.println("[MysticEssentials] Server started...");
        System.out.println("[MysticEssentials] Version: " + getVersion());
    }

    public void ensureCoreServices(Path cfgDir) {
        this.configDir = cfgDir;

        if (cfg == null) {
            cfg = MEConfig.load(cfgDir);
            if (MEConfig.INSTANCE == null) MEConfig.INSTANCE = cfg;
        }

        // Initialize MOTD service
        MotdService.init(placeholders);

        // Holograms / NPC managers
        if (cfg.holograms != null && cfg.holograms.enabled) {
            if (hologramManager == null) {
                hologramManager = new HologramManager(this);
                System.out.println("[MysticEssentials] Hologram Manager initialized.");
            }
        }

        if (cfg.npcs != null && cfg.npcs.enabled) {
            // Skin service (only if skin sub-module is enabled)
            if (cfg.npcs.skin != null && cfg.npcs.skin.enabled && npcSkinService == null) {
                npcSkinService = new NpcSkinService(this, cfgDir);
                System.out.println("[MysticEssentials] NPC Skin Service initialized.");
            }

            if (npcManager == null) {
                npcManager = new NpcManager(this);
                System.out.println("[MysticEssentials] NPC Manager initialized.");
            }
        }


        if (holograms == null) {
            holograms = new com.alphine.mysticessentials.hologram.store.HologramStore(
                    MEConfig.getGson(),
                    cfgDir,
                    cfg.holograms != null ? cfg.holograms.directory : "holograms",
                    cfg.holograms != null && cfg.holograms.atomicSaves
            );
            try {
                System.out.println("[MysticEssentials] Hologram Store loaded.");
                holograms.loadAll();
            } catch (IOException e) {
                System.err.println("[MysticEssentials] Failed to load holograms: " + e.getMessage());
            }
        }

        if (npcs == null) {
            npcs = new com.alphine.mysticessentials.npc.store.NpcStore(
                    MEConfig.getGson(),
                    cfgDir,
                    cfg.npcs != null ? cfg.npcs.directory : "npcs",
                    cfg.npcs != null && cfg.npcs.atomicSaves
            );
            try {
                System.out.println("[MysticEssentials] NPC Store loaded.");
                npcs.loadAll();
            } catch (IOException e) {
                System.err.println("[MysticEssentials] Failed to load npcs: " + e.getMessage());
            }
        }

        // Initialize messages AFTER config is available so we know the locale.
        try {
            String locale = (cfg.locale == null || cfg.locale.isBlank()) ? "en_us" : cfg.locale;
            MessagesUtil.init(cfgDir, locale);
        } catch (Throwable t) {
            System.err.println("[MysticEssentials] Failed to initialize MessagesUtil: " + t.getMessage());
        }

        // Data stores
        System.out.println("[MysticEssentials] Loading Stores...");
        if (pdata == null) pdata = new PlayerDataStore(cfgDir);
        if (homes == null) homes = new HomesStore(pdata);
        if (warps == null) warps = new WarpsStore(cfgDir);
        if (spawn == null) spawn = new SpawnStore(cfgDir);
        if (afkPools == null) afkPools = new AfkPoolsStore(cfgDir);

        // mirror pools -> config only if empty (prevents duplicates if called twice)
        if (cfg.afk != null && cfg.afk.pools != null && cfg.afk.pools.isEmpty()) {
            System.out.println("[MysticEssentials] AFK Pools loading...");
            cfg.afk.pools.putAll(afkPools.viewAll());
        }

        if (afk == null) afk = new AfkService(cfg, pdata);
        if (punish == null) punish = new PunishStore(cfgDir);
        if (audit == null) audit = new AuditLogStore(cfgDir);
        if (kits == null) kits = new KitStore(cfgDir);
        if (kitsPlayers == null) kitsPlayers = new KitPlayerStore(pdata);

        // Vaults
        System.out.println("[MysticEssentials] Loading Vaults...");
        if (vaultStore == null) vaultStore = new com.alphine.mysticessentials.vault.store.JsonVaultStore(cfgDir);
        if (vaultService == null) vaultService = new com.alphine.mysticessentials.vault.VaultService(vaultStore);
        if (vaultOpen == null) vaultOpen = new com.alphine.mysticessentials.vault.VaultOpen(vaultService);
        if (vaultSelectorUi == null)
            vaultSelectorUi = new com.alphine.mysticessentials.vault.VaultSelectorUi(vaultService, vaultOpen);


        // register module placeholders
        placeholders.register((key, ctx) -> {
            // example: vault placeholders
            if (!key.startsWith("vault_")) return null;
            // implement later
            return null;
        });
    }

    public void registerCommands(CommandDispatcher<CommandSourceStack> d) {
        CommandRegistrar.registerAll(this, d);
    }

    public void serverStopping() {
        System.out.println("[MysticEssentials] Server stopping. Saving data...");
        // Despawn hologram entities FIRST so they don't get saved into chunks
        if (server != null && hologramManager != null) {
            hologramManager.shutdown(server);
            System.out.println("[MysticEssentials] Hologram Manager shutdown.");
        }

        if (npcManager != null) {
            npcManager.shutdown(server);
            System.out.println("[MysticEssentials] NPC Manager shutdown.");
        }

        // Save all stores
        System.out.println("[MysticEssentials] Saved Stores...");
        if (homes != null) homes.save();
        if (warps != null) warps.save();
        if (spawn != null) spawn.save();
        if (pdata != null) pdata.save();
        if (punish != null) punish.save();
        if (afkPools != null) afkPools.save();
        try {
            if (holograms != null) holograms.saveAll();
        } catch (Throwable ignored) {
        }
        try {
            if (npcs != null) npcs.saveAll();
        } catch (Throwable ignored) {
        }
        System.out.println("[MysticEssentials] Saved Vaults... DONE! Goodbye!");
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

                // Reset announcement timer so new config takes effect cleanly
                if (broadcastScheduler != null) {
                    broadcastScheduler.resetTimer();
                }
            }
        } catch (Throwable t) {
            System.err.println("[MysticEssentials] Failed to reload chat configs: " + t.getMessage());
        }

        // Reload holograms
        try {
            if (holograms != null && cfg != null && cfg.holograms != null && cfg.holograms.enabled) {
                holograms.reloadAll();
                n++;

                if (server != null && hologramManager != null) {
                    hologramManager.reloadAll(server);
                    n++;
                }
            }
        } catch (Throwable t) {
            System.err.println("[MysticEssentials] Failed to reload holograms: " + t.getMessage());
        }

        // Reload NPCs
        try {
            boolean npcFeatureEnabled =
                    cfg != null
                            && cfg.npcs != null
                            && cfg.npcs.enabled
                            && (cfg.features == null || cfg.features.enableNpcSystem);

            if (npcs != null && npcFeatureEnabled) {
                npcs.reloadAll();
                n++;

                if (server != null && npcManager != null) {
                    npcManager.reloadAll(server);
                    n++;
                }
            }
        } catch (Throwable t) {
            System.err.println("[MysticEssentials] Failed to reload npcs: " + t.getMessage());
        }


        // Reload message files (messages/<locale>.json)
        try {
            MessagesUtil.reload();
            n++;
        } catch (Throwable ignored) {
        }

        // Re-apply cooldown config
        try {
            this.cooldowns.updateFromConfig();
            n++;
        } catch (Throwable ignored) {
        }

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

        // Reload holograms
        try {
            if (holograms != null && cfg != null && cfg.holograms != null && cfg.holograms.enabled) {
                holograms.reloadAll();
                n++;
            }
        } catch (Throwable t) {
            System.err.println("[MysticEssentials] Failed to reload holograms: " + t.getMessage());
        }

        // Reload NPCs
        try {
            if (npcs != null && cfg != null && cfg.npcs != null && cfg.npcs.enabled) {
                npcs.reloadAll();
                n++;
            }
        } catch (Throwable t) {
            System.err.println("[MysticEssentials] Failed to reload npcs: " + t.getMessage());
        }

        return n;
    }

    /**
     * Call once per second from Fabric/NeoForge adapters.
     */
    public void serverTick1s(MinecraftServer server) {
        if (afk != null) afk.tick(server);
        if (broadcastScheduler != null && cfg != null && cfg.features != null && cfg.features.enableChatSystem) {
            broadcastScheduler.tick();
        }
    }

    private boolean featuresEnabled() {
        return cfg != null && cfg.features != null && cfg.features.enableAfkSystem;
    }

    public java.nio.file.Path getConfigDir() {
        return configDir;
    }

    /**
     * Forward activity events to AFK service.
     */
    public void onPlayerMove(ServerPlayer p) {
        if (featuresEnabled() && afk != null) afk.markActiveMovement(p);
    }

    public void onPlayerInteract(ServerPlayer p) {
        if (featuresEnabled() && afk != null) afk.markActiveInteraction(p);
    }

    public void onPlayerChat(ServerPlayer p) {
        if (featuresEnabled() && afk != null) afk.markActiveChat(p);
    }

    public void onPlayerJoin(ServerPlayer p) {
        if (featuresEnabled() && afk != null)
            afk.onJoin(p);
        // Always handled here so both Fabric + NeoForge get the same MOTD behavior
        MotdService.sendJoinMotd(p);
    }

    public void onPlayerQuit(ServerPlayer p) {
        if (featuresEnabled() && afk != null) afk.onQuit(p.getUUID());
        privateMessages.onQuit(p);
        chatState.clear(p.getUUID());
    }

    /**
     * Lightweight wrapper used where we only need CommonPlayer for
     * permission checks (e.g. /channel aliases).
     * <p>
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

    public void refreshVersion() {
        try {
            if (modInfo != null) {
                version = safe(modInfo.getVersion());
            }
        } catch (Throwable ignored) {
            version = "UNKNOWN";
        }
    }

    /**
     * Initialize auto-broadcasts once the chat module + platform are ready.
     * The broadcaster should send a MiniMessage string to all online players.
     */
    public void initBroadcasts(ChatBroadcaster broadcaster) {
        this.broadcastScheduler = new BroadcastScheduler(
                () -> ChatConfigManager.ANNOUNCEMENTS,
                broadcaster
        );
    }

    /**
     * Teleport a player to the global spawn, if one is set.
     *
     * @return true if a teleport actually happened, false if no spawn is configured.
     */
    public boolean teleportToSpawnIfSet(ServerPlayer player) {
        if (spawn == null) return false;

        SpawnStore.Point pt = spawn.get();
        if (pt == null || pt.dim == null || pt.dim.isBlank()) {
            return false;
        }

        var server = player.getServer();
        if (server == null) {
            return false;
        }

        ResourceLocation id = ResourceLocation.tryParse(pt.dim);
        if (id == null) return false;

        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
        ServerLevel level = server.getLevel(key);
        if (level == null) return false;

        Teleports.pushBackAndTeleport(
                player,
                level,
                pt.x, pt.y, pt.z,
                pt.yaw, pt.pitch,
                pdata
        );
        return true;
    }

    public void setNpcPlatform(NpcPlatformAdapter adapter) {
        this.npcPlatform = adapter;
    }
}
