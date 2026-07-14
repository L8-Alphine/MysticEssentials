# Developer API

Mystic Essentials exposes a stable, service-based API. Addons should depend on the `api` package and avoid concrete implementation classes under `core`, `modules`, or `platform`.

## Accessing the API

Use:

```java
MysticEssentialsAPI api = MysticEssentialsProvider.get();
```

`get()` throws `IllegalStateException` if called before Mystic Essentials has started or after it has shut down. If your addon loads independently, check:

```java
if (MysticEssentialsProvider.isAvailable()) {
    MysticEssentialsAPI api = MysticEssentialsProvider.get();
}
```

Declare Mystic Essentials as a dependency when your addon requires it.

## Core services

These services are always available:

| Service | Method | Purpose |
| --- | --- | --- |
| `ModuleManager` | `getModuleManager()` | Query, register, reload, and inspect modules |
| `StorageService` | `getStorageService()` | Namespaced JSON document storage |
| `PlayerProfileService` | `getPlayerProfileService()` | Profiles, username lookup, playtime, last locations |
| `MessageService` | `getMessageService()` | Placeholder and color formatting |
| `PlaceholderService` | `getPlaceholderService()` | Internal placeholders and PlaceholderAPI bridge |
| `EconomyService` | `getEconomyService()` | VaultUnlocked-backed balance/cost/payout helpers |
| `PermissionService` | `getPermissionService()` | Permission checks, LuckPerms metadata, numeric limits |
| `TeleportService` | `getTeleportService()` | Central teleport pipeline |
| `EventBus` | `getEventBus()` | Lightweight synchronous addon events |

## Module services

These may be `null` when the owning module is disabled:

| Service | Method | Owning module |
| --- | --- | --- |
| `SpawnService` | `getSpawnService()` | `spawn` |
| `WarpService` | `getWarpService()` | `warps` |
| `MailService` | `getMailService()` | `mail` |
| `AfkService` | `getAfkService()` | `afk` |
| `ChatService` | `getChatService()` | `chat` |
| `AnnouncementService` | `getAnnouncementService()` | `announcements` |

Always guard module services:

```java
MysticEssentialsAPI api = MysticEssentialsProvider.get();
if (api.getModuleManager().isEnabled("warps") && api.getWarpService() != null) {
    api.getWarpService().listAllPlayerWarps();
}
```

## Teleporting players

Use `TeleportService` rather than moving entities directly. This preserves warmups, cooldowns, costs, cancellation, and back-location tracking.

```java
TeleportRequest request = TeleportRequest.builder()
        .type("my-addon")
        .target(destination)
        .warmupSeconds(3)
        .cooldownKey("my-addon")
        .cooldownSeconds(10)
        .cost(25.0)
        .build();

api.getTeleportService().teleport(player, request)
        .thenAccept(result -> {
            if (result == TeleportService.Result.SUCCESS) {
                // Teleport completed.
            }
        });
```

Use `teleportNow(player, destination)` only for admin or system moves where checks should be skipped.

## Random Teleport

The Teleportation module exposes `RandomTeleportService` (obtain it from the Teleportation module; it is `null` when the module is disabled). Use it to trigger RTP, search destinations without moving anyone, and register custom safety logic.

```java
RtpRequest request = RtpRequest.builder(playerUuid)
        .profileId("default-wilderness")
        .force(false)
        .build();

randomTeleportService.teleport(request)
        .thenAccept(result -> {
            if (result.isSuccess()) {
                // Player was moved.
            }
        });

// Find a spot without teleporting:
randomTeleportService.findDestination(RtpDestinationRequest.of("default-wilderness"));
```

The service never completes exceptionally for an expected outcome (no safe spot, on cooldown, insufficient funds) — inspect `RtpResult.status()` instead. Extend the search with:

- `registerValidator(RtpDestinationValidator)` — reject or accept candidate locations with custom rules.
- `registerExclusionProvider(RtpExclusionProvider)` — contribute claim/region keep-out areas (e.g. from another mod).

RTP publishes a set of events through the event bus for the full lifecycle: `RtpRequestEvent`, `RtpSearchStartEvent`, `RtpDestinationFoundEvent`, `RtpCandidateRejectedEvent`, `RtpWarmupStartEvent`, `RtpPreTeleportEvent`, `RtpCompleteEvent`, `RtpCancelledEvent`, and `RtpFailedEvent`.

## Storage

`StorageService` stores JSON documents by namespace and key:

```java
JsonObject object = new JsonObject();
object.addProperty("value", "example");

api.getStorageService().save("my_addon", playerUuid.toString(), object);
api.getStorageService().load("my_addon", playerUuid.toString());
```

The same code works with JSON, MySQL, or MariaDB storage.

## Placeholders

Register internal placeholders:

```java
api.getPlaceholderService().register("my_addon_score", (uuid, arg) -> {
    return Integer.toString(scoreFor(uuid));
});
```

Players and configs can then use:

```text
{my_addon_score}
```

When PlaceholderAPI is installed, Mystic exposes its placeholders as `%mystic_<name>%`.

## Messages

Use `MessageService` for consistent formatting:

```java
api.getMessageService().send(player, "&aSaved &f{thing}&a!");
```

For contextual placeholders:

```java
Message message = api.getMessageService().formatFor(player.getUuid(),
        "{luckperms_prefix}{player_name} &7opened the menu");
player.sendMessage(message);
```

## Event bus

Subscribe to Mystic events:

```java
EventBus.Subscription sub = api.getEventBus().subscribe(MyEvent.class, event -> {
    // Handle event.
});

sub.close();
```

Listeners run synchronously on the calling thread. Do not block event handlers with slow I/O.

## Registering addon modules

Implement `MysticModule` when your addon wants to participate in Mystic's lifecycle.

```java
public final class MyAddonModule implements MysticModule {
    private MysticEssentialsAPI api;

    public String id() { return "myaddon"; }
    public String name() { return "My Addon"; }
    public String version() { return "1.0.0"; }

    public void onLoad(MysticEssentialsAPI api) {
        this.api = api;
    }

    public void onEnable() {
        // Register commands, listeners, tasks, services.
    }

    public void onDisable() {
        // Flush and release resources.
    }

    public void onReload() {
        // Reload config.
    }
}
```

Register at runtime:

```java
api.getModuleManager().registerExternalModule(new MyAddonModule());
```

Explicit `false` entries in `config.json -> modules` are respected.

## Hytale threading note

Hytale server entity/component access is world-threaded. Mystic's platform layer handles this internally for its own features. Addons should prefer Mystic services when possible. If you must touch Hytale ECS components directly, do so on the correct world/entity thread using the platform approach from the core implementation.