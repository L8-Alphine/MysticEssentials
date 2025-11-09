package com.alphine.mysticessentials.commands.misc;

import com.mojang.brigadier.CommandDispatcher;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.util.MessagesUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Toggle invulnerability (god mode) per player. */
public class GodCmd {
    private final Set<UUID> godPlayers = new HashSet<>();

    public GodCmd(Object ignored){}

    public void register(CommandDispatcher<CommandSourceStack> d){
        d.register(net.minecraft.commands.Commands.literal("god")
                .requires(src -> Perms.has(src, PermNodes.GOD_USE, 2))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    if (godPlayers.remove(p.getUUID())) {
                        p.displayClientMessage(MessagesUtil.msg("god.disabled"), false);
                    } else {
                        godPlayers.add(p.getUUID());
                        p.displayClientMessage(MessagesUtil.msg("god.enabled"), false);
                    }
                    return 1;
                })
        );
    }

    /** External check (damage listener hook) */
    public boolean isGod(UUID uuid){ return godPlayers.contains(uuid); }
}
