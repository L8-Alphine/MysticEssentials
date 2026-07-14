# Greetings

The Greetings module handles the messages players see and trigger when they connect and disconnect: a Message of the Day, a special first-join message, and join/leave broadcasts. It has no commands — everything is configured in its config file.

## What it sends

- **MOTD** — a multi-line message sent privately to each player on join.
- **First-join message** — broadcast the first time a player ever joins.
- **Join / leave broadcasts** — announced to the server as players come and go.

All messages support Mystic's full color and gradient syntax (see [Chat Formatting](chat-formatting)) and the `{player_name}` placeholder.

> **Join and leave broadcasts default to off** so Mystic does not duplicate a server-native or another mod's join/leave system. Turn them on only when you want Mystic to own those messages.

## Configuration

File:

```text
modules/greetings/config.json
```

| Setting | Default | Description |
| --- | --- | --- |
| `motdEnabled` | `true` | Send the MOTD on join |
| `motd` | Welcome lines | MOTD lines (list of strings) |
| `firstJoinEnabled` | `true` | Enable the first-join message |
| `firstJoinMessage` | Welcome message | First-join broadcast |
| `joinEnabled` | `false` | Enable the join broadcast |
| `joinMessage` | `&8[&a+&8] &7{player_name}` | Join broadcast |
| `leaveEnabled` | `false` | Enable the leave broadcast |
| `leaveMessage` | `&8[&c-&8] &7{player_name}` | Leave broadcast |

The MOTD is a list of strings, one per line — add or remove lines freely:

```json
{
  "motdEnabled": true,
  "motd": [
    "<gradient:#7b2cff:#00d4ff>&lMystic Essentials</gradient>",
    "&7Welcome back, &f{player_name}&7!",
    "&7Type &f/mystic &7for help."
  ],
  "firstJoinEnabled": true,
  "firstJoinMessage": "&e&lWelcome &f{player_name} &e&lto the server for the first time!",
  "joinEnabled": true,
  "joinMessage": "&8[&a+&8] &7{player_name} &8joined",
  "leaveEnabled": true,
  "leaveMessage": "&8[&c-&8] &7{player_name} &8left"
}
```

## See also

- [Configuration Reference](configuration)
- [Chat Formatting](chat-formatting) — color and placeholder syntax
