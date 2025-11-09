package com.alphine.mysticessentials.commands.misc;

import com.mojang.brigadier.CommandDispatcher;
import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.storage.PlayerDataStore;
import com.alphine.mysticessentials.util.MessagesUtil;
import com.alphine.mysticessentials.util.Teleports;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/** Teleports player to block they're looking at (limited range). */
public class JumpCmd {
    private final PlayerDataStore pdata;
    public JumpCmd(PlayerDataStore pdata){ this.pdata=pdata; }

    public void register(CommandDispatcher<CommandSourceStack> d){
        d.register(net.minecraft.commands.Commands.literal("jump")
                .requires(src -> Perms.has(src, PermNodes.JUMP_USE, 2))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    double range = (MEConfig.INSTANCE != null && MEConfig.INSTANCE.limits != null)
                            ? MEConfig.INSTANCE.limits.maxJumpDistance
                            : 100.0;

                    Vec3 eye = p.getEyePosition();
                    Vec3 end = eye.add(p.getLookAngle().scale(range));
                    HitResult hit = p.level().clip(new net.minecraft.world.level.ClipContext(
                            eye, end,
                            net.minecraft.world.level.ClipContext.Block.OUTLINE,
                            net.minecraft.world.level.ClipContext.Fluid.NONE, p));

                    if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
                        p.displayClientMessage(MessagesUtil.msg("jump.no_block", Map.of("range", (int)range)), false);
                        return 0;
                    }

                    Vec3 pos = hit.getLocation();
                    Teleports.pushBackAndTeleport(p, p.serverLevel(), pos.x, pos.y + 1, pos.z, p.getYRot(), p.getXRot(), pdata);
                    p.displayClientMessage(MessagesUtil.msg("jump.ok"), false);
                    return 1;
                })
        );
    }
}
