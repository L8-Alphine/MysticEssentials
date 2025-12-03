package com.alphine.mysticessentials.chat.placeholder;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.minecraft.server.level.ServerPlayer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LuckPermsPlaceholders {

    private static LuckPerms LP;
    private static boolean triedInit = false;
    private static boolean loggedMissing = false;

    // %luckperms_meta_<key>% and %lp_meta_<key>%
    private static final Pattern META_PATTERN =
            Pattern.compile("%(?:luckperms|lp)_meta_([a-zA-Z0-9_.-]+)%");

    private LuckPermsPlaceholders() {}

    private static LuckPerms luckPerms() {
        if (triedInit) return LP;
        triedInit = true;
        try {
            LP = LuckPermsProvider.get();
            System.out.println("[MysticEssentials] LuckPerms API found, enabling LP placeholders.");
        } catch (IllegalStateException e) {
            LP = null;
            if (!loggedMissing) {
                System.out.println("[MysticEssentials] LuckPerms not found – LP placeholders will be left as-is.");
                loggedMissing = true;
            }
        }
        return LP;
    }

    public static String apply(ServerPlayer player, String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        LuckPerms lp = luckPerms();
        if (lp == null) {
            // No LP installed → leave placeholders as-is (but at least don’t crash)
            return input;
        }

        final User user;
        try {
            user = lp.getPlayerAdapter(ServerPlayer.class).getUser(player);
        } catch (Exception ex) {
            if (!loggedMissing) {
                System.out.println("[MysticEssentials] Failed to fetch LuckPerms user for "
                        + player.getGameProfile().getName() + ": " + ex.getMessage());
                loggedMissing = true;
            }
            return input;
        }

        CachedMetaData meta = user.getCachedData().getMetaData();

        String prefix = meta.getPrefix();
        String suffix = meta.getSuffix();
        String primaryGroup = user.getPrimaryGroup();

        Group group = lp.getGroupManager().getGroup(primaryGroup);
        String groupDisplay = (group != null && group.getDisplayName() != null)
                ? group.getDisplayName()
                : primaryGroup;

        String result = input;

        // --- MAIN placeholders ---
        result = result.replace("%luckperms_prefix%", safe(prefix));
        result = result.replace("%luckperms_suffix%", safe(suffix));

        // Short aliases
        result = result.replace("%lp_prefix%", safe(prefix));
        result = result.replace("%lp_suffix%", safe(suffix));

        // Primary group
        result = result.replace("%luckperms_primary_group%", safe(primaryGroup));
        result = result.replace("%luckperms_group_display%", safe(groupDisplay));
        result = result.replace("%lp_primary_group%", safe(primaryGroup));
        result = result.replace("%lp_group_display%", safe(groupDisplay));

        // %luckperms_meta_key% / %lp_meta_key%
        Matcher m = META_PATTERN.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            String value = meta.getMetaValue(key);
            m.appendReplacement(sb, Matcher.quoteReplacement(safe(value)));
        }
        m.appendTail(sb);
        result = sb.toString();

        return result;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
