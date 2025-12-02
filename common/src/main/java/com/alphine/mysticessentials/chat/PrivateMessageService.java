package com.alphine.mysticessentials.chat;

import com.alphine.mysticessentials.chat.redis.RedisClientAdapter;
import com.alphine.mysticessentials.chat.redis.RedisIgnoreMessage;
import com.alphine.mysticessentials.chat.redis.RedisPrivateMessage;
import com.alphine.mysticessentials.config.ChatConfigManager;
import com.alphine.mysticessentials.config.MEConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.network.chat.Component.literal;

/**
 * Handles /msg, /reply, /ignore and social spy.
 *
 * Local behaviour is unchanged, but if Redis is configured,
 * private messages and ignore toggles are also published
 * so other servers in the cluster can mirror the state.
 */
public class PrivateMessageService {

    // last partner each player messaged (for /reply)
    private final Map<UUID, UUID> lastConversation = new ConcurrentHashMap<>();

    // social spy toggles
    private final Set<UUID> spies = ConcurrentHashMap.newKeySet();

    // ignore lists: owner -> set of ignored player UUIDs
    private final Map<UUID, Set<UUID>> ignoreLists = new ConcurrentHashMap<>();

    private static final MiniMessage MM = MiniMessage.miniMessage();

    // optional Redis adapter (wired by platform)
    private RedisClientAdapter redis;
    private final Gson gson = new GsonBuilder().create();

    public void setRedisAdapter(RedisClientAdapter adapter) {
        this.redis = adapter;
    }

    private boolean redisEnabled() {
        MEConfig cfg = MEConfig.INSTANCE;
        return cfg != null
                && cfg.chat != null
                && cfg.chat.redis != null
                && cfg.chat.redis.enabled
                && redis != null;
    }

    public void onQuit(ServerPlayer player) {
        UUID id = player.getUUID();
        lastConversation.remove(id);
        spies.remove(id);
        ignoreLists.remove(id);
    }

    /**
     * Toggle spy state for this player.
     *
     * @return new state (true = enabled, false = disabled)
     */
    public boolean toggleSpy(ServerPlayer staff) {
        UUID id = staff.getUUID();
        if (spies.remove(id)) {
            return false;
        } else {
            spies.add(id);
            return true;
        }
    }

    public boolean isSpy(ServerPlayer player) {
        return spies.contains(player.getUUID());
    }

    // ------------------------------------------------------------------------
    // Local send entry point (used by commands on this server)
    // ------------------------------------------------------------------------
    public void sendMessage(ServerPlayer sender, ServerPlayer target, String raw) {
        sendLocal(sender, target, raw, true, true);

        // Also publish to Redis so other servers can see/deliver
        if (redisEnabled()) {
            publishPm(sender, target, raw);
        }
    }

    // ------------------------------------------------------------------------
    // /reply
    // ------------------------------------------------------------------------
    public void reply(ServerPlayer sender, String raw) {
        ChatConfigManager.PrivateMessagesConfig cfg = ChatConfigManager.PRIVATE;
        if (cfg == null || !cfg.enabled) {
            sender.sendSystemMessage(literal("§cPrivate messages are disabled."));
            return;
        }

        UUID partnerId = lastConversation.get(sender.getUUID());
        if (partnerId == null) {
            sender.sendSystemMessage(literal("§cYou have nobody to reply to."));
            return;
        }

        ServerPlayer target = sender.server.getPlayerList().getPlayer(partnerId);
        if (target == null) {
            sender.sendSystemMessage(literal("§cThat player is no longer online on this server."));
            return;
        }

        sendMessage(sender, target, raw);
    }

    // ------------------------------------------------------------------------
    // Internal local send implementation
    // ------------------------------------------------------------------------
    private void sendLocal(ServerPlayer sender,
                           ServerPlayer target,
                           String raw,
                           boolean updateConversation,
                           boolean spyAndConsole) {

        ChatConfigManager.PrivateMessagesConfig cfg = ChatConfigManager.PRIVATE;
        if (cfg == null || !cfg.enabled) {
            sender.sendSystemMessage(literal("§cPrivate messages are disabled."));
            return;
        }

        raw = raw.trim();
        if (raw.isEmpty()) {
            sender.sendSystemMessage(literal("§cYou must provide a message."));
            return;
        }

        if (sender.getUUID().equals(target.getUUID())) {
            sender.sendSystemMessage(literal("§cYou cannot message yourself."));
            return;
        }

        // if target is ignoring sender, silently drop
        if (isIgnoring(target.getUUID(), sender.getUUID())) {
            return;
        }

        if (updateConversation) {
            lastConversation.put(sender.getUUID(), target.getUUID());
            lastConversation.put(target.getUUID(), sender.getUUID());
        }

        String senderName = sender.getGameProfile().getName();
        String targetName = target.getGameProfile().getName();

        // Build MiniMessage strings
        String toSender = cfg.format.toSender
                .replace("<sender>", senderName)
                .replace("<target>", targetName)
                .replace("<message>", raw);

        String toTarget = cfg.format.toTarget
                .replace("<sender>", senderName)
                .replace("<target>", targetName)
                .replace("<message>", raw);

        Component advSender = MM.deserialize(toSender);
        Component advTarget = MM.deserialize(toTarget);

        ((Audience) sender).sendMessage(advSender);
        ((Audience) target).sendMessage(advTarget);

        if (spyAndConsole) {
            broadcastSpy(sender, target, raw, cfg);
        }
    }

    private void broadcastSpy(ServerPlayer sender,
                              ServerPlayer target,
                              String raw,
                              ChatConfigManager.PrivateMessagesConfig cfg) {

        String senderName = sender.getGameProfile().getName();
        String targetName = target.getGameProfile().getName();

        String spyMsg = cfg.format.spy
                .replace("<sender>", senderName)
                .replace("<target>", targetName)
                .replace("<message>", raw);

        Component spyComponent = MM.deserialize(spyMsg);

        // staff spies (local only)
        for (ServerPlayer online : sender.server.getPlayerList().getPlayers()) {
            if (online.getUUID().equals(sender.getUUID())) continue;
            if (online.getUUID().equals(target.getUUID())) continue;
            if (!spies.contains(online.getUUID())) continue;

            ((Audience) online).sendMessage(spyComponent);
        }

        if (cfg.logToConsole) {
            sender.server.sendSystemMessage(
                    literal("[PM Spy] " + senderName + " -> " + targetName + ": " + raw)
            );
        }
    }

    // ------------------------------------------------------------------------
    // Redis publishing
    // ------------------------------------------------------------------------
    private void publishPm(ServerPlayer sender, ServerPlayer target, String raw) {
        RedisPrivateMessage envelope = RedisPrivateMessage.fromLocal(
                sender.getUUID(),
                sender.getGameProfile().getName(),
                target != null ? target.getUUID() : null,
                target != null ? target.getGameProfile().getName() : "",
                raw
        );

        MEConfig cfg = MEConfig.INSTANCE;
        MEConfig.Redis r = (cfg != null && cfg.chat != null) ? cfg.chat.redis : null;
        if (r == null || r.pmChannel == null || r.pmChannel.isEmpty()) return;

        String json = gson.toJson(envelope);
        redis.publish(r.pmChannel, json);
    }

    // ------------------------------------------------------------------------
    // Redis inbound PM delivery
    // Called by RedisPrivateMessageBridge.handleIncomingPm(...)
    // ------------------------------------------------------------------------
    public void deliverRemote(UUID senderUuid,
                              String senderName,
                              ServerPlayer target,
                              String raw) {

        ChatConfigManager.PrivateMessagesConfig cfg = ChatConfigManager.PRIVATE;
        if (cfg == null || !cfg.enabled) return;

        if (senderUuid != null) {
            lastConversation.put(target.getUUID(), senderUuid);
        }

        // Build toTarget + (optional) spy/console message using the same formats
        String toTarget = cfg.format.toTarget
                .replace("<sender>", senderName)
                .replace("<target>", target.getGameProfile().getName())
                .replace("<message>", raw);

        Component advTarget = MM.deserialize(toTarget);
        ((Audience) target).sendMessage(advTarget);

        // Spies & console on THIS server too (still "local only" per server)
        if (cfg.logToConsole || !spies.isEmpty()) {
            // Fake a minimal sender for spy message
            String spyMsg = cfg.format.spy
                    .replace("<sender>", senderName)
                    .replace("<target>", target.getGameProfile().getName())
                    .replace("<message>", raw);

            Component spyComponent = MM.deserialize(spyMsg);

            // staff spies
            for (ServerPlayer online : target.server.getPlayerList().getPlayers()) {
                if (online.getUUID().equals(target.getUUID())) continue;
                if (!spies.contains(online.getUUID())) continue;
                ((Audience) online).sendMessage(spyComponent);
            }

            if (cfg.logToConsole) {
                target.server.sendSystemMessage(
                        literal("[PM Spy] " + senderName + " -> " + target.getGameProfile().getName() + ": " + raw)
                );
            }
        }
    }

    // ------------------------------------------------------------------------
    // Ignore support (+ Redis mirror)
    // ------------------------------------------------------------------------
    public boolean toggleIgnore(ServerPlayer sender, ServerPlayer target) {
        UUID s = sender.getUUID();
        UUID t = target.getUUID();

        boolean nowIgnored = toggleIgnoreLocal(s, t);

        // mirror to Redis
        if (redisEnabled()) {
            publishIgnore(s, t, nowIgnored);
        }

        return nowIgnored;
    }

    private boolean toggleIgnoreLocal(UUID owner, UUID target) {
        Set<UUID> set = ignoreLists.computeIfAbsent(owner, k -> ConcurrentHashMap.newKeySet());
        if (set.remove(target)) {
            return false; // no longer ignoring
        } else {
            set.add(target);
            return true;  // now ignoring
        }
    }

    public boolean isIgnoring(UUID owner, UUID other) {
        Set<UUID> set = ignoreLists.get(owner);
        return set != null && set.contains(other);
    }

    public UUID getLastConversation(UUID playerId) {
        return lastConversation.get(playerId);
    }

    private void publishIgnore(UUID owner, UUID target, boolean ignored) {
        RedisIgnoreMessage env = RedisIgnoreMessage.of(owner, target, ignored);

        MEConfig cfg = MEConfig.INSTANCE;
        MEConfig.Redis r = (cfg != null && cfg.chat != null) ? cfg.chat.redis : null;
        if (r == null || r.ignoreChannel == null || r.ignoreChannel.isEmpty()) return;

        String json = gson.toJson(env);
        redis.publish(r.ignoreChannel, json);
    }

    /**
     * Apply an ignore change received from Redis.
     * Called by RedisPrivateMessageBridge.handleIncomingIgnore(...)
     */
    public void applyIgnoreFromRemote(UUID owner, UUID target, boolean ignored) {
        Set<UUID> set = ignoreLists.computeIfAbsent(owner, k -> ConcurrentHashMap.newKeySet());
        if (ignored) {
            set.add(target);
        } else {
            set.remove(target);
        }
    }
}
