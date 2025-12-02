package com.alphine.mysticessentials.commands.chat;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.chat.ChatPermissions;
import com.alphine.mysticessentials.config.ChatConfigManager;
import com.alphine.mysticessentials.config.ChatConfigManager.ChannelsConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class ChannelCmd {

    private static final SuggestionProvider<CommandSourceStack> CHANNEL_SUGGESTER =
            ChannelCmd::suggestChannels;

    public void register(CommandDispatcher<CommandSourceStack> d) {

        // /channel <id>
        var root = Commands.literal("channel")
                .requires(src -> src.getEntity() instanceof ServerPlayer) // player only
                .then(Commands.argument("id", StringArgumentType.string())
                        .suggests(CHANNEL_SUGGESTER)
                        .executes(this::switchChannel));

        d.register(root);
        d.register(Commands.literal("ch").redirect(root.build()));

        // Convenience per-channel aliases (toggle only)
        registerAlias(d, "global", "global");
        registerAlias(d, "local",  "local");
        registerAlias(d, "staffchat", "staff");
        registerAlias(d, "adminchat", "admin");

        // shorter aliases
        registerAlias(d, "g",  "global");
        registerAlias(d, "l",  "local");
        registerAlias(d, "sc", "staff");
        registerAlias(d, "ac", "admin");
    }

    private static void registerAlias(CommandDispatcher<CommandSourceStack> d,
                                      String literal,
                                      String channelId) {
        d.register(Commands.literal(literal)
                .requires(src -> {
                    if (!(src.getEntity() instanceof ServerPlayer sp)) return false;
                    var commonPlayer = MysticEssentialsCommon.get().wrapPlayer(sp);
                    return ChatPermissions.canSend(commonPlayer, channelId);
                })
                .executes(ctx -> switchTo(ctx, channelId)));
    }

    private int switchChannel(CommandContext<CommandSourceStack> ctx) {
        var entity = ctx.getSource().getEntity();
        if (!(entity instanceof ServerPlayer)) {
            return 0;
        }
        String id = StringArgumentType.getString(ctx, "id").toLowerCase(Locale.ROOT);
        return switchTo(ctx, id);
    }

    private static int switchTo(CommandContext<CommandSourceStack> ctx, String channelId) {
        var entity = ctx.getSource().getEntity();
        if (!(entity instanceof ServerPlayer p)) {
            return 0;
        }

        // validate channel exists
        ChannelsConfig cfg = ChatConfigManager.CHANNELS;
        if (cfg == null || cfg.channels == null || !cfg.channels.containsKey(channelId)) {
            p.displayClientMessage(Component.literal("§cUnknown channel: §f" + channelId), false);
            return 0;
        }

        var commonPlayer = MysticEssentialsCommon.get().wrapPlayer(p);
        if (!ChatPermissions.canSend(commonPlayer, channelId)) {
            p.displayClientMessage(Component.literal("§cYou do not have permission to talk in that channel."), false);
            return 0;
        }

        // Store active channel in your chat state service
        MysticEssentialsCommon.get().chatState.setActiveChannel(p.getUUID(), channelId);

        p.displayClientMessage(Component.literal("§aYou are now talking in §f" + channelId + " §achat."), false);
        return 1;
    }

    // Suggestions from channels.json keys
    private static CompletableFuture<Suggestions> suggestChannels(
            CommandContext<CommandSourceStack> ctx,
            SuggestionsBuilder builder
    ) {
        ChannelsConfig cfg = ChatConfigManager.CHANNELS;
        if (cfg != null && cfg.channels != null) {
            return SharedSuggestionProvider.suggest(cfg.channels.keySet(), builder);
        }
        return builder.buildFuture();
    }
}
