package com.alphine.mysticessentials.placeholders;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.user.User;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public final class LuckPermsBuiltinProvider {

    private static LuckPerms LP;
    private static boolean triedInit = false;
    private static boolean loggedMissing = false;

    private LuckPermsBuiltinProvider() {}

    public static void registerAll(PlaceholderService svc) {
        svc.register((rawKey, ctx) -> {
            if (rawKey == null || rawKey.isEmpty()) return null;
            String key = rawKey.toLowerCase(Locale.ROOT);

            if (!key.equals("rank_prefix") && !key.equals("rank_suffix")) {
                return null;
            }

            ServerPlayer viewer = ctx.viewer();
            ServerPlayer sender = ctx.sender();
            ServerPlayer target = viewer != null ? viewer : sender;
            if (target == null) return "";

            CachedMetaData meta = getMeta(target);
            if (meta == null) return "";

            return switch (key) {
                case "rank_prefix" -> safe(meta.getPrefix());
                case "rank_suffix" -> safe(meta.getSuffix());
                default -> null;
            };
        });
    }

    private static @Nullable CachedMetaData getMeta(ServerPlayer player) {
        LuckPerms lp = luckPerms();
        if (lp == null) return null;

        try {
            User user = lp.getPlayerAdapter(ServerPlayer.class).getUser(player);
            return user.getCachedData().getMetaData();
        } catch (Exception ex) {
            if (!loggedMissing) {
                System.out.println("[MysticEssentials] Failed to fetch LuckPerms meta for "
                        + player.getGameProfile().getName() + ": " + ex.getMessage());
                loggedMissing = true;
            }
            return null;
        }
    }

    private static LuckPerms luckPerms() {
        if (triedInit) return LP;
        triedInit = true;
        try {
            LP = LuckPermsProvider.get();
            System.out.println("[MysticEssentials] LuckPerms API found, enabling rank_* placeholders.");
        } catch (IllegalStateException e) {
            LP = null;
            if (!loggedMissing) {
                System.out.println("[MysticEssentials] LuckPerms not found – rank_* placeholders will be empty.");
                loggedMissing = true;
            }
        }
        return LP;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
