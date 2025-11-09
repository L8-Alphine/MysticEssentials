package com.alphine.mysticessentials.commands.admin;

import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.util.MessagesUtil; // ← add
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.Map;

public class KillCmd {

    public void register(CommandDispatcher<CommandSourceStack> d) {
        var base = Commands.literal("kill")
                .requires(src -> Perms.has(src, PermNodes.KILL_USE, 2))

                .executes(ctx -> killSelf(ctx.getSource()))

                .then(Commands.literal("players")
                        .requires(src -> Perms.has(src, PermNodes.KILL_PLAYERS, 2))
                        .executes(ctx -> killGroup(ctx.getSource(), Group.PLAYERS, null))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 100000))
                                .executes(ctx -> killGroup(ctx.getSource(), Group.PLAYERS, IntegerArgumentType.getInteger(ctx, "radius")))
                        )
                )
                .then(Commands.literal("mobs")
                        .requires(src -> Perms.has(src, PermNodes.KILL_MOBS, 2))
                        .executes(ctx -> killGroup(ctx.getSource(), Group.MOBS, null))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 100000))
                                .executes(ctx -> killGroup(ctx.getSource(), Group.MOBS, IntegerArgumentType.getInteger(ctx, "radius")))
                        )
                )
                .then(Commands.literal("entities")
                        .requires(src -> Perms.has(src, PermNodes.KILL_ENTITIES, 2))
                        .executes(ctx -> killGroup(ctx.getSource(), Group.ENTITIES, null))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 100000))
                                .executes(ctx -> killGroup(ctx.getSource(), Group.ENTITIES, IntegerArgumentType.getInteger(ctx, "radius")))
                        )
                )
                .then(Commands.literal("all")
                        .requires(src -> Perms.has(src, PermNodes.KILL_ALL, 2))
                        .executes(ctx -> killGroup(ctx.getSource(), Group.ALL, null))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 100000))
                                .executes(ctx -> killGroup(ctx.getSource(), Group.ALL, IntegerArgumentType.getInteger(ctx, "radius")))
                        )
                );

        d.register(base);
    }

    private enum Group { PLAYERS, MOBS, ENTITIES, ALL }

    private int killSelf(CommandSourceStack src) {
        ServerPlayer p = src.getPlayer();
        if (p == null) {
            src.sendFailure(MessagesUtil.msg("common.players_only"));
            return 0;
        }
        if (!Perms.has(src, PermNodes.KILL_PLAYERS, 2)) {
            src.sendFailure(MessagesUtil.msg("kill.no_perm", Map.of("perm", PermNodes.KILL_PLAYERS)));
            return 0;
        }
        if (Perms.has(p, PermNodes.KILL_EXEMPT, 2)) {
            src.sendFailure(MessagesUtil.msg("kill.self_exempt"));
            return 0;
        }
        p.kill();
        src.sendSuccess(() -> MessagesUtil.msg("kill.self_ok"), true);
        return 1;
    }

    private int killGroup(CommandSourceStack src, Group group, Integer radiusOpt) {
        ServerPlayer executor = src.getPlayer();
        ServerLevel level = src.getLevel();
        if (level == null) {
            src.sendFailure(MessagesUtil.msg("kill.no_world"));
            return 0;
        }

        AABB box;
        if (radiusOpt == null) {
            box = new AABB(
                    Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                    Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY
            );
        } else {
            if (executor == null) {
                src.sendFailure(MessagesUtil.msg("kill.radius_needs_player"));
                return 0;
            }
            var pos = executor.position();
            box = new AABB(pos, pos).inflate(radiusOpt);
        }

        Predicate<Entity> filter = switch (group) {
            case PLAYERS  -> e -> e instanceof ServerPlayer;
            case MOBS     -> e -> e instanceof LivingEntity && !(e instanceof ServerPlayer);
            case ENTITIES -> e -> !(e instanceof ServerPlayer);
            case ALL      -> e -> true;
        };

        int count = 0;
        List<Entity> targets = level.getEntities((Entity) null, box, filter).stream().toList();

        for (Entity e : targets) {
            if (e instanceof ServerPlayer sp) {
                if (Perms.has(sp, PermNodes.KILL_EXEMPT, 2)) continue;
                if (group == Group.ENTITIES) continue;
                sp.kill();
                count++;
            } else if (e instanceof LivingEntity le) {
                le.kill();
                count++;
            } else {
                e.discard();
                count++;
            }
        }

        String scopeKey = switch (group) {
            case PLAYERS -> "kill.scope.players";
            case MOBS -> "kill.scope.mobs";
            case ENTITIES -> "kill.scope.entities";
            case ALL -> "kill.scope.all";
        };
        String area = (radiusOpt == null) ? "world" : String.valueOf(radiusOpt);

        int finalCount = count;
        src.sendSuccess(() -> MessagesUtil.msg("kill.group_ok", Map.of(
                "count", finalCount,
                "scope", Objects.requireNonNull(MessagesUtil.getRaw(scopeKey)),
                "area", area
        )), true);
        return count > 0 ? 1 : 0;
    }
}
