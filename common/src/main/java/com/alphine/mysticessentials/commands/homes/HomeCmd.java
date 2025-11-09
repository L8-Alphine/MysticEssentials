package com.alphine.mysticessentials.commands.homes;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.storage.HomesStore;
import com.alphine.mysticessentials.teleport.CooldownManager;
import com.alphine.mysticessentials.teleport.WarmupManager;
import com.alphine.mysticessentials.util.MessagesUtil;
import com.alphine.mysticessentials.util.Teleports;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.perm.Bypass;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.Map;

public class HomeCmd {
    private final HomesStore store;
    private final CooldownManager cooldowns;
    private final WarmupManager warmups;
    private final com.alphine.mysticessentials.storage.PlayerDataStore pdata;

    public HomeCmd(HomesStore s, CooldownManager c, WarmupManager w,
                   com.alphine.mysticessentials.storage.PlayerDataStore pdata){
        this.store = s; this.cooldowns = c; this.warmups = w; this.pdata = pdata;
    }

    private SuggestionProvider<CommandSourceStack> homeSuggest() {
        return (ctx, b) -> {
            try {
                Player target;
                // If a "player" arg is present, suggest that player's homes; else, the executor's homes.
                if (ctx.getNodes().size() >= 2 &&
                        ctx.getNodes().stream().anyMatch(n -> "player".equals(n.getNode().getName()))) {
                    target = EntityArgument.getPlayer(ctx, "player");
                } else {
                    target = ctx.getSource().getPlayerOrException();
                }
                for (var n : store.names(target.getUUID())) b.suggest(n);
            } catch (Exception ignored) {}
            return b.buildFuture();
        };
    }

    private static boolean featureOn(){
        var c = MEConfig.INSTANCE;
        return c == null || c.features == null || c.features.enableHomesWarpsTP;
    }

    public void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("home")
                .requires(src -> Perms.has(src, PermNodes.HOME_USE, 0))
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(homeSuggest())
                        // /home <name>  -> go to your own home
                        .executes(ctx ->
                                tpToHome(ctx, ctx.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(ctx, "name")))
                        // /home <name> <player>  -> go to another player's home (requires perm)
                        .then(Commands.argument("player", EntityArgument.player())
                                .requires(src -> Perms.has(src, PermNodes.HOME_OTHERS, 2))
                                .suggests(homeSuggest())
                                .executes(ctx -> {
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                    String name = StringArgumentType.getString(ctx, "name");
                                    return tpToHome(ctx, target, name);
                                }))
                )
        );
    }

    /** Teleports the executor (actor) to the specified player's (target) home. */
    private int tpToHome(CommandContext<CommandSourceStack> ctx, ServerPlayer target, String name) {
        if (!featureOn()) {
            ctx.getSource().sendFailure(MessagesUtil.msg("feature.disabled.teleport"));
            return 0;
        }

        ServerPlayer actor;
        try {
            actor = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return 0;
        }

        var opt = store.get(target.getUUID(), name);
        if (opt.isEmpty()) {
            actor.displayClientMessage(
                    MessagesUtil.msg("home.no_such_for",
                            Map.of("name", name, "player", target.getGameProfile().getName())),
                    false);
            return 0;
        }
        var h = opt.get();

        // Cooldown (bypass allowed)
        long now = System.currentTimeMillis();
        if (!Bypass.cooldown(ctx.getSource())
                && cooldowns.getDefaultSeconds("home") > 0
                && !cooldowns.checkAndStampDefault(actor.getUUID(), "home", now)) {
            long rem = cooldowns.remaining(actor.getUUID(), "home", now);
            actor.displayClientMessage(
                    MessagesUtil.msg("cooldown.wait", Map.of("seconds", rem)),
                    false);
            return 0;
        }

        // Warmup seconds from config
        int warmSec = (MEConfig.INSTANCE != null) ? MEConfig.INSTANCE.getWarmup("home") : 0;

        // Teleport action
        Runnable tp = () -> {
            ResourceLocation id = ResourceLocation.tryParse(h.dim);
            if (id == null) {
                actor.displayClientMessage(
                        MessagesUtil.msg("tp.bad_dimension", Map.of("id", h.dim)), false);
                return;
            }
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
            ServerLevel level = actor.getServer().getLevel(key);
            if (level == null) {
                actor.displayClientMessage(
                        MessagesUtil.msg("tp.world_missing", Map.of("id", h.dim)), false);
                return;
            }
            Teleports.pushBackAndTeleport(actor, level, h.x, h.y, h.z, h.yaw, h.pitch, pdata);
        };

        // Warmup manager handles bypass internally
        warmups.startOrBypass(ctx.getSource().getServer(), actor, warmSec, tp);
        return 1;
    }
}
