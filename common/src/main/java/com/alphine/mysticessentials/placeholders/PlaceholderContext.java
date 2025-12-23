package com.alphine.mysticessentials.placeholders;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

public record PlaceholderContext(
        MinecraftServer server,
        @Nullable ServerPlayer sender,
        @Nullable ServerPlayer viewer
) {
    public static PlaceholderContext of(MinecraftServer server, @Nullable ServerPlayer sender) {
        return new PlaceholderContext(server, sender, null);
    }

    public PlaceholderContext withViewer(@Nullable ServerPlayer viewer) {
        return new PlaceholderContext(server, sender, viewer);
    }
}
