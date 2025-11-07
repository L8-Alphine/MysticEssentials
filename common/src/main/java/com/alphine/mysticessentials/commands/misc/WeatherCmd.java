package com.alphine.mysticessentials.commands.misc;

import com.mojang.brigadier.CommandDispatcher;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

public class WeatherCmd {
    public void register(CommandDispatcher<CommandSourceStack> d){
        d.register(net.minecraft.commands.Commands.literal("sun")
                .requires(src -> Perms.has(src, PermNodes.CLEAR_WEATHER_USE, 2))
                .executes(ctx -> { ctx.getSource().getLevel().setWeatherParameters(6000, 0, false, false);
                    ctx.getSource().sendSuccess(() -> Component.literal("§eWeather cleared."), false); return 1; })
        );
        d.register(net.minecraft.commands.Commands.literal("rain")
                .requires(src -> Perms.has(src, PermNodes.RAIN_USE, 2))
                .executes(ctx -> { ctx.getSource().getLevel().setWeatherParameters(0, 6000, true, false);
                    ctx.getSource().sendSuccess(() -> Component.literal("§eRain started."), false); return 1; })
        );
        d.register(net.minecraft.commands.Commands.literal("storm")
                .requires(src -> Perms.has(src, PermNodes.WEATHER_SET, 2))
                .executes(ctx -> { ctx.getSource().getLevel().setWeatherParameters(0, 6000, true, true);
                    ctx.getSource().sendSuccess(() -> Component.literal("§eThunderstorm started."), false); return 1; })
        );
    }
}
