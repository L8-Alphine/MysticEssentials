package org.hyzionstudios.mysticessentials.core.migration;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.hyzionstudios.mysticessentials.api.model.MysticLocation;
import org.hyzionstudios.mysticessentials.api.model.PlayerProfile;
import org.hyzionstudios.mysticessentials.api.model.Warp;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.modules.kits.KitConfig;
import org.hyzionstudios.mysticessentials.modules.spawn.SpawnConfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

/** File-only migration support for legacy essentials-style Hytale mods. */
public final class LegacyMigrationService {

    private static final Type WARP_MAP_TYPE = new TypeToken<LinkedHashMap<String, Warp>>() {
    }.getType();
    private static final int MAX_WARNINGS = 8;
    private static final String HOMES_KEY = "homes";

    private final MysticCore core;
    private final Set<Path> backups = new HashSet<>();
    private final String backupSuffix = ".bak-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now());

    public LegacyMigrationService(MysticCore core) {
        this.core = core;
    }

    public Report run(Options options) throws IOException {
        Path root = resolveRoot(options.source(), options.path());
        if (!Files.isDirectory(root)) {
            throw new IOException("Source path is not a directory: " + root);
        }

        MutableReport report = new MutableReport(options.source(), root, options.dryRun());
        LegacyData data = new LegacyData();
        scan(root, options.source(), data, report);
        report.homesFound = data.homeCount();
        report.serverWarpsFound = data.serverWarps.size();
        report.playerWarpsFound = data.playerWarps.size();
        report.spawnsFound = (data.globalSpawn == null ? 0 : 1) + data.worldSpawns.size();
        report.kitsFound = data.kits.size();

        if (!options.dryRun()) {
            apply(data, options.replace(), report);
        }
        return report.freeze();
    }

    private Path resolveRoot(Source source, Path requested) {
        if (requested != null) {
            if (requested.isAbsolute()) {
                return requested.normalize();
            }
            Path fromMods = modsRoot().resolve(requested).normalize();
            return Files.exists(fromMods) ? fromMods : Path.of("").toAbsolutePath().resolve(requested).normalize();
        }

        Path mods = modsRoot();
        for (String folder : source.defaultFolders()) {
            Path candidate = mods.resolve(folder);
            if (Files.isDirectory(candidate)) {
                return candidate.normalize();
            }
        }
        return source == Source.AUTO ? mods.normalize() : mods.resolve(source.defaultFolders().get(0)).normalize();
    }

    private Path modsRoot() {
        Path parent = core.paths().root().getParent();
        return parent == null ? core.paths().root() : parent;
    }

    private void scan(Path root, Source source, LegacyData data, MutableReport report) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> !isUnder(path, core.paths().root()))
                    .filter(this::isSupportedFile)
                    .forEach(path -> scanFile(path, source, data, report));
        }
    }

    private boolean isSupportedFile(Path path) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".json") || lower.endsWith(".toml");
    }

    private boolean isUnder(Path path, Path parent) {
        return path.toAbsolutePath().normalize().startsWith(parent.toAbsolutePath().normalize());
    }

    private void scanFile(Path path, Source source, LegacyData data, MutableReport report) {
        report.filesScanned++;
        try {
            String lower = normalizedPath(path);
            if (lower.endsWith(".json")) {
                scanJson(path, source, data);
            } else if (lower.endsWith(".toml")) {
                scanToml(path, data);
            }
        } catch (JsonSyntaxException | IOException | IllegalStateException e) {
            report.filesFailed++;
            report.warn("Could not read " + path.getFileName() + ": " + e.getMessage());
        }
    }

    private void scanJson(Path path, Source source, LegacyData data) throws IOException {
        JsonElement element = Json.readFile(path);
        if (element == null || element.isJsonNull()) {
            return;
        }
        String lower = normalizedPath(path);
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("homes")) {
                parseHomes(path, object.get("homes"), fileOrObjectUuid(path, object).orElse(null),
                        username(object), data);
            }
            if (object.has("warps")) {
                parseWarps(object.get("warps"), false, data);
            }
            if (object.has("playerWarps") || object.has("playerwarps")) {
                parseWarps(firstPresent(object, "playerWarps", "playerwarps"), true, data);
            }
            if (object.has("worldSpawns")) {
                parseWorldSpawns(object.get("worldSpawns"), data);
            }
            if (object.has("globalSpawn")) {
                locationFrom(object.get("globalSpawn")).ifPresent(location -> data.globalSpawn = location);
            }
            if (object.has("spawn")) {
                locationFrom(object.get("spawn")).ifPresent(location -> data.globalSpawn = location);
            }
            if (object.has("kits")) {
                parseKits(object.get("kits"), data);
            }
        }

        if (lower.contains("home")) {
            parseHomes(path, element, fileUuid(path).orElse(null), null, data);
        }
        if (lower.contains("playerwarp") || lower.contains("player-warp") || lower.contains("pwarp")) {
            parseWarps(element, true, data);
        } else if (lower.contains("warp")) {
            parseWarps(element, false, data);
        }
        if (lower.contains("spawn")) {
            parseSpawn(element, data);
        }
        if (lower.contains("kit")) {
            parseKits(element, data);
        }

        if (source == Source.AUTO && element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (looksLikePlayerFile(object)) {
                parseHomes(path, object, fileOrObjectUuid(path, object).orElse(null), username(object), data);
            }
        }
    }

    private void scanToml(Path path, LegacyData data) throws IOException {
        String lower = normalizedPath(path);
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        if (lower.contains("kit")) {
            parseTomlKits(raw, data);
        }
        if (lower.endsWith("config.toml")) {
            parseTomlSpawnSettings(raw, data);
        }
    }

    private boolean looksLikePlayerFile(JsonObject object) {
        return object.has("uuid") || object.has("playerUuid") || object.has("playerId") || object.has("userId");
    }

    private void parseHomes(Path path, JsonElement element, UUID owner, String username, LegacyData data) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonObject()) {
                    JsonObject object = item.getAsJsonObject();
                    UUID itemOwner = ownerUuid(object).orElse(owner);
                    String itemUser = username(object);
                    addHome(itemOwner, itemUser != null ? itemUser : username,
                            homeName(object, "home"), locationFrom(object), data);
                }
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }

        JsonObject object = element.getAsJsonObject();
        UUID objectOwner = ownerUuid(object).orElse(owner);
        String objectUser = username(object);
        if (object.has("homes")) {
            parseHomes(path, object.get("homes"), objectOwner, objectUser != null ? objectUser : username, data);
            return;
        }

        Optional<MysticLocation> direct = locationFrom(object);
        if (direct.isPresent()) {
            addHome(objectOwner, objectUser != null ? objectUser : username,
                    homeName(object, basename(path)), direct, data);
            return;
        }

        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (isMetadataKey(entry.getKey())) {
                continue;
            }
            UUID entryOwner = parseUuid(entry.getKey()).orElse(objectOwner);
            if (entry.getValue().isJsonObject()) {
                JsonObject child = entry.getValue().getAsJsonObject();
                if (child.has("homes")) {
                    parseHomes(path, child.get("homes"), ownerUuid(child).orElse(entryOwner),
                            username(child), data);
                    continue;
                }
            }
            addHome(entryOwner, objectUser != null ? objectUser : username, safeName(entry.getKey(), "home"),
                    locationFrom(entry.getValue()), data);
        }
    }

    private void addHome(UUID owner, String username, String name, Optional<MysticLocation> location,
            LegacyData data) {
        if (owner == null || location.isEmpty()) {
            return;
        }
        LegacyPlayerHomes homes = data.homes.computeIfAbsent(owner, key -> new LegacyPlayerHomes(username));
        if (username != null && !username.isBlank()) {
            homes.username = username;
        }
        homes.homes.put(safeName(name, "home"), location.get());
    }

    private void parseWarps(JsonElement element, boolean playerWarp, LegacyData data) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                addWarp(null, item, playerWarp, data);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        if (object.has("warps")) {
            parseWarps(object.get("warps"), playerWarp, data);
            return;
        }
        if (object.has("playerWarps") || object.has("playerwarps")) {
            parseWarps(firstPresent(object, "playerWarps", "playerwarps"), true, data);
            return;
        }
        if (locationFrom(object).isPresent()) {
            addWarp(null, object, playerWarp, data);
            return;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!isMetadataKey(entry.getKey())) {
                addWarp(entry.getKey(), entry.getValue(), playerWarp, data);
            }
        }
    }

    private void addWarp(String fallbackName, JsonElement element, boolean playerWarp, LegacyData data) {
        Optional<MysticLocation> location = locationFrom(element);
        if (location.isEmpty()) {
            return;
        }
        JsonObject object = element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        String name = safeName(warpName(object, fallbackName), "warp");
        Warp warp = new Warp(name, location.get());
        string(object, "category", "group").ifPresent(warp::setCategory);
        string(object, "description", "desc", "message").ifPresent(warp::setDescription);
        string(object, "permission", "permissionNode").ifPresent(warp::setPermission);
        number(object, "cost", "price").ifPresent(value -> warp.setCost(value.doubleValue()));
        visibility(object).ifPresent(warp::setVisibility);
        ownerUuid(object).ifPresent(owner -> {
            warp.setOwner(owner.toString());
            string(object, "ownerName", "playerName", "username").ifPresent(warp::setOwnerName);
        });
        if (playerWarp || warp.getOwner() != null) {
            data.playerWarps.put(key(name), warp);
        } else {
            data.serverWarps.put(key(name), warp);
        }
    }

    private void parseSpawn(JsonElement element, LegacyData data) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("globalSpawn")) {
                locationFrom(object.get("globalSpawn")).ifPresent(location -> data.globalSpawn = location);
            }
            if (object.has("spawn")) {
                locationFrom(object.get("spawn")).ifPresent(location -> data.globalSpawn = location);
            }
            if (object.has("worldSpawns")) {
                parseWorldSpawns(object.get("worldSpawns"), data);
            }
        }
        locationFrom(element).ifPresent(location -> {
            if (data.globalSpawn == null) {
                data.globalSpawn = location;
            }
        });
    }

    private void parseWorldSpawns(JsonElement element, LegacyData data) {
        if (element == null || !element.isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            locationFrom(entry.getValue()).ifPresent(location ->
                    data.worldSpawns.put(safeName(entry.getKey(), location.getWorld()), location));
        }
    }

    private void parseKits(JsonElement element, LegacyData data) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonObject()) {
                    JsonObject object = item.getAsJsonObject();
                    addKit(kitName(object, "kit"), object, data);
                }
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        if (object.has("kits")) {
            parseKits(object.get("kits"), data);
            return;
        }
        if (object.has("items") || object.has("contents")) {
            addKit(kitName(object, "kit"), object, data);
            return;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (entry.getValue().isJsonObject()) {
                addKit(safeName(entry.getKey(), "kit"), entry.getValue().getAsJsonObject(), data);
            }
        }
    }

    private void addKit(String name, JsonObject object, LegacyData data) {
        KitConfig.Kit kit = new KitConfig.Kit();
        string(object, "description", "desc").ifPresent(value -> kit.description = value);
        number(object, "cooldownSeconds", "cooldown", "delay").ifPresent(value ->
                kit.cooldownSeconds = seconds(value.longValue()));
        number(object, "requiredOnlineSeconds", "playtimeRequired", "requiredPlaytime").ifPresent(value ->
                kit.requiredOnlineSeconds = seconds(value.longValue()));
        bool(object, "requirePermission", "permissionRequired").ifPresent(value -> kit.requirePermission = value);
        number(object, "cost", "price").ifPresent(value -> kit.cost = value.doubleValue());

        JsonElement items = firstPresent(object, "items", "contents", "stack");
        if (items != null && items.isJsonArray()) {
            for (JsonElement item : items.getAsJsonArray()) {
                parseKitItem(item).ifPresent(kit.items::add);
            }
        }
        if (!kit.items.isEmpty()) {
            data.kits.put(key(name), kit);
        }
    }

    private Optional<KitConfig.KitItem> parseKitItem(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return Optional.empty();
        }
        if (element.isJsonPrimitive()) {
            return Optional.of(new KitConfig.KitItem(element.getAsString(), 1));
        }
        if (!element.isJsonObject()) {
            return Optional.empty();
        }
        JsonObject object = element.getAsJsonObject();
        Optional<String> itemId = string(object, "itemId", "id", "item", "material", "type", "identifier", "name");
        if (itemId.isEmpty()) {
            return Optional.empty();
        }
        int quantity = number(object, "quantity", "amount", "count").map(Number::intValue).orElse(1);
        return Optional.of(new KitConfig.KitItem(itemId.get(), Math.max(1, quantity)));
    }

    private void parseTomlKits(String raw, LegacyData data) {
        String section = "";
        String currentKit = null;
        KitConfig.Kit kit = null;
        KitConfig.KitItem pendingItem = null;

        for (String original : raw.split("\\R")) {
            String line = original.split("#", 2)[0].trim();
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                if (pendingItem != null && pendingItem.itemId != null && kit != null) {
                    kit.items.add(pendingItem);
                }
                pendingItem = null;
                section = line.replace("[", "").replace("]", "").trim();
                currentKit = kitNameFromSection(section);
                if (currentKit != null) {
                    kit = data.kits.computeIfAbsent(key(currentKit), ignored -> new KitConfig.Kit());
                    if (section.toLowerCase(Locale.ROOT).contains("item")) {
                        pendingItem = new KitConfig.KitItem();
                    }
                }
                continue;
            }
            if (kit == null || currentKit == null || !line.contains("=")) {
                continue;
            }
            String[] parts = line.split("=", 2);
            String key = parts[0].trim().toLowerCase(Locale.ROOT);
            String value = unquote(parts[1].trim());
            if (pendingItem != null) {
                if (key.equals("item") || key.equals("itemid") || key.equals("id") || key.equals("material")) {
                    pendingItem.itemId = value;
                } else if (key.equals("amount") || key.equals("quantity") || key.equals("count")) {
                    pendingItem.quantity = parseLong(value).map(Long::intValue).orElse(1);
                }
            } else if (key.equals("description")) {
                kit.description = value;
            } else if (key.equals("cooldown") || key.equals("cooldownseconds")) {
                kit.cooldownSeconds = parseLong(value).map(this::seconds).orElse(kit.cooldownSeconds);
            } else if (key.equals("cost") || key.equals("price")) {
                kit.cost = parseDouble(value).orElse(kit.cost);
            } else if (key.equals("requirepermission") || key.equals("permissionrequired")) {
                kit.requirePermission = Boolean.parseBoolean(value);
            }
        }
        if (pendingItem != null && pendingItem.itemId != null && kit != null) {
            kit.items.add(pendingItem);
        }
        data.kits.entrySet().removeIf(entry -> entry.getValue().items.isEmpty());
    }

    private void parseTomlSpawnSettings(String raw, LegacyData data) {
        // The EssentialsCore config stores spawn booleans in TOML. Mystic imports
        // those settings only when a spawn file/location is also present.
        data.teleportOnFirstJoin = findTomlBoolean(raw, "first-join").orElse(data.teleportOnFirstJoin);
        data.teleportOnJoin = findTomlBoolean(raw, "every-join").orElse(data.teleportOnJoin);
    }

    private void apply(LegacyData data, boolean replace, MutableReport report) throws IOException {
        applyHomes(data, replace, report);
        applyWarps(data.serverWarps, core.paths().moduleDataDir("warps").resolve("server.json"), replace, report);
        applyWarps(data.playerWarps, core.paths().moduleDataDir("warps").resolve("playerwarps.json"), replace, report);
        applySpawns(data, replace, report);
        applyKits(data, replace, report);
    }

    private void applyHomes(LegacyData data, boolean replace, MutableReport report) {
        for (Map.Entry<UUID, LegacyPlayerHomes> playerEntry : data.homes.entrySet()) {
            UUID uuid = playerEntry.getKey();
            LegacyPlayerHomes imported = playerEntry.getValue();
            PlayerProfile profile = core.getPlayerProfileService().getCached(uuid).orElseGet(() ->
                    loadProfile(uuid, imported.username));
            JsonObject homes = profile.getModuleData().computeIfAbsent(HOMES_KEY, ignored -> new JsonObject());
            for (Map.Entry<String, MysticLocation> home : imported.homes.entrySet()) {
                String name = safeName(home.getKey(), "home");
                if (homes.has(name) && !replace) {
                    report.skipped++;
                    continue;
                }
                if (homes.has(name)) {
                    report.replaced++;
                } else {
                    report.created++;
                }
                homes.add(name, Json.toTree(home.getValue()));
            }
            if (imported.username != null && !imported.username.isBlank()) {
                profile.setUsername(imported.username);
                core.getStorageService().save("usernames", imported.username.toLowerCase(Locale.ROOT),
                        Json.toTree(uuid.toString())).join();
            }
            core.getPlayerProfileService().save(profile).join();
        }
    }

    private PlayerProfile loadProfile(UUID uuid, String username) {
        JsonElement existing = core.getStorageService().load("players", uuid.toString()).join();
        PlayerProfile profile = existing == null ? null : Json.fromJson(existing, PlayerProfile.class);
        if (profile == null) {
            profile = PlayerProfile.create(uuid, username == null ? "legacy-" + uuid.toString().substring(0, 8) : username);
        }
        return profile;
    }

    private void applyWarps(Map<String, Warp> imported, Path file, boolean replace, MutableReport report)
            throws IOException {
        if (imported.isEmpty()) {
            return;
        }
        Map<String, Warp> existing = readWarpMap(file);
        backup(file);
        for (Map.Entry<String, Warp> entry : imported.entrySet()) {
            if (existing.containsKey(entry.getKey()) && !replace) {
                report.skipped++;
                continue;
            }
            if (existing.containsKey(entry.getKey())) {
                report.replaced++;
            } else {
                report.created++;
            }
            existing.put(entry.getKey(), entry.getValue());
        }
        Json.writeFile(file, existing);
    }

    private Map<String, Warp> readWarpMap(Path file) throws IOException {
        JsonElement element = Json.readFile(file);
        Map<String, Warp> loaded = element == null ? null : Json.gson().fromJson(element, WARP_MAP_TYPE);
        return loaded == null ? new LinkedHashMap<>() : new LinkedHashMap<>(loaded);
    }

    private void applySpawns(LegacyData data, boolean replace, MutableReport report) throws IOException {
        if (data.globalSpawn == null && data.worldSpawns.isEmpty() && data.teleportOnFirstJoin == null
                && data.teleportOnJoin == null) {
            return;
        }
        Path file = core.paths().moduleConfigFile("spawn");
        SpawnConfig config = Json.readFile(file, SpawnConfig.class);
        if (config == null) {
            config = new SpawnConfig();
        }
        if (config.worldSpawns == null) {
            config.worldSpawns = new LinkedHashMap<>();
        }
        backup(file);
        if (data.globalSpawn != null) {
            if (config.globalSpawn != null && !replace) {
                report.skipped++;
            } else {
                if (config.globalSpawn == null) {
                    report.created++;
                } else {
                    report.replaced++;
                }
                config.globalSpawn = data.globalSpawn;
            }
        }
        for (Map.Entry<String, MysticLocation> entry : data.worldSpawns.entrySet()) {
            if (config.worldSpawns.containsKey(entry.getKey()) && !replace) {
                report.skipped++;
                continue;
            }
            if (config.worldSpawns.containsKey(entry.getKey())) {
                report.replaced++;
            } else {
                report.created++;
            }
            config.worldSpawns.put(entry.getKey(), entry.getValue());
        }
        if (data.teleportOnFirstJoin != null) {
            config.teleportOnFirstJoin = data.teleportOnFirstJoin;
        }
        if (data.teleportOnJoin != null) {
            config.teleportOnJoin = data.teleportOnJoin;
        }
        Json.writeFile(file, config);
    }

    private void applyKits(LegacyData data, boolean replace, MutableReport report) throws IOException {
        if (data.kits.isEmpty()) {
            return;
        }
        Path file = core.paths().moduleConfigFile("kits");
        KitConfig config = Json.readFile(file, KitConfig.class);
        if (config == null) {
            config = new KitConfig();
        }
        if (config.kits == null) {
            config.kits = new LinkedHashMap<>();
        }
        backup(file);
        for (Map.Entry<String, KitConfig.Kit> entry : data.kits.entrySet()) {
            if (config.kits.containsKey(entry.getKey()) && !replace) {
                report.skipped++;
                continue;
            }
            if (config.kits.containsKey(entry.getKey())) {
                report.replaced++;
            } else {
                report.created++;
            }
            config.kits.put(entry.getKey(), entry.getValue());
        }
        Json.writeFile(file, config);
    }

    private void backup(Path file) throws IOException {
        if (!Files.exists(file) || !backups.add(file.toAbsolutePath().normalize())) {
            return;
        }
        Files.copy(file, file.resolveSibling(file.getFileName() + backupSuffix), StandardCopyOption.COPY_ATTRIBUTES);
    }

    private Optional<MysticLocation> locationFrom(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return Optional.empty();
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            if (array.size() >= 3 && primitives(array, 0, 1, 2)) {
                double x = array.get(0).getAsDouble();
                double y = array.get(1).getAsDouble();
                double z = array.get(2).getAsDouble();
                float yaw = array.size() > 3 && array.get(3).isJsonPrimitive() ? array.get(3).getAsFloat() : 0.0f;
                float pitch = array.size() > 4 && array.get(4).isJsonPrimitive() ? array.get(4).getAsFloat() : 0.0f;
                return Optional.of(new MysticLocation("default", x, y, z, yaw, pitch));
            }
            return Optional.empty();
        }
        if (!element.isJsonObject()) {
            return Optional.empty();
        }

        JsonObject object = element.getAsJsonObject();
        JsonObject coordinates = coordinateObject(object).orElse(null);
        if (coordinates == null) {
            for (String key : List.of("location", "loc", "position", "pos", "transform", "spawn")) {
                if (object.has(key)) {
                    Optional<MysticLocation> nested = locationFrom(object.get(key));
                    if (nested.isPresent()) {
                        MysticLocation location = nested.get();
                        if (isDefaultWorld(location.getWorld())) {
                            string(object, "world", "worldName", "worldId", "universe", "realm")
                                    .ifPresent(location::setWorld);
                        }
                        return Optional.of(location);
                    }
                }
            }
            return Optional.empty();
        }

        Optional<Double> x = number(coordinates, "x", "blockX").map(Number::doubleValue);
        Optional<Double> y = number(coordinates, "y", "blockY").map(Number::doubleValue);
        Optional<Double> z = number(coordinates, "z", "blockZ").map(Number::doubleValue);
        if (x.isEmpty() || y.isEmpty() || z.isEmpty()) {
            return Optional.empty();
        }
        String world = string(object, "world", "worldName", "worldId", "universe", "realm")
                .or(() -> string(coordinates, "world", "worldName", "worldId"))
                .orElse("default");
        float yaw = number(object, "yaw", "rotationYaw")
                .or(() -> rotationNumber(object, "yaw", "y"))
                .map(Number::floatValue)
                .orElse(0.0f);
        float pitch = number(object, "pitch", "rotationPitch")
                .or(() -> rotationNumber(object, "pitch", "x"))
                .map(Number::floatValue)
                .orElse(0.0f);
        return Optional.of(new MysticLocation(world, x.get(), y.get(), z.get(), yaw, pitch));
    }

    private Optional<JsonObject> coordinateObject(JsonObject object) {
        if (hasCoordinates(object)) {
            return Optional.of(object);
        }
        for (String key : List.of("position", "pos", "coordinates", "coords", "blockPosition")) {
            if (object.has(key) && object.get(key).isJsonObject() && hasCoordinates(object.getAsJsonObject(key))) {
                return Optional.of(object.getAsJsonObject(key));
            }
        }
        return Optional.empty();
    }

    private boolean hasCoordinates(JsonObject object) {
        return number(object, "x", "blockX").isPresent()
                && number(object, "y", "blockY").isPresent()
                && number(object, "z", "blockZ").isPresent();
    }

    private boolean primitives(JsonArray array, int... indexes) {
        for (int index : indexes) {
            if (array.size() <= index || !array.get(index).isJsonPrimitive()) {
                return false;
            }
        }
        return true;
    }

    private boolean isDefaultWorld(String world) {
        return world == null || world.isBlank() || world.equals("default");
    }

    private Optional<Warp.Visibility> visibility(JsonObject object) {
        Optional<String> raw = string(object, "visibility", "mode");
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Warp.Visibility.valueOf(raw.get().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            if (raw.get().equalsIgnoreCase("private")) {
                return Optional.of(Warp.Visibility.HIDDEN);
            }
            return Optional.empty();
        }
    }

    private JsonElement firstPresent(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key)) {
                return object.get(key);
            }
        }
        return null;
    }

    private Optional<UUID> fileOrObjectUuid(Path path, JsonObject object) {
        return ownerUuid(object).or(() -> fileUuid(path));
    }

    private Optional<UUID> ownerUuid(JsonObject object) {
        return string(object, "uuid", "playerUuid", "playerId", "userId", "owner", "ownerUuid", "ownerId")
                .flatMap(this::parseUuid);
    }

    private Optional<UUID> fileUuid(Path path) {
        Path fileName = path.getFileName();
        if (fileName != null) {
            String name = fileName.toString().replaceFirst("\\.[^.]+$", "");
            Optional<UUID> uuid = parseUuid(name);
            if (uuid.isPresent()) {
                return uuid;
            }
        }
        Path parent = path.getParent();
        while (parent != null) {
            Path name = parent.getFileName();
            if (name != null) {
                Optional<UUID> uuid = parseUuid(name.toString());
                if (uuid.isPresent()) {
                    return uuid;
                }
            }
            parent = parent.getParent();
        }
        return Optional.empty();
    }

    private Optional<UUID> parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(unquote(raw)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private String username(JsonObject object) {
        return string(object, "username", "playerName", "ownerName").orElse(null);
    }

    private String homeName(JsonObject object, String fallback) {
        return string(object, "homeName", "name", "id", "key").orElse(fallback);
    }

    private String warpName(JsonObject object, String fallback) {
        return string(object, "warpName", "name", "id", "key").orElse(fallback);
    }

    private String kitName(JsonObject object, String fallback) {
        return string(object, "kitName", "name", "id", "key").orElse(fallback);
    }

    private String kitNameFromSection(String section) {
        String clean = section.replace("[", "").replace("]", "").trim();
        String[] parts = clean.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equalsIgnoreCase("kits") && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        return parts.length == 1 && !parts[0].equalsIgnoreCase("kits") ? parts[0] : null;
    }

    private Optional<String> string(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key) && object.get(key).isJsonPrimitive()) {
                String value = object.get(key).getAsString();
                if (value != null && !value.isBlank()) {
                    return Optional.of(value.trim());
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Number> number(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key) && object.get(key).isJsonPrimitive()) {
                JsonPrimitive primitive = object.getAsJsonPrimitive(key);
                try {
                    if (primitive.isNumber()) {
                        return Optional.of(primitive.getAsNumber());
                    }
                    if (primitive.isString()) {
                        Optional<Double> parsed = parseDouble(primitive.getAsString());
                        if (parsed.isPresent()) {
                            return Optional.of(parsed.get());
                        }
                    }
                } catch (NumberFormatException ignored) {
                    // Try the next key.
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Number> rotationNumber(JsonObject object, String... keys) {
        if (!object.has("rotation") || !object.get("rotation").isJsonObject()) {
            return Optional.empty();
        }
        return number(object.getAsJsonObject("rotation"), keys);
    }

    private Optional<Boolean> bool(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key) && object.get(key).isJsonPrimitive()) {
                return Optional.of(object.get(key).getAsBoolean());
            }
        }
        return Optional.empty();
    }

    private Optional<Double> parseDouble(String raw) {
        try {
            return Optional.of(Double.parseDouble(unquote(raw)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private Optional<Long> parseLong(String raw) {
        try {
            return Optional.of(Long.parseLong(unquote(raw)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private long seconds(long value) {
        return value > 100_000L ? value / 1000L : value;
    }

    private Optional<Boolean> findTomlBoolean(String raw, String key) {
        String prefix = key.toLowerCase(Locale.ROOT) + " =";
        for (String original : raw.split("\\R")) {
            String line = original.split("#", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (line.startsWith(prefix)) {
                return Optional.of(Boolean.parseBoolean(line.substring(prefix.length()).trim()));
            }
        }
        return Optional.empty();
    }

    private String basename(Path path) {
        Path file = path.getFileName();
        if (file == null) {
            return "home";
        }
        return file.toString().replaceFirst("\\.[^.]+$", "");
    }

    private boolean isMetadataKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.equals("uuid")
                || normalized.equals("playeruuid")
                || normalized.equals("playerid")
                || normalized.equals("userid")
                || normalized.equals("username")
                || normalized.equals("playername")
                || normalized.equals("owner")
                || normalized.equals("owneruuid")
                || normalized.equals("ownername")
                || normalized.equals("metadata");
    }

    private String safeName(String value, String fallback) {
        String candidate = value == null || value.isBlank() ? fallback : value;
        candidate = candidate == null || candidate.isBlank() ? "imported" : candidate;
        return candidate.trim().replaceAll("\\s+", "_");
    }

    private String key(String value) {
        return safeName(value, "imported").toLowerCase(Locale.ROOT);
    }

    private String unquote(String value) {
        String trimmed = value == null ? "" : value.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String normalizedPath(Path path) {
        return path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    public enum Source {
        AUTO("auto", List.of("EssentialsPlus", "Hyssentials", "EliteEssentials", "Essentials", "HyEssentialsX")),
        ESSENTIALS_PLUS("essentialsplus", List.of("EssentialsPlus")),
        HYSSENTIALS("hyssentials", List.of("Hyssentials")),
        ELITE_ESSENTIALS("eliteessentials", List.of("EliteEssentials")),
        ESSENTIALS("essentials", List.of("Essentials")),
        HYESSENTIALSX("hyessentialsx", List.of("HyEssentialsX", "hyessentialsx"));

        private final String id;
        private final List<String> defaultFolders;

        Source(String id, List<String> defaultFolders) {
            this.id = id;
            this.defaultFolders = defaultFolders;
        }

        public String id() {
            return id;
        }

        List<String> defaultFolders() {
            return defaultFolders;
        }

        public static Source from(String value) {
            String normalized = value.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
            for (Source source : values()) {
                if (source.id.replace("_", "").equals(normalized)
                        || source.name().toLowerCase(Locale.ROOT).replace("_", "").equals(normalized)) {
                    return source;
                }
            }
            return null;
        }
    }

    public record Options(Source source, Path path, boolean dryRun, boolean replace) {
    }

    public record Report(Source source, Path root, boolean dryRun, int filesScanned, int filesFailed,
            int homesFound, int serverWarpsFound, int playerWarpsFound, int spawnsFound, int kitsFound,
            int created, int replaced, int skipped, List<String> warnings) {
    }

    private static final class MutableReport {
        private final Source source;
        private final Path root;
        private final boolean dryRun;
        private final List<String> warnings = new ArrayList<>();
        private int filesScanned;
        private int filesFailed;
        private int homesFound;
        private int serverWarpsFound;
        private int playerWarpsFound;
        private int spawnsFound;
        private int kitsFound;
        private int created;
        private int replaced;
        private int skipped;

        private MutableReport(Source source, Path root, boolean dryRun) {
            this.source = source;
            this.root = root;
            this.dryRun = dryRun;
        }

        private void warn(String warning) {
            if (warnings.size() < MAX_WARNINGS) {
                warnings.add(warning);
            }
        }

        private Report freeze() {
            return new Report(source, root, dryRun, filesScanned, filesFailed, homesFound, serverWarpsFound,
                    playerWarpsFound, spawnsFound, kitsFound, created, replaced, skipped, List.copyOf(warnings));
        }
    }

    private static final class LegacyData {
        private final Map<UUID, LegacyPlayerHomes> homes = new LinkedHashMap<>();
        private final Map<String, Warp> serverWarps = new LinkedHashMap<>();
        private final Map<String, Warp> playerWarps = new LinkedHashMap<>();
        private final Map<String, MysticLocation> worldSpawns = new LinkedHashMap<>();
        private final Map<String, KitConfig.Kit> kits = new LinkedHashMap<>();
        private MysticLocation globalSpawn;
        private Boolean teleportOnFirstJoin;
        private Boolean teleportOnJoin;

        private int homeCount() {
            return homes.values().stream().mapToInt(value -> value.homes.size()).sum();
        }
    }

    private static final class LegacyPlayerHomes {
        private String username;
        private final Map<String, MysticLocation> homes = new HashMap<>();

        private LegacyPlayerHomes(String username) {
            this.username = username;
        }
    }
}
