# Storage

Mystic Essentials stores all of its data — homes, warps, mail, vaults, snapshots, and per-module state — through one storage layer. Every module uses the same namespaced document API, so switching the backing store changes nothing about how features behave.

## Providers

The provider is set in the main config, `mods/MysticEssentials/config.json`:

```json
"storage": {
  "provider": "json"
}
```

| Provider | Description |
| --- | --- |
| `json` | Flat JSON files under `mods/MysticEssentials/data/`. Zero setup; best for single servers |
| `mysql` | MySQL database via a HikariCP connection pool |
| `mariadb` | MariaDB, using the same SQL backend |

SQL settings:

| Path | Default | Description |
| --- | --- | --- |
| `storage.mysql.host` | `"localhost"` | SQL host |
| `storage.mysql.port` | `3306` | SQL port |
| `storage.mysql.database` | `"mystic_essentials"` | Database name |
| `storage.mysql.username` | `"root"` | Username |
| `storage.mysql.password` | `"password"` | Password |
| `storage.mysql.poolSize` | `10` | HikariCP pool size |

The same code path serves JSON, MySQL, and MariaDB, so operators can start on JSON and migrate to SQL later without feature changes.

## Redis (networks)

Redis is optional and layers a shared cache and pub/sub on top of the storage provider. It powers cross-server features such as network-wide private messages, staff chat, temporary-channel restore, and Player Vault locking.

| Path | Default | Description |
| --- | --- | --- |
| `storage.redis.enabled` | `false` | Enable Redis cache/pub-sub |
| `storage.redis.host` | `"localhost"` | Redis host |
| `storage.redis.port` | `6379` | Redis port |
| `storage.redis.username` | `""` | ACL user (Redis 6+); blank for the `default` user |
| `storage.redis.password` | `""` | Password; blank for none |
| `storage.redis.serverId` | `"survival-1"` | Unique id for this server |
| `storage.redis.networkId` | `"mystic-network"` | Shared id for all servers in the network |
| `storage.redis.advertisedHost` | `""` | Hostname/IP clients use when another server refers them here (cross-server TPA). Blank = auto-detected from the bound interface / first LAN or public IPv4 |
| `storage.redis.advertisedPort` | `0` | Public game port paired with `advertisedHost`; `0` = the port this server is bound to |
| `storage.redis.presenceTtlSeconds` | `30` | How long a server's roster entry survives without a heartbeat |
| `storage.redis.proxyHost` / `proxyPort` | `""` / `0` | Public address of a network proxy (MysticGate). When set, players are referred to the proxy instead of a server's advertised address and backends may stay private |

Every server in a network must share the same `networkId` but use a distinct `serverId`. `/mystic network` prints the roster and this server's advertised address. The advertised address is also logged at startup (`Advertising 'survival-1' to the network at 192.168.1.20:5520 (host auto-detected)`); only set `advertisedHost`/`advertisedPort` when that is not what your players can reach — typically behind NAT, in Docker, or behind a proxy. The `serverId` is what other servers display as the origin of a cross-server chat line or teleport request, so pick something readable (`survival`, `creative`, `hub`).

While Redis is connected every server publishes a roster of its online players, so commands that take a player name — `/tpa`, `/tpahere`, `/msg`, `/mail send` — resolve and tab-complete players anywhere on the network.

### Transfers and the network proxy contract

Hytale has no proxy layer of its own; a server moves a player with `PlayerRef.referToServer(host, port, data)`, which makes the **client** reconnect to `host:port`. Without a proxy, `host:port` is the destination server's advertised address, so every server a player can be sent to needs an address that player's client can reach.

With `proxyHost`/`proxyPort` set, every referral goes to the proxy instead, and the referral payload (≤ 4 KiB, UTF-8 JSON) names the backend. A proxy (MysticGate) that fronts a Mystic network must:

1. Accept the client connection and read `Connect.referralData` / `referralSource`.
2. If the data parses as JSON with a `destinationServerId`, connect the client to that backend; otherwise use the default backend. Payload kinds Mystic Essentials emits:
   - `{"kind":"mysticessentials:tpa-arrival","destinationServerId":"…","target":"<uuid>","teleportType":"tpa|tpahere|tp|tphere","issued":<ms>}` — an accepted teleport.
   - `{"kind":"mysticessentials:route","destinationServerId":"…"}` — a plain transfer with nothing else attached.
   Any other payload belongs to another mod: route it to the default backend and pass it through untouched.
3. Forward `referralData` and `referralSource` unchanged in the `Connect` it sends to the backend, so the backend's `PlayerSetupConnectEvent.getReferralData()` receives them.
4. Find backend addresses either in its own config or from the roster Mystic publishes: `<networkId>:cache:presence:server:<serverId>` holds JSON with `host` and `port` — the backend's (private) advertised address.

Payloads are never trusted on their own: a backend only acts on an arrival payload that matches the arrival record the referring server wrote to Redis, so a client cannot hand-craft one to be teleported to someone.

## Generated layout

After the first run, Mystic creates:

```text
mods/MysticEssentials/
  config.json
  messages/en_us.json
  modules/<module>/config.json
  data/
  logs/
```

Under JSON storage, `data/` holds each module's documents. Under SQL storage, the same documents live in database tables instead.

## For developers

Addons persist their own data through the same API without caring which backend is active:

```java
JsonObject object = new JsonObject();
object.addProperty("value", "example");
api.getStorageService().save("my_addon", playerUuid.toString(), object);
api.getStorageService().load("my_addon", playerUuid.toString());
```

See the [Developer API](developer-api) for details.

## See also

- [Configuration Reference](configuration)
- [Integrations](integrations)
- [Developer API](developer-api)
