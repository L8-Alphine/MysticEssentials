package org.hyzionstudios.mysticessentials.modules.flight;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Flight: {@code /fly} toggles the verified {@code MovementSettings.canFly}
 * flag through the player's {@code MovementManager} component (mutated on the
 * player's world thread, then pushed to the client with
 * {@code MovementManager.update(packetHandler)}). Flight is always off until
 * toggled — never granted on first join.
 *
 * <p>With {@code paidFlight} enabled and a VaultUnlocked economy detected,
 * flying costs {@code costPerMinute} charged once a minute — each successful
 * withdrawal sends the player a {@code flight-charged} receipt; players with
 * {@code mysticessentials.fly.free} or {@code .unlimited} fly free. World
 * changes rebuild movement settings from defaults, so flight is re-applied on
 * {@code AddPlayerToWorldEvent}.</p>
 */
public final class FlightModule extends AbstractMysticModule {

    private final Set<UUID> flying = ConcurrentHashMap.newKeySet();
    private FlightConfig config = new FlightConfig();
    private ScheduledFuture<?> chargeTask;
    private ScheduledFuture<?> reapplyTask;

    public FlightModule() {
        super("flight", "Flight", "1.0.0");
    }

    @Override
    public void onEnable() {
        config = core.configManager().loadModuleConfig(id(), FlightConfig.class, new FlightConfig());
        registerCommand(new FlyCommand());
        // World transfers and gamemode changes rebuild MovementSettings from
        // defaults (no usable plugin event exposes them in 0.5.6), so flight is
        // re-applied on a short loop for players who have it enabled.
        reapplyTask = core.scheduler().runRepeating(this::reapplyFlight, 10, 10, TimeUnit.SECONDS);
        registerEvent(PlayerDisconnectEvent.class, (PlayerDisconnectEvent event) ->
                flying.remove(event.getPlayerRef().getUuid()));
        startChargeTask();
    }

    @Override
    public void onReload() {
        config = core.configManager().loadModuleConfig(id(), FlightConfig.class, new FlightConfig());
        stopChargeTask();
        startChargeTask();
    }

    /** Re-applies canFly for flying players whose settings were rebuilt by the server. */
    private void reapplyFlight() {
        for (UUID uuid : flying) {
            core.platform().findPlayer(uuid).ifPresent(player -> applyFlight(player, true));
        }
    }

    @Override
    public void onDisable() {
        stopChargeTask();
        if (reapplyTask != null) {
            reapplyTask.cancel(false);
            reapplyTask = null;
        }
        for (UUID uuid : flying) {
            core.platform().findPlayer(uuid).ifPresent(player -> applyFlight(player, false));
        }
        flying.clear();
    }

    private void startChargeTask() {
        if (!config.paidFlight || chargeTask != null) {
            return;
        }
        chargeTask = core.scheduler().runRepeating(this::chargeFlyingPlayers, 60, 60, TimeUnit.SECONDS);
    }

    private void stopChargeTask() {
        if (chargeTask != null) {
            chargeTask.cancel(false);
            chargeTask = null;
        }
    }

    private void chargeFlyingPlayers() {
        if (!config.paidFlight || !core.getEconomyService().isAvailable()) {
            return;
        }
        for (UUID uuid : flying) {
            PlayerRef player = core.platform().findPlayer(uuid).orElse(null);
            if (player == null) {
                flying.remove(uuid);
                continue;
            }
            if (isCostExempt(player)) {
                continue;
            }
            if (core.getEconomyService().withdraw(uuid, config.costPerMinute)) {
                core.getMessageService().sendKey(player, "flight-charged", Map.of(
                        "cost", core.getEconomyService().format(config.costPerMinute),
                        "balance", core.getEconomyService().format(core.getEconomyService().balance(uuid))));
            } else {
                setFlying(player, false);
                core.getMessageService().sendKey(player, "flight-out-of-money");
            }
        }
    }

    /** True when the player is exempt from paid-flight charges. */
    private boolean isCostExempt(PlayerRef player) {
        return player.hasPermission(Permissions.FLY_FREE)
                || player.hasPermission(Permissions.FLY_UNLIMITED);
    }

    // ----- Flight state ---------------------------------------------------------

    boolean isFlying(UUID player) {
        return flying.contains(player);
    }

    /** Toggles or sets flight; returns the new state. */
    boolean setFlying(PlayerRef player, boolean enable) {
        if (enable) {
            flying.add(player.getUuid());
        } else {
            flying.remove(player.getUuid());
        }
        applyFlight(player, enable);
        return enable;
    }

    /**
     * Mutates {@code MovementSettings.canFly} on the player's world thread and
     * pushes the updated settings to the client.
     */
    private void applyFlight(PlayerRef player, boolean canFly) {
        boolean dispatched = core.platform().runOnEntityThread(player, (store, entity, world) -> {
            MovementManager movement = store.getComponent(entity, MovementManager.getComponentType());
            if (movement == null) {
                core.getMessageService().sendKey(player, "flight-unavailable");
                return;
            }
            var settings = movement.getSettings();
            settings.canFly = canFly;
            if (canFly) {
                var defaults = movement.getDefaultSettings();
                settings.horizontalFlySpeed = defaults.horizontalFlySpeed
                        * Math.max(0.1f, config.horizontalSpeedMultiplier);
                settings.verticalFlySpeed = defaults.verticalFlySpeed
                        * Math.max(0.1f, config.verticalSpeedMultiplier);
            }
            movement.update(player.getPacketHandler());
        });
        if (!dispatched) {
            flying.remove(player.getUuid());
        }
    }

    private void toggleFor(MysticCommandSender sender, PlayerRef target, boolean announceToTarget) {
        boolean enable = !isFlying(target.getUuid());
        if (enable && config.paidFlight && core.getEconomyService().isAvailable()
                && !isCostExempt(target)
                && !core.getEconomyService().has(target.getUuid(), config.costPerMinute)) {
            sender.replyKey("flight-not-enough-money",
                    Map.of("cost", core.getEconomyService().format(config.costPerMinute)));
            return;
        }
        setFlying(target, enable);
        String state = enable ? "&aenabled" : "&7disabled";
        if (announceToTarget) {
            core.getMessageService().sendKey(target, "flight-state", Map.of("state", state));
        }
        if (!sender.uuid().equals(target.getUuid())) {
            sender.replyKey("flight-state-other", Map.of("state", state, "player", target.getUsername()));
        }
        if (enable && config.paidFlight && core.getEconomyService().isAvailable()
                && !isCostExempt(target)) {
            core.getMessageService().sendKey(target, "flight-cost",
                    Map.of("cost", core.getEconomyService().format(config.costPerMinute)));
        }
    }

    // ----- Commands ----------------------------------------------------------

    /** {@code /fly} toggles own flight; {@code /fly <player>} toggles another's (positional variant). */
    private final class FlyCommand extends MysticCommand {
        FlyCommand() {
            super(FlightModule.this.core, "fly", "Toggle flight.");
            requirePermission(Permissions.FLY_USE);
            addUsageVariant(new FlyOtherVariant());
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            toggleFor(sender, sender.player().orElseThrow(), true);
        }
    }

    private final class FlyOtherVariant extends MysticCommand {
        private final RequiredArg<String> target = withRequiredArg("player", "Target player",
                ArgTypes.STRING).suggest((commandSender, input, index, result) ->
                        core.platform().onlinePlayers().forEach(p -> result.suggest(p.getUsername())));

        FlyOtherVariant() {
            super(FlightModule.this.core, "Toggle flight for another player.");
            requirePermission(Permissions.FLY_OTHERS);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            PlayerRef targetRef = core.platform().findPlayerByName(sender.get(target)).orElse(null);
            if (targetRef == null) {
                sender.replyKey("player-not-found");
                return;
            }
            toggleFor(sender, targetRef, true);
        }
    }
}
