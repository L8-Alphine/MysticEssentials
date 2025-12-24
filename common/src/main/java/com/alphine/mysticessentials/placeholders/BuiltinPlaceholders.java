package com.alphine.mysticessentials.placeholders;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class BuiltinPlaceholders {

    private static final Locale LOCALE = Locale.US;
    private static final ZoneId SERVER_ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter DATE_TIME_12H =
            DateTimeFormatter.ofPattern("MMM d yyyy • h:mm a", LOCALE);
    private static final DateTimeFormatter TIME_12H =
            DateTimeFormatter.ofPattern("h:mm a", LOCALE);

    private BuiltinPlaceholders() {}

    public static void registerAll(PlaceholderService svc) {
        // Core / global placeholders
        svc.register((rawKey, ctx) -> {
            if (rawKey == null || rawKey.isEmpty()) return null;
            String key = rawKey.toLowerCase(Locale.ROOT);

            ServerPlayer viewer = ctx.viewer();
            ServerPlayer sender = ctx.sender();
            ServerPlayer primary = viewer != null ? viewer : sender;

            switch (key) {
                // --- Identity ---
                // {player} - viewer's displayname
                case "player" -> {
                    if (primary == null) return "";
                    var dn = primary.getDisplayName();
                    if (dn != null) {
                        String s = dn.getString();
                        if (!s.isEmpty()) return s;
                    }
                    return primary.getGameProfile().getName();
                }

                // kept for compatibility: {sender} -> name of command/chat sender
                case "sender" -> {
                    if (sender == null) return "";
                    return sender.getGameProfile().getName();
                }

                case "uuid" -> {
                    if (primary == null) return "";
                    return primary.getUUID().toString();
                }

                // --- Position: viewer coords only ---
                case "x" -> {
                    if (primary == null) return "0";
                    return String.format(Locale.ROOT, "%.2f", primary.getX());
                }
                case "y" -> {
                    if (primary == null) return "0";
                    return String.format(Locale.ROOT, "%.2f", primary.getY());
                }
                case "z" -> {
                    if (primary == null) return "0";
                    return String.format(Locale.ROOT, "%.2f", primary.getZ());
                }
                case "pos" -> {
                    if (primary == null) return "0, 0, 0";
                    return String.format(
                            Locale.ROOT,
                            "%.2f, %.2f, %.2f",
                            primary.getX(), primary.getY(), primary.getZ()
                    );
                }

                // --- World info ---
                // {world} - viewer's world
                case "world" -> {
                    if (primary == null) return "";
                    return primary.serverLevel().dimension().location().toString();
                }

                // {worldonline} - players in same world as viewer
                case "worldonline" -> {
                    if (primary == null) return "0";
                    Level level = primary.level();
                    long count = ctx.server().getPlayerList().getPlayers()
                            .stream()
                            .filter(p -> p.level() == level)
                            .count();
                    return Long.toString(count);
                }

                // --- Online counts ---
                case "online" -> {
                    int online = ctx.server().getPlayerList().getPlayerCount();
                    return Integer.toString(online);
                }

                case "max" -> {
                    int max = ctx.server().getPlayerList().getMaxPlayers();
                    return Integer.toString(max);
                }

                // {uniqueJoins} - best-effort using profile cache
                case "uniquejoins" -> {
                    var cache = ctx.server().getProfileCache();
                    if (cache != null) {
                        // Collection size of known profiles
                        return Integer.toString(cache.load().size());
                    }
                    return "0";
                }

                // --- Server / platform ---
                case "platform" -> {
                    // NeoForge/Fabric name, or your custom mod name
                    return ctx.server().getServerModName();
                }

                case "version" -> {
                    // Implementation detail may differ between loaders;
                    // this works on dedicated servers.
                    return ctx.server().getServerVersion();
                }

                // --- Time: real-world ---
                // {time} - server real time, 12h with date
                case "time" -> {
                    LocalDateTime now = LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(System.currentTimeMillis()),
                            SERVER_ZONE
                    );
                    return DATE_TIME_12H.format(now);
                }

                // {timelocal} - viewer local time; for now same as server time
                // (Minecraft has no per-player timezone, this is future-proofed)
                case "timelocal" -> {
                    LocalDateTime now = LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(System.currentTimeMillis()),
                            SERVER_ZONE
                    );
                    return DATE_TIME_12H.format(now);
                }

                // --- Time: Minecraft world time ---
                // {servertime} - MC time, 12h (e.g. 3:27 PM)
                case "servertime" -> {
                    Level level;
                    if (primary != null) {
                        level = primary.serverLevel();
                    } else {
                        level = ctx.server().overworld();
                    }
                    long dayTime = level.getDayTime() % 24000L;
                    int hours24 = (int) ((dayTime / 1000L + 6) % 24); // 0 ticks = 6:00
                    int minutes = (int) ((dayTime % 1000L) * 60L / 1000L);

                    int displayHour = hours24 % 12;
                    if (displayHour == 0) displayHour = 12;
                    String amPm = hours24 < 12 ? "AM" : "PM";

                    return String.format(LOCALE, "%d:%02d %s", displayHour, minutes, amPm);
                }

                // --- Health ---
                // {health} - health of viewer (or sender fallback)
                case "health" -> {
                    if (primary instanceof LivingEntity living) {
                        return String.format(Locale.ROOT, "%.1f", living.getHealth());
                    }
                    return "0";
                }

                // --- Playtime ---
                // {playtime} -  from vanilla stats (ticks -> human readable)
                case "playtime" -> {
                    if (primary == null) return "0m";
                    int ticks = primary.getStats()
                            .getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));

                    long totalSeconds = ticks / 20L;
                    long hours = totalSeconds / 3600L;
                    long minutes = (totalSeconds % 3600L) / 60L;

                    if (hours > 0) {
                        return hours + "h " + minutes + "m";
                    }
                    return minutes + "m";
                }

                default -> {
                    return null;
                }
            }
        });

        // Optional: keep these extra viewer-specific shortcuts
        svc.register((key, ctx) -> {
            var v = ctx.viewer();
            if (v == null) return null;
            return switch (key.toLowerCase(Locale.ROOT)) {
                case "viewer" -> v.getGameProfile().getName();
                case "viewer_uuid" -> v.getUUID().toString();
                default -> null;
            };
        });
    }
}
