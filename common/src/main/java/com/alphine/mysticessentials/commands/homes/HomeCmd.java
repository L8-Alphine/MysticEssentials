package com.alphine.mysticessentials.commands.homes;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.storage.HomesStore;
import com.alphine.mysticessentials.teleport.CooldownManager;
import com.alphine.mysticessentials.teleport.WarmupManager;
import com.alphine.mysticessentials.util.Teleports;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.perm.Bypass;
import net.minecraft.commands.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.Level;

public class HomeCmd {
    private final HomesStore store;
    private final CooldownManager cooldowns;
    private final WarmupManager warmups;
    private final com.alphine.mysticessentials.storage.PlayerDataStore pdata;

    public HomeCmd(HomesStore s, CooldownManager c, WarmupManager w, com.alphine.mysticessentials.storage.PlayerDataStore pdata){
        this.store=s; this.cooldowns=c; this.warmups=w; this.pdata=pdata;
    }

    private static final SuggestionProvider<CommandSourceStack> HOME_SUGGEST = (ctx, b) -> {
        try {
            var p = ctx.getSource().getPlayerOrException();
            for (var n : com.alphine.mysticessentials.MysticEssentialsCommon.get().homes.names(p.getUUID())) b.suggest(n);
        } catch (Exception ignored) {}
        return b.buildFuture();
    };

    private static boolean featureOn(){
        var c = MEConfig.INSTANCE; return c == null || c.features == null || c.features.enableHomesWarpsTP;
    }

    public void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("home")
                .requires(src -> Perms.has(src, PermNodes.HOME_USE, 2))
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(HOME_SUGGEST)
                        .executes(ctx -> {
                            if (!featureOn()) { ctx.getSource().sendFailure(Component.literal("§cTeleport features are disabled by config.")); return 0; }

                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String name = StringArgumentType.getString(ctx, "name");

                            var opt = store.get(p.getUUID(), name);
                            if (opt.isEmpty()) { p.displayClientMessage(Component.literal("§cNo home named §e"+name), false); return 0; }
                            var h = opt.get();

                            long now = System.currentTimeMillis();
                            if (!Bypass.cooldown(ctx.getSource()) && cooldowns.getDefaultSeconds("home") > 0
                                    && !cooldowns.checkAndStampDefault(p.getUUID(), "home", now)) {
                                long rem = cooldowns.remaining(p.getUUID(), "home", now);
                                p.displayClientMessage(Component.literal("§cCooldown: §e"+rem+"s"), false);
                                return 0;
                            }

                            int warmSec = MEConfig.INSTANCE != null ? MEConfig.INSTANCE.getWarmup("home") : 0;
                            Runnable tp = () -> {
                                ResourceLocation id = ResourceLocation.tryParse(h.dim); // "namespace:path"
                                if (id == null) {
                                    p.displayClientMessage(Component.literal("§cBad dimension id: " + h.dim), false);
                                    return;
                                }
                                ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
                                ServerLevel level = p.getServer().getLevel(key);
                                if (level == null) {
                                    p.displayClientMessage(Component.literal("§cWorld missing: " + h.dim), false);
                                    return;
                                }
                                Teleports.pushBackAndTeleport(p, level, h.x, h.y, h.z, h.yaw, h.pitch, pdata);
                            };

                            if (Bypass.warmup(ctx.getSource())) tp.run();
                            else warmups.startOrBypass(ctx.getSource().getServer(), p, warmSec, tp);
                            return 1;
                        })
                )
        );
    }
}
