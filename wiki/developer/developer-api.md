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
| `PlaytimeService` | `getPlaytimeService()` | Live total, active, idle, and current-session seconds |
| `MessageService` | `getMessageService()` | Placeholder and color formatting |
| `PlaceholderService` | `getPlaceholderService()` | Internal placeholders and PlaceholderAPI bridge |
| `EconomyService` | `getEconomyService()` | VaultUnlocked-backed balance/cost/payout helpers |
| `PermissionService` | `getPermissionService()` | Permission checks, LuckPerms metadata, numeric limits |
| `TeleportService` | `getTeleportService()` | Central teleport pipeline |
| `EventBus` | `getEventBus()` | Lightweight synchronous addon events |
| `ItemInspectionService` | `getItemInspectionService()` | Shared, provider-extensible item classification and detail model |
| `NotificationService` | `getNotificationService()` | Unified targeted delivery, history, filters, audiences, and actions |
| `CustomUiService` | `getCustomUiService()` | UI compiler, registry, sessions, bindings, patches, and typed action router |

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

Built-in playtime names include `playtime_total`, `playtime_active`,
`playtime_idle`, `playtime_session`, and `_seconds` / `_hours` variants. New
registered placeholders are advertised automatically, including the
`%mysticessentials_<name>%` alias, and late PlaceholderAPI startup is retried.

## Item inspection providers

Register an `ItemViewProvider` to add classification, statistics, modifiers,
requirements, lore, properties, or a custom section for items owned by your mod:

```java
api.getItemInspectionService().registerProvider(new MyItemViewProvider());
```

Providers declare an id and priority, decide whether they support an `ItemStack`
and context, then populate the shared `ItemViewBuilder`. The same normalized
model is used by chat item links and other integrations. Provider failures are
isolated by default, and a section with no data is omitted from the UI.

## Notifications and audiences

Use `NotificationService` instead of sending independent titles and sounds:

```java
Notification notice = Notification.builder()
        .category(NotificationCategory.EVENT)
        .priority(NotificationPriority.IMPORTANT)
        .title("Tournament")
        .message("Registration is open.")
        .action(NotificationAction.command("/tournament"))
        .build();

api.getNotificationService().send(notice, NotificationAudience.all());
```

Built-in audiences cover players, sets, permissions, worlds, channels, nearby
players, staff, and predicates. A guild/party/region addon can call
`registerAudienceResolver(type, resolver)`. Addons may also register Notification
Center filters. Delivery then follows server profiles, player preferences,
critical-bypass rules, and history policy.

## Mention scopes

Relationship-owning mods can add a real option to every player's mention-scope
picker with `ChatService.registerMentionScope(MentionScopeProvider)`. Implement
an id, display name, order, availability check, and `allows(sender, target)`.
Unavailable providers are hidden. Channel ownership/moderator changes are
published as `ChannelOwnershipTransferredEvent` and
`ChannelModeratorChangedEvent`; voice integrations may register a
`ChannelVoicePresenceProvider`.

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
