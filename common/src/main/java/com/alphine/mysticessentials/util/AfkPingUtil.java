package com.alphine.mysticessentials.util;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

import java.util.UUID;
import java.util.regex.Pattern;

public final class AfkPingUtil {
    private AfkPingUtil(){}
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Call this after you’ve decided the chat message is allowed to send. */
    public static void handleChatMention(MinecraftServer server, ServerPlayer sender, String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) return;

        var common = MysticEssentialsCommon.get();
        var afk = common.afk;

        if (afk == null) return;

        // iterate online players and look for whole-word name hits (case-insensitive)
        for (ServerPlayer target : server.getPlayerList().getPlayers()) {
            if (target.getUUID().equals(sender.getUUID())) continue;

            String name = target.getGameProfile().getName();
            if (!containsWholeWordIgnoreCase(rawMessage, name)) continue;

            // if target is not AFK or exempt from AFK ping, skip
            if (!afk.isAfk(target.getUUID())) continue;
            if (Perms.has(target, PermNodes.AFK_MESSAGE_EXEMPT, 2)) continue;

            // choose message: see-custom requires sender permission
            String base = common.cfg.afk.defaultMessage == null ? "I'm currently AFK." : common.cfg.afk.defaultMessage;
            String msg = base;
            if (Perms.has(sender, PermNodes.AFK_MESSAGE_USE, 0)) {
                msg = afk.currentAfkMessage(target.getUUID()).orElse(base);
            }

            String out = afk.formatNotify(sender.getGameProfile().getName(), name, msg);
            var adv = MINI.deserialize(out);
            var comp = AdventureComponentBridge.advToNative(adv, sender.server.registryAccess());
            sender.displayClientMessage(comp, false);
            // Only one DM per message per target name occurrence; multiple names still send multiple DMs
        }
    }

    private static boolean containsWholeWordIgnoreCase(String haystack, String word) {
        String w = Pattern.quote(word);
        Pattern p = Pattern.compile("(?i)(^|\\W)" + w + "(\\W|$)");
        return p.matcher(haystack).find();
    }

}
