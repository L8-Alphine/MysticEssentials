package com.alphine.mysticessentials.commands.misc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.util.MessagesUtil;
import net.minecraft.commands.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.Map;

public class GmCmd {
    public void register(CommandDispatcher<CommandSourceStack> d){
        d.register(Commands.literal("gm")
                .requires(src -> Perms.has(src, PermNodes.CREATIVE_USE, 2))
                .then(Commands.argument("mode", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String arg = StringArgumentType.getString(ctx,"mode").toLowerCase();
                            GameType type = switch (arg) {
                                case "0","s","survival"      -> GameType.SURVIVAL;
                                case "1","c","creative"      -> GameType.CREATIVE;
                                case "2","a","adventure"     -> GameType.ADVENTURE;
                                case "3","sp","spec","spectator" -> GameType.SPECTATOR;
                                default -> null;
                            };
                            if (type == null) {
                                p.displayClientMessage(MessagesUtil.msg("gm.unknown"), false);
                                return 0;
                            }
                            p.setGameMode(type);
                            p.displayClientMessage(MessagesUtil.msg("gm.set", Map.of("mode", type.getName())), false);
                            return 1;
                        })
                )
        );
    }
}
