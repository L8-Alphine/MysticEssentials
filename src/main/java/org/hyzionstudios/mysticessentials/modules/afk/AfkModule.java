package org.hyzionstudios.mysticessentials.modules.afk;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.api.model.MysticLocation;
import org.hyzionstudios.mysticessentials.api.service.AfkService;
import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.platform.Conversions;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.protocol.packets.interface_.EditorBlocksChange;
import com.hypixel.hytale.protocol.packets.interface_.EditorSelection;

/**
 * Idle-state tracking: manual {@code /afk}, automatic AFK after inactivity, and
 * the AFK Rewards submodule with named reward zones.
 *
 * <p>Activity is detected from three signals — movement (position polled by the
 * idle-check task, since the server exposes no player-move event), mouse/attack
 * clicks ({@code PlayerMouseButtonEvent}), and chat ({@code PlayerChatEvent}).
 * A player who is idle past the configured threshold, without the bypass
 * permission, is flagged AFK; any activity clears it.</p>
 *
 * <p>Reward zones ({@code /afkzone}) are named cuboids. Movement always clears
 * a player's AFK state <b>except</b> while they remain inside a zone (so AFK
 * pools that nudge players with currents keep working). The idle-check task
 * therefore runs even when auto-AFK is disabled, and the auto-AFK bypass
 * permission only exempts a player from being <i>flagged</i>, never from
 * movement clearing an existing AFK state.</p>
 */
public final class AfkModule extends AbstractMysticModule implements AfkService {

    /** Distance (blocks) a player must move for it to count as activity. */
    private static final double MOVE_EPSILON_SQ = 0.1 * 0.1;

    /**
     * Activity within this window after going AFK is ignored, so the click that
     * confirms the zone-select UI cannot instantly clear the fresh AFK state.
     */
    private static final long AFK_GRACE_MILLIS = 1000;

    /**
     * A freshly-entered zone session ignores exit detection for this long. It
     * stops a zone teleport (whose position update can lag a tick behind) from
     * being read as "already walked out" and instantly bouncing the player back.
     */
    private static final long ZONE_ENTER_GRACE_MILLIS = 1500;

    private static final String ZONE_NAME_PATTERN = "[A-Za-z0-9_-]{1,32}";

    /** Profile moduleData key holding this module's persisted per-player data. */
    private static final String DATA_KEY = "afk";
    /** Pre-zone-teleport location, persisted so it survives restarts. */
    private static final String RETURN_KEY = "returnLocation";

    private static final class Activity {
        boolean afk;
        String reason;
        long lastActivityMillis = System.currentTimeMillis();
        MysticLocation lastPosition;
    }

    private static final class RewardState {
        double session;
        double dailyTotal;
        int rollsToday;
        long dayIndex = -1;
        /** When the next roll is due for a player earning outside a zone (no ZoneSession clock). */
        long nextRollAtMillis;
    }

    private static final class ZoneSession {
        String zoneName;
        long enteredAtMillis;
        long nextRewardAtMillis;
        String lastHudText;
    }

    private final Map<UUID, Activity> states = new ConcurrentHashMap<>();
    private final Map<UUID, RewardState> rewards = new ConcurrentHashMap<>();
    /** Players currently inside a permitted AFK zone, used for exit handling and HUD updates. */
    private final Map<UUID, ZoneSession> zoneSessions = new ConcurrentHashMap<>();
    /** Per-admin pending {@code /afkzone pos1}/{@code pos2} corner selections. */
    private final Map<UUID, MysticLocation[]> zoneSelections = new ConcurrentHashMap<>();
    /** In-memory mirror of the persisted return locations (profile is the source of truth). */
    private final Map<UUID, MysticLocation> returnLocations = new ConcurrentHashMap<>();
    private AfkConfig config;
    private ScheduledFuture<?> tickTask;
    private ScheduledFuture<?> rewardTask;

    public AfkModule() {
        super("afk", "AFK", "1.0.0");
    }

    @Override
    public void onEnable() {
        loadConfig();
        registerCommand(new AfkCommand());
        registerCommand(new AfkZoneCommand());

        // Activity signals: clicks (Void-keyed event) and chat (async event).
        registerEvent(PlayerMouseButtonEvent.class,
                (PlayerMouseButtonEvent event) -> markActivity(event.getPlayerRefComponent().getUuid()));
        registerAsyncEvent(PlayerChatEvent.class, future -> future.thenApply(event -> {
            markActivity(event.getSender().getUuid());
            return event;
        }));
        // Restart recovery: a player who was zone-teleported when the server
        // stopped still has a persisted return location — send them back.
        registerEvent(PlayerConnectEvent.class,
                (PlayerConnectEvent event) -> restoreReturnLocation(event.getPlayerRef()));

        // A single 1s tick drives zone enter/exit, HUD refresh, movement, and
        // auto-AFK. It always runs (movement-clearing must work even when
        // auto-AFK flagging is off), and the fast cadence is what keeps zone
        // entry/exit registering within a second instead of one slow poll later.
        // One task (never concurrent with itself) also avoids racing the shared
        // AFK/zone state across the scheduler's thread pool.
        tickTask = core.scheduler().runRepeating(this::tick, 1, 1, TimeUnit.SECONDS);

        syncRewardTask();
    }

    @Override
    public void onReload() {
        loadConfig();
        // A config reload calls onReload (never onEnable), so the reward task
        // must be (re)started/stopped here — otherwise enabling AFK Rewards via
        // reload would show the HUD countdown but never grant or reset it.
        syncRewardTask();
    }

    /**
     * Reconciles {@link #rewardTask} with {@code config.rewards.enabled}: starts
     * it when rewards are enabled and stops it when they are not. The task polls
     * every second so a player's countdown grants and resets as soon as it hits
     * zero; the configured {@code intervalSeconds} paces the actual payouts (read
     * fresh each run), so an interval change takes effect without a restart.
     */
    private void syncRewardTask() {
        if (config.rewards.enabled) {
            if (rewardTask == null) {
                rewardTask = core.scheduler().runRepeating(this::grantRewards, 1, 1, TimeUnit.SECONDS);
                log("AFK Rewards enabled (" + Math.max(5, config.rewards.intervalSeconds) + "s interval, "
                        + config.rewards.zones.size() + " zone(s)).");
            }
        } else if (rewardTask != null) {
            rewardTask.cancel(false);
            rewardTask = null;
        }
    }

    @Override
    public void onDisable() {
        if (tickTask != null) {
            tickTask.cancel(false);
            tickTask = null;
        }
        if (rewardTask != null) {
            rewardTask.cancel(false);
            rewardTask = null;
        }
        for (PlayerRef player : core.platform().onlinePlayers()) {
            hideZoneHud(player);
        }
        states.clear();
        rewards.clear();
        zoneSessions.clear();
        zoneSelections.clear();
        returnLocations.clear();
    }

    private void loadConfig() {
        config = core.configManager().loadModuleConfig(id(), AfkConfig.class, new AfkConfig());
        if (config.rewards == null) {
            config.rewards = new AfkConfig.Rewards();
        }
        if (config.rewards.zones == null) {
            config.rewards.zones = new ArrayList<>();
        }
        if (config.rewards.rewardPool == null) {
            config.rewards.rewardPool = new ArrayList<>();
        }
        validatePool("rewards.rewardPool", config.rewards.rewardPool);
        for (AfkConfig.Zone zone : config.rewards.zones) {
            if (zone.rewardPool != null) {
                validatePool("zone '" + zone.name + "'", zone.rewardPool);
            }
        }
        convertLegacyZoneCorners();
    }

    /** Logs pool entries that can never roll so misconfigurations are visible at boot. */
    private void validatePool(String context, List<AfkConfig.RewardEntry> pool) {
        for (AfkConfig.RewardEntry entry : pool) {
            if (entry == null) {
                continue;
            }
            boolean valid = switch (rewardType(entry)) {
                case "money" -> entry.amount > 0;
                case "item" -> entry.itemId != null && !entry.itemId.isBlank();
                case "command" -> entry.command != null && !entry.command.isBlank();
                default -> false;
            };
            if (!valid || entry.weight <= 0) {
                log("AFK reward pool entry in " + context + " (type '" + entry.type + "', weight "
                        + entry.weight + ") is invalid and will never roll.");
            }
        }
    }

    /** One-time upgrade: the old single {@code zoneCornerA/B} pair becomes a named zone. */
    private void convertLegacyZoneCorners() {
        AfkConfig.Rewards r = config.rewards;
        if (r.zoneCornerA == null || r.zoneCornerB == null) {
            return;
        }
        String name = "legacy";
        for (int i = 2; findZone(name).isPresent(); i++) {
            name = "legacy-" + i;
        }
        r.zones.add(new AfkConfig.Zone(name, r.zoneCornerA, r.zoneCornerB));
        r.zoneCornerA = null;
        r.zoneCornerB = null;
        saveConfig();
        log("Converted legacy reward-zone corners into AFK zone '" + name + "'.");
    }

    private void saveConfig() {
        try {
            Json.writeFile(core.paths().moduleConfigFile(id()), config);
        } catch (Exception e) {
            log("Failed to save AFK config: " + e.getMessage());
        }
    }

    // ----- AfkService --------------------------------------------------------

    @Override
    public boolean isAfk(UUID player) {
        Activity state = states.get(player);
        return state != null && state.afk;
    }

    @Override
    public void setAfk(UUID player, boolean afk, String reason) {
        Activity state = states.computeIfAbsent(player, k -> new Activity());
        state.afk = afk;
        state.reason = reason;
        state.lastActivityMillis = System.currentTimeMillis();
        core.platform().findPlayer(player).ifPresent(ref -> {
            state.lastPosition = safeCapture(ref);
            if (!afk) {
                endZoneSession(ref);
                returnFromZone(ref);
                return;
            }
            enterConfiguredZone(ref, state.lastPosition);
        });
    }

    /**
     * On going AFK, applies the reward-zone behaviour: teleport into a permitted
     * zone when {@code teleportToZoneOnAfk} is set, else start a session for the
     * zone the player already stands in, else hint that a zone is required.
     * Shared by {@code /afk} ({@link #setAfk}) and auto-AFK ({@link #goAutoAfk}).
     */
    private void enterConfiguredZone(PlayerRef player, MysticLocation position) {
        AfkConfig.Rewards r = config.rewards;
        if (r.teleportToZoneOnAfk && teleportIntoZone(player, position)) {
            return;
        }
        Optional<AfkConfig.Zone> zone = zoneFor(player, position);
        if (zone.isPresent()) {
            startOrUpdateZoneSession(player, zone.get(), System.currentTimeMillis());
        } else if (r.enabled && r.requireInZone) {
            core.getMessageService().sendKey(player, "afk-reward-zone-hint");
        }
    }

    @Override
    public void markActivity(UUID player) {
        Activity state = states.computeIfAbsent(player, k -> new Activity());
        boolean wasAfk = state.afk;
        // While AFK, lastActivityMillis holds the moment the player went AFK.
        if (wasAfk && System.currentTimeMillis() - state.lastActivityMillis < AFK_GRACE_MILLIS) {
            return;
        }
        state.afk = false;
        state.reason = null;
        state.lastActivityMillis = System.currentTimeMillis();
        if (wasAfk) {
            core.platform().findPlayer(player).ifPresent(ref -> {
                endZoneSession(ref);
                core.getMessageService().sendKey(ref, "afk-returned");
                announce(ref.getUuid(), ref.getUsername() + " is no longer AFK");
                returnFromZone(ref);
            });
        } else {
            core.platform().findPlayer(player).ifPresent(this::endZoneSession);
        }
    }

    @Override
    public long idleSeconds(UUID player) {
        Activity state = states.get(player);
        if (state == null || state.afk) {
            return 0;
        }
        return (System.currentTimeMillis() - state.lastActivityMillis) / 1000L;
    }

    // ----- Zone / movement / idle tick ---------------------------------------

    /**
     * The 1s poll: detects zone entry/exit, refreshes zone HUDs, clears AFK on
     * movement (except while drifting inside a zone), and flags idle players
     * auto-AFK. Runs as a single task so it never races itself over the shared
     * AFK/zone state.
     */
    private void tick() {
        long thresholdMillis = Math.max(1, config.autoAfkSeconds) * 1000L;
        long now = System.currentTimeMillis();
        Set<UUID> online = new HashSet<>();

        for (PlayerRef player : core.platform().onlinePlayers()) {
            UUID uuid = player.getUuid();
            online.add(uuid);
            Activity state = states.computeIfAbsent(uuid, k -> new Activity());
            MysticLocation current = safeCapture(player);
            Optional<AfkConfig.Zone> currentZone = zoneFor(player, current);
            ZoneSession session = zoneSessions.get(uuid);
            // A fresh zone session may be a zone teleport whose position hasn't
            // caught up yet (setAfk/goAutoAfk flag AFK and dispatch the teleport
            // synchronously, before the async move actually lands) — don't read
            // the resulting position jump as either an immediate exit or as
            // player-initiated movement that should clear AFK.
            boolean justEnteredZone = session != null
                    && now - session.enteredAtMillis < ZONE_ENTER_GRACE_MILLIS;

            if (currentZone.isPresent()) {
                if (!state.afk) {
                    state.afk = true;
                    state.reason = "zone";
                    state.lastActivityMillis = now;
                    core.getMessageService().sendKey(player, "afk-now");
                    announce(uuid, player.getUsername() + " is now AFK");
                }
                startOrUpdateZoneSession(player, currentZone.get(), now);
            } else if (session != null) {
                if (!justEnteredZone) {
                    if (state.afk) {
                        markActivity(uuid);
                        continue;
                    }
                    endZoneSession(player);
                }
            }

            boolean moved = current != null && state.lastPosition != null
                    && hasMoved(state.lastPosition, current);
            if (current != null) {
                state.lastPosition = current;
            }
            if (moved) {
                // Drifting inside a reward zone keeps an AFK player AFK (pools
                // push players around); any movement elsewhere is activity.
                if (justEnteredZone
                        || (state.afk && currentZone.isPresent() && config.rewards.stayAfkWhileMovingInZone)) {
                    continue;
                }
                markActivity(uuid);
                continue;
            }
            if (!config.autoAfkEnabled || state.afk
                    || (config.bypassPermission != null && player.hasPermission(config.bypassPermission))) {
                continue;
            }
            if (now - state.lastActivityMillis >= thresholdMillis) {
                goAutoAfk(player, state, current, now);
            }
        }
        // Drop tracking for players who are no longer online.
        states.keySet().retainAll(online);
        zoneSessions.keySet().retainAll(online);
    }

    /** Flags a player auto-AFK and sends them to a reward zone (default AFK zone). */
    private void goAutoAfk(PlayerRef player, Activity state, MysticLocation current, long now) {
        state.afk = true;
        state.reason = "auto";
        state.lastActivityMillis = now;
        core.getMessageService().sendKey(player, "afk-now");
        announce(player.getUuid(), player.getUsername() + " is now AFK");
        enterConfiguredZone(player, current);
    }

    private MysticLocation safeCapture(PlayerRef player) {
        try {
            return Conversions.capture(player);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasMoved(MysticLocation a, MysticLocation b) {
        if (a.getWorld() != null && !a.getWorld().equals(b.getWorld())) {
            return true;
        }
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return (dx * dx + dy * dy + dz * dz) > MOVE_EPSILON_SQ;
    }

    /** Broadcasts an AFK status line, unless the player is vanished. */
    private void announce(UUID player, String message) {
        if (config.announce && core.getAnnouncementService() != null
                && !core.vanish().isVanished(player)) {
            core.getAnnouncementService().broadcast("&8* &7" + message);
        }
    }

    // ----- Reward zones ------------------------------------------------------

    private Optional<AfkConfig.Zone> zoneAt(MysticLocation pos) {
        if (pos == null) {
            return Optional.empty();
        }
        return config.rewards.zones.stream().filter(z -> z.contains(pos)).findFirst();
    }

    private static boolean zoneAllows(PlayerRef player, AfkConfig.Zone zone) {
        return zone.permission == null || zone.permission.isBlank() || player.hasPermission(zone.permission);
    }

    /** The zone {@code player} is standing in and allowed to use, if any. */
    private Optional<AfkConfig.Zone> zoneFor(PlayerRef player, MysticLocation pos) {
        if (pos == null) {
            return Optional.empty();
        }
        return config.rewards.zones.stream()
                .filter(z -> z.contains(pos))
                .filter(z -> zoneAllows(player, z))
                .findFirst();
    }

    // ----- Zone teleport & return --------------------------------------------

    /**
     * Saves the player's location and teleports them to a random spot inside a
     * permitted zone (same-world zones preferred).
     *
     * @return {@code true} if the player is now inside a permitted zone —
     *         either teleported, or they were already standing in one.
     */
    /** Zones with both corners set that {@code player} has permission to use. */
    List<AfkConfig.Zone> permittedZones(PlayerRef player) {
        List<AfkConfig.Zone> permitted = new ArrayList<>();
        for (AfkConfig.Zone zone : config.rewards.zones) {
            if (zone.cornerA != null && zone.cornerB != null && zoneAllows(player, zone)) {
                permitted.add(zone);
            }
        }
        return permitted;
    }

    private boolean teleportIntoZone(PlayerRef player, MysticLocation current) {
        if (current == null || config.rewards.zones.isEmpty()) {
            return false;
        }
        Optional<AfkConfig.Zone> currentZone = zoneFor(player, current);
        if (currentZone.isPresent()) {
            startOrUpdateZoneSession(player, currentZone.get(), System.currentTimeMillis());
            return true; // already standing in a zone they may use
        }
        List<AfkConfig.Zone> permitted = permittedZones(player);
        if (permitted.isEmpty()) {
            return false;
        }
        // A configured default zone wins (even cross-world); otherwise fall back
        // to a same-world zone, then any permitted zone, at random.
        AfkConfig.Zone zone = defaultZoneFor(player).orElseGet(() -> {
            List<AfkConfig.Zone> sameWorld = permitted.stream()
                    .filter(z -> current.getWorld() != null && current.getWorld().equals(z.cornerA.getWorld()))
                    .toList();
            List<AfkConfig.Zone> candidates = sameWorld.isEmpty() ? permitted : sameWorld;
            return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        });
        enterZone(player, current, zone);
        return true;
    }

    /** The configured default auto-AFK zone, if set, valid, and usable by {@code player}. */
    private Optional<AfkConfig.Zone> defaultZoneFor(PlayerRef player) {
        String name = config.rewards.defaultZone;
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return findZone(name)
                .filter(zone -> zone.cornerA != null && zone.cornerB != null)
                .filter(zone -> zoneAllows(player, zone));
    }

    /** Saves the return point and sends the player to a random spot in {@code zone}. */
    private void enterZone(PlayerRef player, MysticLocation from, AfkConfig.Zone zone) {
        saveReturnLocation(player.getUuid(), from);
        core.getTeleportService().teleportNow(player, randomPointIn(zone));
        startOrUpdateZoneSession(player, zone, System.currentTimeMillis());
        core.getMessageService().sendKey(player, "afk-zone-teleported", Map.of("name", zone.name));
    }

    /**
     * Marks the player AFK, optionally sending them into {@code zone} (used by
     * the zone-select UI; {@code null} = stay put). Mirrors the {@code /afk}
     * command's announce/message behaviour.
     */
    void goAfk(PlayerRef player, String reason, AfkConfig.Zone zone) {
        UUID uuid = player.getUuid();
        Activity state = states.computeIfAbsent(uuid, k -> new Activity());
        state.afk = true;
        state.reason = reason;
        state.lastActivityMillis = System.currentTimeMillis();
        MysticLocation current = safeCapture(player);
        state.lastPosition = current;

        if (zone != null && current != null && zoneFor(player, current).isEmpty()) {
            enterZone(player, current, zone);
        } else if (config.rewards.enabled && config.rewards.requireInZone
                && zoneFor(player, current).isEmpty()) {
            core.getMessageService().sendKey(player, "afk-reward-zone-hint");
        } else {
            zoneFor(player, current).ifPresent(currentZone -> startOrUpdateZoneSession(player, currentZone,
                    System.currentTimeMillis()));
        }
        announce(uuid, player.getUsername() + " is now AFK" + (reason == null ? "" : ": " + reason));
        core.getMessageService().sendKey(player, "afk-now");
    }

    private void startOrUpdateZoneSession(PlayerRef player, AfkConfig.Zone zone, long now) {
        UUID uuid = player.getUuid();
        String zoneName = displayZoneName(zone);
        ZoneSession session = zoneSessions.computeIfAbsent(uuid, ignored -> {
            ZoneSession created = new ZoneSession();
            created.zoneName = zoneName;
            created.enteredAtMillis = now;
            created.nextRewardAtMillis = nextRewardAt(now);
            return created;
        });
        if (session.zoneName == null || !session.zoneName.equals(zoneName)) {
            session.zoneName = zoneName;
            session.enteredAtMillis = now;
            session.nextRewardAtMillis = nextRewardAt(now);
            session.lastHudText = null;
        }
        if (session.nextRewardAtMillis <= 0) {
            session.nextRewardAtMillis = nextRewardAt(now);
        }
        showZoneHud(player, session, now);
    }

    private void endZoneSession(PlayerRef player) {
        if (zoneSessions.remove(player.getUuid()) != null) {
            hideZoneHud(player);
        }
    }

    private void showZoneHud(PlayerRef player, ZoneSession session, long now) {
        String elapsed = "In zone: " + formatDuration(Math.max(0, now - session.enteredAtMillis), false);
        String nextReward = nextRewardText(player, session, now);
        String text = session.zoneName + "|" + elapsed + "|" + nextReward;
        if (text.equals(session.lastHudText)) {
            return;
        }
        session.lastHudText = text;
        core.platform().showHud(player, new AfkZoneHud(player, session.zoneName, elapsed, nextReward));
    }

    private void hideZoneHud(PlayerRef player) {
        core.platform().removeHud(player, AfkZoneHud.KEY);
    }

    private String nextRewardText(PlayerRef player, ZoneSession session, long now) {
        AfkConfig.Rewards r = config.rewards;
        if (!r.enabled) {
            return "Next reward: disabled";
        }
        if (r.permission != null && !r.permission.isBlank() && !player.hasPermission(r.permission)) {
            return "Next reward: no permission";
        }
        RewardState rewardState = rewards.get(player.getUuid());
        long today = System.currentTimeMillis() / 86_400_000L;
        if (rewardState != null && rewardState.dayIndex == today
                && r.maxRollsPerDay > 0 && rewardState.rollsToday >= r.maxRollsPerDay) {
            return "Next reward: daily cap reached";
        }
        long remaining = Math.max(0, session.nextRewardAtMillis - now);
        return "Next reward: " + formatDuration(remaining, true);
    }

    private long nextRewardAt(long now) {
        return now + rewardIntervalMillis(config.rewards);
    }

    private static long rewardIntervalMillis(AfkConfig.Rewards rewards) {
        return Math.max(5, rewards.intervalSeconds) * 1000L;
    }

    private static String displayZoneName(AfkConfig.Zone zone) {
        return zone.name == null || zone.name.isBlank() ? "AFK Zone" : zone.name;
    }

    /** Sends the player back to where they stood before the zone teleport, if recorded. */
    private void returnFromZone(PlayerRef player) {
        takeReturnLocation(player.getUuid()).ifPresent(location -> {
            core.getTeleportService().teleportNow(player, location);
            core.getMessageService().sendKey(player, "afk-zone-returned");
        });
    }

    /** A random point inside the zone at floor level (the lowest corner Y). */
    private static MysticLocation randomPointIn(AfkConfig.Zone zone) {
        double x = randomBetween(zone.cornerA.getX(), zone.cornerB.getX());
        double z = randomBetween(zone.cornerA.getZ(), zone.cornerB.getZ());
        double y = Math.min(zone.cornerA.getY(), zone.cornerB.getY());
        return new MysticLocation(zone.cornerA.getWorld(), x, y + 0.1, z, 0f, 0f);
    }

    private static double randomBetween(double a, double b) {
        double min = Math.min(a, b);
        double max = Math.max(a, b);
        return min >= max ? min : ThreadLocalRandom.current().nextDouble(min, max);
    }

    /** Persists the return location in the player profile so it survives restarts. */
    private void saveReturnLocation(UUID uuid, MysticLocation location) {
        returnLocations.put(uuid, location);
        core.getPlayerProfileService().getCached(uuid).ifPresent(profile -> {
            profile.getModuleData().computeIfAbsent(DATA_KEY, k -> new JsonObject())
                    .add(RETURN_KEY, Json.gson().toJsonTree(location));
            core.getPlayerProfileService().save(profile);
        });
    }

    /** Removes and returns the stored return location (memory first, then profile). */
    private Optional<MysticLocation> takeReturnLocation(UUID uuid) {
        MysticLocation location = returnLocations.remove(uuid);
        var cached = core.getPlayerProfileService().getCached(uuid);
        if (cached.isPresent()) {
            JsonObject data = cached.get().getModuleData().get(DATA_KEY);
            if (data != null && data.has(RETURN_KEY)) {
                if (location == null) {
                    try {
                        location = Json.gson().fromJson(data.get(RETURN_KEY), MysticLocation.class);
                    } catch (RuntimeException ignored) {
                        // Corrupt entry; drop it below.
                    }
                }
                data.remove(RETURN_KEY);
                core.getPlayerProfileService().save(cached.get());
            }
        }
        return Optional.ofNullable(location);
    }

    /** On join: if a return location survived a restart, send the player back. */
    private void restoreReturnLocation(PlayerRef player) {
        core.getPlayerProfileService().load(player.getUuid(), player.getUsername()).thenAccept(profile -> {
            JsonObject data = profile.getModuleData().get(DATA_KEY);
            if (data == null || !data.has(RETURN_KEY)) {
                return;
            }
            MysticLocation location = null;
            try {
                location = Json.gson().fromJson(data.get(RETURN_KEY), MysticLocation.class);
            } catch (RuntimeException ignored) {
                // Corrupt entry; cleared below either way.
            }
            data.remove(RETURN_KEY);
            core.getPlayerProfileService().save(profile);
            if (location != null) {
                core.getTeleportService().teleportNow(player, location);
                core.getMessageService().sendKey(player, "afk-zone-returned");
            }
        });
    }

    Optional<AfkConfig.Zone> findZone(String name) {
        return config.rewards.zones.stream()
                .filter(z -> z.name != null && z.name.equalsIgnoreCase(name))
                .findFirst();
    }

    private static String formatPos(MysticLocation pos) {
        return pos.getWorld() + " " + Math.round(pos.getX()) + ", "
                + Math.round(pos.getY()) + ", " + Math.round(pos.getZ());
    }

    private void showZoneSelectionPreview(PlayerRef player, MysticLocation[] corners) {
        if (corners == null || corners[0] == null) {
            clearZoneSelectionPreview(player);
            return;
        }
        MysticLocation a = corners[0];
        MysticLocation b = corners[1] == null ? corners[0] : corners[1];
        int ax = blockCoord(a.getX());
        int ay = blockCoord(a.getY());
        int az = blockCoord(a.getZ());
        int bx = blockCoord(b.getX());
        int by = blockCoord(b.getY());
        int bz = blockCoord(b.getZ());
        player.getPacketHandler().write(new EditorBlocksChange(
                new EditorSelection(Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz),
                        Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz)),
                null, null, null, 0, false, false, null, null, null));
    }

    private void clearZoneSelectionPreview(PlayerRef player) {
        player.getPacketHandler().write(new EditorBlocksChange(
                null, null, null, null, 0, false, false, null, null, null));
    }

    private static int blockCoord(double value) {
        return (int) Math.floor(value);
    }

    static String formatSize(AfkConfig.Zone zone) {
        long dx = Math.round(Math.abs(zone.cornerA.getX() - zone.cornerB.getX())) + 1;
        long dy = Math.round(Math.abs(zone.cornerA.getY() - zone.cornerB.getY())) + 1;
        long dz = Math.round(Math.abs(zone.cornerA.getZ() - zone.cornerB.getZ())) + 1;
        return dx + "x" + dy + "x" + dz;
    }

    private static String formatDuration(long millis, boolean roundUp) {
        long seconds = Math.max(0, millis / 1000L);
        if (roundUp && millis % 1000L != 0) {
            seconds++;
        }
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long remainingSeconds = seconds % 60L;
        if (hours > 0) {
            return hours + "h " + String.format(Locale.ROOT, "%02dm", minutes);
        }
        if (minutes > 0) {
            return minutes + "m " + String.format(Locale.ROOT, "%02ds", remainingSeconds);
        }
        return remainingSeconds + "s";
    }

    // ----- AFK Rewards submodule ---------------------------------------------

    private void grantRewards() {
        AfkConfig.Rewards r = config.rewards;
        long now = System.currentTimeMillis();
        long today = now / 86_400_000L;
        long rewardIntervalMillis = rewardIntervalMillis(r);

        for (PlayerRef player : core.platform().onlinePlayers()) {
            UUID uuid = player.getUuid();
            if (!isAfk(uuid)) {
                continue; // only reward players who are actually AFK
            }
            if (r.permission != null && !player.hasPermission(r.permission)) {
                continue;
            }
            RewardState state = rewards.computeIfAbsent(uuid, k -> new RewardState());
            if (state.dayIndex != today) {
                state.dayIndex = today;
                state.dailyTotal = 0;
                state.rollsToday = 0;
            }
            if (r.maxRollsPerDay > 0 && state.rollsToday >= r.maxRollsPerDay) {
                continue;
            }
            AfkConfig.Zone zone = zoneFor(player, safeCapture(player)).orElse(null);
            if (r.requireInZone && zone == null) {
                continue;
            }
            // The zone HUD is owned solely by the tick() task, which refreshes it
            // every second for in-zone players. This task must not show/remove it:
            // running on a separate scheduler thread, its HUD dispatch can overtake
            // tick()'s removeHud when a player leaves a zone, re-adding a HUD that
            // then never updates or clears (it freezes on-screen). Here we only
            // advance the reward clock; tick() reflects the new countdown next poll.
            ZoneSession session = zoneSessions.get(uuid);
            if (session != null) {
                if (session.nextRewardAtMillis <= 0) {
                    session.nextRewardAtMillis = now + rewardIntervalMillis;
                }
                if (now < session.nextRewardAtMillis) {
                    continue;
                }
                session.nextRewardAtMillis = now + rewardIntervalMillis;
            } else {
                // Earning outside a zone: no ZoneSession clock, so pace rolls on
                // the RewardState timer instead of granting on every 1s poll.
                if (state.nextRollAtMillis <= 0) {
                    state.nextRollAtMillis = now + rewardIntervalMillis;
                }
                if (now < state.nextRollAtMillis) {
                    continue;
                }
                state.nextRollAtMillis = now + rewardIntervalMillis;
            }
            // A zone-specific pool replaces the global one for players inside it.
            List<AfkConfig.RewardEntry> pool = zone != null && zone.rewardPool != null && !zone.rewardPool.isEmpty()
                    ? zone.rewardPool
                    : r.rewardPool;
            // Anti-abuse: no reward if recently damaged / in combat.
            core.platform().lastDamageTime(player).thenAccept(last -> {
                if (last != null && Duration.between(last, Instant.now()).getSeconds() < r.noRewardWithinCombatSeconds) {
                    return;
                }
                if (!isAfk(uuid)) {
                    return;
                }
                AfkConfig.RewardEntry entry = rollReward(r, state, pool);
                if (entry != null) {
                    deliverReward(player, r, state, entry);
                }
            });
        }
        rewards.keySet().retainAll(onlineUuids());
    }

    /**
     * Picks one reward from the weighted pool, or a synthetic money entry for
     * the legacy flat payout when the pool is empty. Money entries drop out of
     * the pool once the session/daily money caps are exhausted; {@code null}
     * means nothing is currently winnable.
     */
    private AfkConfig.RewardEntry rollReward(AfkConfig.Rewards r, RewardState state,
            List<AfkConfig.RewardEntry> pool) {
        double remaining = remainingReward(r, state);
        if (pool == null || pool.isEmpty()) {
            if (remaining <= 0 || r.amountPerInterval <= 0) {
                return null;
            }
            AfkConfig.RewardEntry legacy = new AfkConfig.RewardEntry();
            legacy.amount = r.amountPerInterval;
            return legacy;
        }
        List<AfkConfig.RewardEntry> eligible = new ArrayList<>();
        double totalWeight = 0;
        for (AfkConfig.RewardEntry entry : pool) {
            if (entry == null || entry.weight <= 0) {
                continue;
            }
            boolean winnable = switch (rewardType(entry)) {
                case "money" -> entry.amount > 0 && remaining > 0;
                case "item" -> entry.itemId != null && !entry.itemId.isBlank();
                case "command" -> entry.command != null && !entry.command.isBlank();
                default -> false;
            };
            if (winnable) {
                eligible.add(entry);
                totalWeight += entry.weight;
            }
        }
        if (eligible.isEmpty()) {
            return null;
        }
        double roll = ThreadLocalRandom.current().nextDouble(totalWeight);
        for (AfkConfig.RewardEntry entry : eligible) {
            roll -= entry.weight;
            if (roll < 0) {
                return entry;
            }
        }
        return eligible.get(eligible.size() - 1);
    }

    private static String rewardType(AfkConfig.RewardEntry entry) {
        return entry.type == null ? "money" : entry.type.toLowerCase(Locale.ROOT);
    }

    private void deliverReward(PlayerRef player, AfkConfig.Rewards r, RewardState state,
            AfkConfig.RewardEntry entry) {
        UUID uuid = player.getUuid();
        switch (rewardType(entry)) {
            case "money" -> {
                double amount = Math.min(entry.amount, remainingReward(r, state));
                if (amount <= 0) {
                    return;
                }
                core.getEconomyService().deposit(uuid, amount);
                state.session += amount;
                state.dailyTotal += amount;
                state.rollsToday++;
                sendRewardMessage(player, entry, "afk-reward",
                        Map.of("amount", core.getEconomyService().format(amount)));
            }
            case "item" -> {
                state.rollsToday++;
                giveItemReward(player, entry);
                sendRewardMessage(player, entry, "afk-reward-item", Map.of(
                        "item", entry.itemId,
                        "quantity", String.valueOf(Math.max(1, entry.quantity))));
            }
            case "command" -> {
                state.rollsToday++;
                String command = entry.command
                        .replace("{player}", player.getUsername())
                        .replace("{uuid}", uuid.toString());
                if (command.startsWith("/")) {
                    command = command.substring(1);
                }
                core.platform().dispatchConsoleCommand(command);
                if (entry.message != null && !entry.message.isBlank()) {
                    core.getMessageService().send(player, entry.message);
                }
            }
            default -> log("AFK reward entry has unknown type '" + entry.type + "'; skipped.");
        }
    }

    /** Sends the entry's custom message (with params substituted) or the default bundle message. */
    private void sendRewardMessage(PlayerRef player, AfkConfig.RewardEntry entry, String defaultKey,
            Map<String, String> params) {
        if (entry.message == null || entry.message.isBlank()) {
            core.getMessageService().sendKey(player, defaultKey, params);
            return;
        }
        String raw = entry.message;
        for (Map.Entry<String, String> param : params.entrySet()) {
            raw = raw.replace("{" + param.getKey() + "}", param.getValue());
        }
        core.getMessageService().send(player, raw);
    }

    /** Gives the reward item on the player's world thread (overflow drops, like /give). */
    private void giveItemReward(PlayerRef player, AfkConfig.RewardEntry entry) {
        boolean dispatched = core.platform().runOnEntityThread(player, (store, entity, world) -> {
            try {
                ItemStack stack = new ItemStack(entry.itemId, Math.max(1, entry.quantity));
                Player.giveItem(stack, entity, store);
            } catch (Throwable t) {
                log("AFK reward: cannot give item '" + entry.itemId + "': " + t);
            }
        });
        if (!dispatched) {
            log("AFK reward: could not give item to " + player.getUsername() + " (invalid entity).");
        }
    }

    private double remainingReward(AfkConfig.Rewards r, RewardState state) {
        double sessionRemaining = r.maxSessionReward <= 0 ? Double.MAX_VALUE : r.maxSessionReward - state.session;
        double dailyRemaining = r.maxDailyReward <= 0 ? Double.MAX_VALUE : r.maxDailyReward - state.dailyTotal;
        return Math.min(sessionRemaining, dailyRemaining);
    }

    private Set<UUID> onlineUuids() {
        Set<UUID> ids = new HashSet<>();
        for (PlayerRef player : core.platform().onlinePlayers()) {
            ids.add(player.getUuid());
        }
        return ids;
    }

    // ----- Commands ----------------------------------------------------------

    private final class AfkCommand extends MysticCommand {
        AfkCommand() {
            super(AfkModule.this.core, "afk", "Toggle your AFK status.");
            allowExtraArguments();
            requirePermission(Permissions.AFK_USE);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            UUID uuid = sender.uuid();
            if (isAfk(uuid)) {
                markActivity(uuid);
                return;
            }
            String reason = sender.args().length > 0 ? String.join(" ", sender.args()) : null;
            // With several zones to choose from (and not already inside one),
            // let the player pick in a UI; going AFK happens on selection.
            PlayerRef player = sender.player().orElse(null);
            if (player != null && config.rewards.teleportToZoneOnAfk
                    && zoneFor(player, safeCapture(player)).isEmpty()
                    && permittedZones(player).size() > 1) {
                core.platform().openPage(player,
                        new AfkPages.ZoneSelectPage(core, AfkModule.this, player, reason));
                return;
            }
            setAfk(uuid, true, reason);
            announce(uuid, sender.name() + " is now AFK" + (reason == null ? "" : ": " + reason));
            sender.replyKey("afk-now");
        }
    }

    /**
     * {@code /afkzone} — admin management of AFK reward zones: select the two
     * corners by standing on them ({@code pos1}/{@code pos2}), then
     * {@code create <name>}; plus {@code delete}, {@code list} and a
     * {@code check} helper that reports which zone the admin is standing in.
     */
    private final class AfkZoneCommand extends MysticCommand {
        AfkZoneCommand() {
            super(AfkModule.this.core, "afkzone", "Manage AFK reward zones.");
            requirePermission(Permissions.AFK_ZONE_ADMIN);
            addSubCommand(new ZonePosCommand("pos1", 0));
            addSubCommand(new ZonePosCommand("pos2", 1));
            addSubCommand(new ZoneCreateCommand());
            addSubCommand(new ZoneDeleteCommand());
            addSubCommand(new ZonePermissionCommand());
            addSubCommand(new ZoneDefaultCommand());
            addSubCommand(new ZoneListCommand());
            addSubCommand(new ZoneCheckCommand());
        }

        @Override
        protected void run(MysticCommandSender sender) {
            sender.replyKey("afkzone-usage");
            listZones(sender);
        }
    }

    private void listZones(MysticCommandSender sender) {
        if (config.rewards.zones.isEmpty()) {
            sender.replyKey("afkzone-none");
            return;
        }
        sender.replyKey("afkzone-list-header");
        for (AfkConfig.Zone zone : config.rewards.zones) {
            boolean gated = zone.permission != null && !zone.permission.isBlank();
            Map<String, String> params = gated
                    ? Map.of("name", zone.name,
                            "world", zone.cornerA.getWorld() == null ? "?" : zone.cornerA.getWorld(),
                            "size", formatSize(zone),
                            "permission", zone.permission)
                    : Map.of("name", zone.name,
                            "world", zone.cornerA.getWorld() == null ? "?" : zone.cornerA.getWorld(),
                            "size", formatSize(zone));
            sender.replyKey(gated ? "afkzone-list-entry-perm" : "afkzone-list-entry", params);
        }
        String defaultZone = config.rewards.defaultZone;
        if (defaultZone != null && !defaultZone.isBlank()) {
            sender.replyKey("afkzone-list-default", Map.of("name", defaultZone));
        }
    }

    /** Captures the sender's position as pending corner 1 or 2. */
    private final class ZonePosCommand extends MysticCommand {
        private final int index;

        ZonePosCommand(String name, int index) {
            super(AfkModule.this.core, name, "Set AFK zone corner " + (index + 1) + " at your position.");
            this.index = index;
        }

        @Override
        protected void run(MysticCommandSender sender) {
            PlayerRef player = sender.player().orElse(null);
            if (player == null) {
                sender.replyKey("player-only");
                return;
            }
            MysticLocation pos = safeCapture(player);
            if (pos == null) {
                sender.reply("&cCould not read your position; try again.");
                return;
            }
            MysticLocation[] corners = zoneSelections.computeIfAbsent(sender.uuid(), k -> new MysticLocation[2]);
            corners[index] = pos;
            showZoneSelectionPreview(player, corners);
            sender.replyKey(index == 0 ? "afkzone-pos1" : "afkzone-pos2", Map.of("pos", formatPos(pos)));
        }
    }

    private final class ZoneCreateCommand extends MysticCommand {
        private final RequiredArg<String> name = withRequiredArg("name", "Zone name", ArgTypes.STRING);

        ZoneCreateCommand() {
            super(AfkModule.this.core, "create", "Create an AFK zone from your two selected corners.");
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            String zoneName = sender.get(name);
            if (!zoneName.matches(ZONE_NAME_PATTERN)) {
                sender.replyKey("afkzone-invalid-name");
                return;
            }
            if (findZone(zoneName).isPresent()) {
                sender.replyKey("afkzone-exists", Map.of("name", zoneName));
                return;
            }
            MysticLocation[] corners = zoneSelections.get(sender.uuid());
            if (corners == null || corners[0] == null || corners[1] == null) {
                sender.replyKey("afkzone-need-corners");
                return;
            }
            if (corners[0].getWorld() == null || !corners[0].getWorld().equals(corners[1].getWorld())) {
                sender.replyKey("afkzone-cross-world");
                return;
            }
            AfkConfig.Zone zone = new AfkConfig.Zone(zoneName, corners[0], corners[1]);
            config.rewards.zones.add(zone);
            zoneSelections.remove(sender.uuid());
            sender.player().ifPresent(AfkModule.this::clearZoneSelectionPreview);
            saveConfig();
            sender.replyKey("afkzone-created", Map.of("name", zoneName, "size", formatSize(zone)));
            if (config.rewards.enabled && !config.rewards.requireInZone) {
                sender.replyKey("afkzone-zone-not-required-hint");
            }
        }
    }

    private final class ZoneDeleteCommand extends MysticCommand {
        private final RequiredArg<String> name = withRequiredArg("name", "Zone name", ArgTypes.STRING)
                .suggest((commandSender, input, index, result) ->
                        config.rewards.zones.forEach(z -> result.suggest(z.name)));

        ZoneDeleteCommand() {
            super(AfkModule.this.core, "delete", "Delete an AFK zone.");
        }

        @Override
        protected void run(MysticCommandSender sender) {
            String zoneName = sender.get(name);
            Optional<AfkConfig.Zone> zone = findZone(zoneName);
            if (zone.isEmpty()) {
                sender.replyKey("afkzone-unknown", Map.of("name", zoneName));
                return;
            }
            config.rewards.zones.remove(zone.get());
            if (config.rewards.defaultZone != null
                    && config.rewards.defaultZone.equalsIgnoreCase(zone.get().name)) {
                config.rewards.defaultZone = null;
            }
            saveConfig();
            sender.replyKey("afkzone-deleted", Map.of("name", zone.get().name));
        }
    }

    /** Sets or clears ({@code -}) the permission node required to use a zone. */
    private final class ZonePermissionCommand extends MysticCommand {
        private final RequiredArg<String> name = withRequiredArg("name", "Zone name", ArgTypes.STRING)
                .suggest((commandSender, input, index, result) ->
                        config.rewards.zones.forEach(z -> result.suggest(z.name)));
        private final RequiredArg<String> node = withRequiredArg("permission", "Permission node, or - to clear",
                ArgTypes.STRING);

        ZonePermissionCommand() {
            super(AfkModule.this.core, "permission", "Set or clear (-) a zone's required permission.");
        }

        @Override
        protected void run(MysticCommandSender sender) {
            String zoneName = sender.get(name);
            Optional<AfkConfig.Zone> zone = findZone(zoneName);
            if (zone.isEmpty()) {
                sender.replyKey("afkzone-unknown", Map.of("name", zoneName));
                return;
            }
            String value = sender.get(node);
            if ("-".equals(value)) {
                zone.get().permission = null;
                saveConfig();
                sender.replyKey("afkzone-permission-cleared", Map.of("name", zone.get().name));
            } else {
                zone.get().permission = value;
                saveConfig();
                sender.replyKey("afkzone-permission-set",
                        Map.of("name", zone.get().name, "permission", value));
            }
        }
    }

    /** Sets or clears ({@code -}) the zone auto-AFK teleports idle players into. */
    private final class ZoneDefaultCommand extends MysticCommand {
        private final RequiredArg<String> name = withRequiredArg("name", "Zone name, or - to clear",
                ArgTypes.STRING).suggest((commandSender, input, index, result) -> {
                    result.suggest("-");
                    config.rewards.zones.forEach(z -> result.suggest(z.name));
                });

        ZoneDefaultCommand() {
            super(AfkModule.this.core, "default", "Set or clear (-) the default auto-AFK teleport zone.");
        }

        @Override
        protected void run(MysticCommandSender sender) {
            String value = sender.get(name);
            if ("-".equals(value)) {
                config.rewards.defaultZone = null;
                saveConfig();
                sender.replyKey("afkzone-default-cleared");
                return;
            }
            Optional<AfkConfig.Zone> zone = findZone(value);
            if (zone.isEmpty()) {
                sender.replyKey("afkzone-unknown", Map.of("name", value));
                return;
            }
            config.rewards.defaultZone = zone.get().name;
            saveConfig();
            sender.replyKey("afkzone-default-set", Map.of("name", zone.get().name));
        }
    }

    private final class ZoneListCommand extends MysticCommand {
        ZoneListCommand() {
            super(AfkModule.this.core, "list", "List AFK zones.");
        }

        @Override
        protected void run(MysticCommandSender sender) {
            listZones(sender);
        }
    }

    /** Reports which zone (if any) the admin is currently standing in. */
    private final class ZoneCheckCommand extends MysticCommand {
        ZoneCheckCommand() {
            super(AfkModule.this.core, "check", "Show which AFK zone you are standing in.");
        }

        @Override
        protected void run(MysticCommandSender sender) {
            PlayerRef player = sender.player().orElse(null);
            if (player == null) {
                sender.replyKey("player-only");
                return;
            }
            Optional<AfkConfig.Zone> zone = zoneAt(safeCapture(player));
            if (zone.isPresent()) {
                sender.replyKey("afkzone-check-in", Map.of("name", zone.get().name));
            } else {
                sender.replyKey("afkzone-check-out");
            }
        }
    }
}
