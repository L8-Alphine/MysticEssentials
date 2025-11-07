package com.alphine.mysticessentials.commands.misc;

import com.mojang.brigadier.CommandDispatcher;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Toggle invulnerability (god mode) per player.
 */
public class GodCmd {
    // simple runtime toggle; you could persist this in PlayerDataStore later
    private final java.util.Set<java.util.UUID> godPlayers = new java.util.HashSet<>();

    public GodCmd(Object ignored){}

    public void register(CommandDispatcher<CommandSourceStack> d){
        d.register(net.minecraft.commands.Commands.literal("god")
                .requires(src -> Perms.has(src, PermNodes.GOD_USE, 2))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    if(godPlayers.contains(p.getUUID())){
                        godPlayers.remove(p.getUUID());
                        p.displayClientMessage(Component.literal("§cGod mode disabled."), false);
                    } else {
                        godPlayers.add(p.getUUID());
                        p.displayClientMessage(Component.literal("§aGod mode enabled."), false);
                    }
                    return 1;
                })
        );
    }

    /** External check (damage listener hook) */
    public boolean isGod(java.util.UUID uuid){ return godPlayers.contains(uuid); }
}
