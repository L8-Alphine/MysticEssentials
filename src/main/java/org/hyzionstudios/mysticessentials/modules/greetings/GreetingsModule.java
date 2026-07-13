package org.hyzionstudios.mysticessentials.modules.greetings;

import java.util.UUID;

import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Player-greeting features: MOTD on join, a first-join message, and optional
 * join/leave broadcasts. Message templates support placeholders (e.g.
 * {@code {player_name}}) and full colour markup.
 *
 * <p>Loads the joining player's profile to detect a first-ever join, then sends
 * the MOTD to that player and (if enabled) broadcasts join/leave lines to the
 * server.</p>
 */
public final class GreetingsModule extends AbstractMysticModule {

    private GreetingsConfig config;

    public GreetingsModule() {
        super("greetings", "Greetings", "1.0.0");
    }

    @Override
    public void onEnable() {
        config = core.configManager().loadModuleConfig(id(), GreetingsConfig.class, new GreetingsConfig());
        registerEvent(PlayerConnectEvent.class, (PlayerConnectEvent event) -> onJoin(event.getPlayerRef()));
        registerEvent(PlayerDisconnectEvent.class,
                (PlayerDisconnectEvent event) -> onLeave(event.getPlayerRef()));
    }

    @Override
    public void onReload() {
        config = core.configManager().loadModuleConfig(id(), GreetingsConfig.class, new GreetingsConfig());
    }

    @Override
    public void onDisable() {
        // No persistent state.
    }

    private void onJoin(PlayerRef player) {
        // Load (idempotent) so first-join detection and player placeholders are ready.
        core.getPlayerProfileService().load(player.getUuid(), player.getUsername()).thenAccept(profile -> {
            sendMotd(player);
            // Vanished players join silently (MysticVanish integration).
            if (core.vanish().isVanished(player.getUuid())) {
                return;
            }
            if (config.firstJoinEnabled && profile.isFirstJoin()) {
                broadcast(config.firstJoinMessage, player.getUuid());
            } else if (config.joinEnabled) {
                broadcast(config.joinMessage, player.getUuid());
            }
        });
    }

    private void onLeave(PlayerRef player) {
        if (config.leaveEnabled && !core.vanish().isVanished(player.getUuid())) {
            broadcast(config.leaveMessage, player.getUuid());
        }
    }

    private void sendMotd(PlayerRef player) {
        if (!config.motdEnabled || config.motd == null) {
            return;
        }
        for (String line : config.motd) {
            player.sendMessage(core.getMessageService().formatFor(player.getUuid(), line));
        }
    }

    /** Broadcasts a formatted line (resolved for {@code contextPlayer}) to every online player. */
    private void broadcast(String template, UUID contextPlayer) {
        Message message = core.getMessageService().formatFor(contextPlayer, template);
        for (PlayerRef online : core.platform().onlinePlayers()) {
            online.sendMessage(message);
        }
    }
}
