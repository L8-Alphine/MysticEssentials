package com.alphine.mysticessentials.placeholders;

import net.minecraft.server.level.ServerPlayer;

public final class BuiltinPlaceholders {
    private BuiltinPlaceholders() {}

    public static void registerAll(PlaceholderService svc) {
        svc.register((key, ctx) -> {
            ServerPlayer s = ctx.sender();
            return switch (key.toLowerCase()) {
                case "player", "sender" -> s != null ? s.getGameProfile().getName() : "";
                case "uuid" -> s != null ? s.getUUID().toString() : "";
                case "world", "dimension" -> s != null ? s.serverLevel().dimension().location().toString() : "";
                case "x" -> s != null ? String.format(java.util.Locale.ROOT, "%.2f", s.getX()) : "0";
                case "y" -> s != null ? String.format(java.util.Locale.ROOT, "%.2f", s.getY()) : "0";
                case "z" -> s != null ? String.format(java.util.Locale.ROOT, "%.2f", s.getZ()) : "0";
                case "online" -> Integer.toString(ctx.server().getPlayerList().getPlayerCount());
                default -> null;
            };
        });

        // viewer placeholders (optional)
        svc.register((key, ctx) -> {
            var v = ctx.viewer();
            if (v == null) return null;
            return switch (key.toLowerCase()) {
                case "viewer" -> v.getGameProfile().getName();
                case "viewer_uuid" -> v.getUUID().toString();
                default -> null;
            };
        });
    }
}
