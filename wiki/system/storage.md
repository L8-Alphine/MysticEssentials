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
| `storage.redis.password` | `""` | Password; blank for none |
| `storage.redis.serverId` | `"survival-1"` | Unique id for this server |
| `storage.redis.networkId` | `"mystic-network"` | Shared id for all servers in the network |

Every server in a network must share the same `networkId` but use a distinct `serverId`.

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
