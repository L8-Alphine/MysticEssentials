package com.alphine.mysticessentials.commands.misc;

import com.mojang.brigadier.CommandDispatcher;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.storage.PlayerDataStore;
import com.alphine.mysticessentials.util.Teleports;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Teleports player to block they're looking at (limited range).
 */
public class JumpCmd {
    private final PlayerDataStore pdata;
    public JumpCmd(PlayerDataStore pdata){ this.pdata=pdata; }

    public void register(CommandDispatcher<CommandSourceStack> d){
        d.register(net.minecraft.commands.Commands.literal("jump")
                .requires(src -> Perms.has(src, PermNodes.JUMP_USE, 2))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    double range = 100.0;
                    Vec3 eye = p.getEyePosition();
                    Vec3 look = p.getLookAngle();
                    Vec3 end = eye.add(look.scale(range));
                    HitResult hit = p.level().clip(new net.minecraft.world.level.ClipContext(
                            eye, end, net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
                    if(hit == null || hit.getType() != HitResult.Type.BLOCK){
                        p.displayClientMessage(Component.literal("§cNo block in sight within "+(int)range+" blocks."), false);
                        return 0;
                    }
                    Vec3 pos = hit.getLocation();
                    Teleports.pushBackAndTeleport(p, p.serverLevel(), pos.x, pos.y+1, pos.z, p.getYRot(), p.getXRot(), pdata);
                    p.displayClientMessage(Component.literal("§aTeleported ahead."), false);
                    return 1;
                })
        );
    }
}
