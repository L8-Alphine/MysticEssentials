package com.alphine.mysticessentials.commands.afk;

import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.storage.AfkPoolsStore;
import com.alphine.mysticessentials.util.AfkService;
import com.alphine.mysticessentials.util.MessagesUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class AfkPoolSetupCmd {
    private final AfkService afkService;
    private final MEConfig config;
    private final AfkPoolsStore store;

    private final Map<UUID, Working> drafts = new ConcurrentHashMap<>();

    public AfkPoolSetupCmd(AfkService svc, MEConfig cfg, AfkPoolsStore store) {
        this.afkService = Objects.requireNonNull(svc);
        this.config = Objects.requireNonNull(cfg);
        this.store = Objects.requireNonNull(store);
    }

    private static final class Working {
        final String name;
        final MEConfig.AfkPool pool;
        Working(String name, MEConfig.AfkPool base) {
            this.name = name;
            this.pool = base != null ? base : new MEConfig.AfkPool();
            if (this.pool.region == null) this.pool.region = new MEConfig.PoolBox();
            if (this.pool.teleport == null) this.pool.teleport = new MEConfig.TeleportPoint("minecraft:overworld", 0.5,64,0.5,0,0);
        }
        BlockPos pos1Tmp, pos2Tmp;
    }

    public void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("afkpool")
                .requires(src -> Perms.has(src, PermNodes.AFK_SET_POOL, 2))
                .then(Commands.literal("setup")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.literal("pos1").executes(ctx -> setPos(ctx.getSource(), StringArgumentType.getString(ctx, "name"), 1)))
                                .then(Commands.literal("pos2").executes(ctx -> setPos(ctx.getSource(), StringArgumentType.getString(ctx, "name"), 2)))
                                .then(Commands.literal("tp").executes(ctx -> setTp(ctx.getSource(), StringArgumentType.getString(ctx, "name"))))
                                .then(Commands.literal("enable").executes(ctx -> setEnabled(ctx.getSource(), StringArgumentType.getString(ctx, "name"), true)))
                                .then(Commands.literal("disable").executes(ctx -> setEnabled(ctx.getSource(), StringArgumentType.getString(ctx, "name"), false)))
                                .then(Commands.literal("require-permission")
                                        .then(Commands.argument("value", BoolArgumentType.bool())
                                                .executes(ctx -> flip(ctx.getSource(), StringArgumentType.getString(ctx,"name"), "require-permission", BoolArgumentType.getBool(ctx,"value")))))
                                .then(Commands.literal("allow-chat")
                                        .then(Commands.argument("value", BoolArgumentType.bool())
                                                .executes(ctx -> flip(ctx.getSource(), StringArgumentType.getString(ctx,"name"), "allow-chat", BoolArgumentType.getBool(ctx,"value")))))
                                .then(Commands.literal("allow-exit")
                                        .then(Commands.argument("value", BoolArgumentType.bool())
                                                .executes(ctx -> flip(ctx.getSource(), StringArgumentType.getString(ctx,"name"), "allow-exit", BoolArgumentType.getBool(ctx,"value")))))
                                .then(Commands.literal("reward")
                                        .then(Commands.literal("add")
                                                .then(Commands.argument("trackId", StringArgumentType.word())
                                                        .then(Commands.argument("everySeconds", IntegerArgumentType.integer(1))
                                                                .executes(ctx -> rewardAdd(ctx.getSource(),
                                                                        StringArgumentType.getString(ctx,"name"),
                                                                        StringArgumentType.getString(ctx,"trackId"),
                                                                        IntegerArgumentType.getInteger(ctx,"everySeconds"))))))
                                        .then(Commands.literal("addcmd")
                                                .then(Commands.argument("trackId", StringArgumentType.word())
                                                        .then(Commands.argument("command", StringArgumentType.greedyString())
                                                                .executes(ctx -> rewardAddCmd(ctx.getSource(),
                                                                        StringArgumentType.getString(ctx,"name"),
                                                                        StringArgumentType.getString(ctx,"trackId"),
                                                                        StringArgumentType.getString(ctx,"command"))))))
                                        .then(Commands.literal("additem")
                                                .then(Commands.argument("trackId", StringArgumentType.word())
                                                        .then(Commands.argument("material", StringArgumentType.word())
                                                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                                                        .executes(ctx -> rewardAddItem(ctx.getSource(),
                                                                                StringArgumentType.getString(ctx,"name"),
                                                                                StringArgumentType.getString(ctx,"trackId"),
                                                                                StringArgumentType.getString(ctx,"material"),
                                                                                IntegerArgumentType.getInteger(ctx,"amount"),
                                                                                "{}")))
                                                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                                                        .then(Commands.argument("nbtJson", StringArgumentType.greedyString())
                                                                                .executes(ctx -> rewardAddItem(ctx.getSource(),
                                                                                        StringArgumentType.getString(ctx,"name"),
                                                                                        StringArgumentType.getString(ctx,"trackId"),
                                                                                        StringArgumentType.getString(ctx,"material"),
                                                                                        IntegerArgumentType.getInteger(ctx,"amount"),
                                                                                        StringArgumentType.getString(ctx,"nbtJson"))))))))
                                        .then(Commands.literal("save").executes(ctx -> save(ctx.getSource(), StringArgumentType.getString(ctx, "name"))))
                                )
                        )
                )
        );
    }

    private int setPos(CommandSourceStack src, String name, int which) {
        ServerPlayer p = src.getPlayer();
        if (p == null) { src.sendFailure(MessagesUtil.msg("common.players_only")); return 0; }

        MEConfig.AfkPool base = store.get(name).orElse(config.afk.pools.get(name));
        Working w = drafts.computeIfAbsent(p.getUUID(), id -> new Working(name, base));
        if (!w.name.equalsIgnoreCase(name)) w = new Working(name, base);
        drafts.put(p.getUUID(), w);

        BlockPos bp = p.blockPosition();
        if (which == 1) w.pos1Tmp = bp; else w.pos2Tmp = bp;

        src.sendSuccess(() -> MessagesUtil.msg("afkpool.setup.set_pos",
                Map.of("which", which==1? "pos1":"pos2", "name", name, "x", bp.getX(), "y", bp.getY(), "z", bp.getZ())), false);
        return 1;
    }

    private int setTp(CommandSourceStack src, String name) {
        ServerPlayer p = src.getPlayer();
        if (p == null) { src.sendFailure(MessagesUtil.msg("common.players_only")); return 0; }

        MEConfig.AfkPool base = store.get(name).orElse(config.afk.pools.get(name));
        Working w = drafts.computeIfAbsent(p.getUUID(), id -> new Working(name, base));
        if (!w.name.equalsIgnoreCase(name)) w = new Working(name, base);
        drafts.put(p.getUUID(), w);

        var pos = p.position();
        w.pool.teleport = new MEConfig.TeleportPoint(p.level().dimension().location().toString(),
                pos.x, pos.y, pos.z, p.getYRot(), p.getXRot());

        src.sendSuccess(() -> MessagesUtil.msg("afkpool.setup.set_tp", Map.of("name", name)), false);
        return 1;
    }

    private int setEnabled(CommandSourceStack src, String name, boolean enabled) {
        Working w = ensureDraft(src, name);
        if (w == null) return 0;
        w.pool.enabled = enabled;
        src.sendSuccess(() -> MessagesUtil.msg("afkpool.setup.enabled",
                Map.of("name", name, "state", enabled ? "on" : "off")), false);
        return 1;
    }

    private int flip(CommandSourceStack src, String name, String field, boolean value) {
        Working w = ensureDraft(src, name);
        if (w == null) return 0;

        switch (field) {
            case "require-permission" -> w.pool.requirePermission = value;
            case "allow-chat" -> w.pool.allowChatInside = value;
            case "allow-exit" -> w.pool.allowEnterExitFreely = value;
        }
        src.sendSuccess(() -> MessagesUtil.msg("afkpool.setup.flip",
                Map.of("field", field, "value", value, "name", name)), false);
        return 1;
    }

    private int rewardAdd(CommandSourceStack src, String name, String trackId, int everySeconds) {
        Working w = ensureDraft(src, name);
        if (w == null) return 0;
        var tr = findOrCreateTrack(w.pool, trackId);
        tr.id = trackId; tr.everySeconds = everySeconds;
        src.sendSuccess(() -> MessagesUtil.msg("afkpool.setup.reward.add",
                Map.of("track", trackId, "sec", everySeconds, "name", name)), false);
        return 1;
    }

    private int rewardAddCmd(CommandSourceStack src, String name, String trackId, String cmd) {
        Working w = ensureDraft(src, name);
        if (w == null) return 0;
        var tr = findOrCreateTrack(w.pool, trackId);
        if (tr.commands == null) tr.commands = new ArrayList<>();
        tr.commands.add(cmd);
        src.sendSuccess(() -> MessagesUtil.msg("afkpool.setup.reward.add_cmd",
                Map.of("track", trackId, "cmd", cmd)), false);
        return 1;
    }

    private int rewardAddItem(CommandSourceStack src, String name, String trackId, String material, int amount, String nbtJson) {
        Working w = ensureDraft(src, name);
        if (w == null) return 0;
        var tr = findOrCreateTrack(w.pool, trackId);
        if (tr.items == null) tr.items = new ArrayList<>();
        MEConfig.ItemSpec it = new MEConfig.ItemSpec();
        it.type = material; it.amount = amount; it.nbt = (nbtJson == null || nbtJson.isBlank()) ? "{}" : nbtJson;
        tr.items.add(it);
        src.sendSuccess(() -> MessagesUtil.msg("afkpool.setup.reward.add_item",
                Map.of("track", trackId, "mat", material, "amt", amount)), false);
        return 1;
    }

    private int save(CommandSourceStack src, String name) {
        ServerPlayer p = src.getPlayer();
        if (p == null) { src.sendFailure(MessagesUtil.msg("common.players_only")); return 0; }

        Working w = drafts.get(p.getUUID());
        if (w == null || !w.name.equalsIgnoreCase(name)) {
            src.sendFailure(MessagesUtil.msg("afkpool.setup.no_draft", Map.of("name", name)));
            return 0;
        }

        if (w.pos1Tmp != null && w.pos2Tmp != null) {
            int minX = Math.min(w.pos1Tmp.getX(), w.pos2Tmp.getX());
            int minY = Math.min(w.pos1Tmp.getY(), w.pos2Tmp.getY());
            int minZ = Math.min(w.pos1Tmp.getZ(), w.pos2Tmp.getZ());
            int maxX = Math.max(w.pos1Tmp.getX(), w.pos2Tmp.getX());
            int maxY = Math.max(w.pos1Tmp.getY(), w.pos2Tmp.getY());
            int maxZ = Math.max(w.pos1Tmp.getZ(), w.pos2Tmp.getZ());

            if (w.pool.region == null) w.pool.region = new MEConfig.PoolBox();
            w.pool.region.world = p.level().dimension().location().toString();
            w.pool.region.min = new MEConfig.Vec3i(minX, minY, minZ);
            w.pool.region.max = new MEConfig.Vec3i(maxX, maxY, maxZ);
        }

        store.put(name, w.pool);

        config.afk.pools.clear();
        config.afk.pools.putAll(store.viewAll());
        afkService.reloadPools();

        drafts.remove(p.getUUID());
        src.sendSuccess(() -> MessagesUtil.msg("afkpool.setup.saved", Map.of("name", name)), true);
        return 1;
    }

    private Working ensureDraft(CommandSourceStack src, String name) {
        ServerPlayer p = src.getPlayer();
        if (p == null) { src.sendFailure(MessagesUtil.msg("common.players_only")); return null; }
        MEConfig.AfkPool base = store.get(name).orElse(config.afk.pools.get(name));
        Working w = drafts.computeIfAbsent(p.getUUID(), id -> new Working(name, base));
        if (!w.name.equalsIgnoreCase(name)) {
            w = new Working(name, base);
            drafts.put(p.getUUID(), w);
        }
        return w;
    }

    private MEConfig.AfkReward findOrCreateTrack(MEConfig.AfkPool pool, String trackId) {
        if (pool.rewards == null) pool.rewards = new ArrayList<>();
        for (MEConfig.AfkReward r : pool.rewards) if (r.id.equalsIgnoreCase(trackId)) return r;
        MEConfig.AfkReward r = new MEConfig.AfkReward();
        r.id = trackId;
        pool.rewards.add(r);
        return r;
    }
}
