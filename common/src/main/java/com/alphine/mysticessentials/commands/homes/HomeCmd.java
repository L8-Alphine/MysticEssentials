package com.alphine.mysticessentials.commands.homes;

import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.storage.HomesStore;
import com.alphine.mysticessentials.teleport.TeleportExecutor;
import com.alphine.mysticessentials.util.MessagesUtil;
import com.alphine.mysticessentials.util.Teleports;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
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
    private final TeleportExecutor exec;
    private final com.alphine.mysticessentials.storage.PlayerDataStore pdata;

    public HomeCmd(HomesStore s, TeleportExecutor exec,
                   com.alphine.mysticessentials.storage.PlayerDataStore pdata) {
        this.store = s;
        this.exec = exec;
        this.pdata = pdata;
    }

    private SuggestionProvider<CommandSourceStack> homeSuggest() {
        return (ctx, b) -> {
            try {
                Player target;
                // If a "player" arg is present, suggest that player's homes; else, executor's homes.
                if (ctx.getNodes().stream().anyMatch(n -> "player".equals(n.getNode().getName()))) {
                    target = EntityArgument.getPlayer(ctx, "player");
                } else {
                    target = ctx.getSource().getPlayerOrException();
                }
                for (var n : store.names(target.getUUID())) b.suggest(n);
            } catch (Exception ignored) {}
            return b.buildFuture();
        };
    }

    private static boolean featureOn() {
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

        final ServerPlayer actor;
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
                    false
            );
            return 0;
        }
        var h = opt.get();

        exec.runTeleport(ctx.getSource().getServer(), actor, ctx.getSource(), "home", () -> {
            ResourceLocation id = ResourceLocation.tryParse(h.dim);
            if (id == null) {
                actor.displayClientMessage(
                        MessagesUtil.msg("tp.bad_dimension", Map.of("id", h.dim)), false);
                return false;
            }

            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
            ServerLevel level = actor.getServer().getLevel(key);
            if (level == null) {
                actor.displayClientMessage(
                        MessagesUtil.msg("tp.world_missing", Map.of("id", h.dim)), false);
                return false;
            }

            Teleports.pushBackAndTeleport(actor, level, h.x, h.y, h.z, h.yaw, h.pitch, pdata);
            return true;
        });

        return 1;
    }
}
