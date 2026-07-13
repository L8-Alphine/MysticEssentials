# Custom Commands Module

Owner-defined commands for messages, command chains, redirects, server links, VIP shortcuts, utility actions, and lightweight scripted behavior.

## Overview

The **Custom Commands** module lets server owners create commands from JSON files without writing Java code. A command can send messages, run other commands, broadcast text, wait, check conditions, show notifications, play sounds, and use typed arguments.

Common use cases include:

- `/rules`, `/discord`, `/store`, `/vote`, and `/wiki`,
- staff-only helper commands,
- VIP commands with permissions and cooldowns,
- command aliases and redirects,
- simple reward or utility command chains,
- informational commands with PlaceholderAPI values,
- cross-server networks that need shared cooldowns and reload sync.

> [!NOTE]
> This module is disabled by default. Enable it in both the main MysticEssentials module map and the custom commands module config before creating commands.

## File locations

Custom command files live under `mods/MysticEssentials/`:

```text
config.json
modules/customcommands/config.json
modules/customcommands/commands/*.json
data/modules/customcommands/
logs/customcommands.log
```

| Path | Purpose |
| --- | --- |
| `config.json` | Main MysticEssentials config. Enables or disables the module. |
| `modules/customcommands/config.json` | Module settings, safety limits, cooldown persistence, Redis sync, and logging. |
| `modules/customcommands/commands/*.json` | One JSON command definition per file. |
| `data/modules/customcommands/` | Persistent usage stats and cooldown data. |
| `logs/customcommands.log` | Optional usage/audit log output when enabled. |

## Enabling the module

First, enable the module in the main `config.json` modules map:

```json
{
  "modules": {
    "customcommands": true
  }
}
```

Then enable it inside `modules/customcommands/config.json`:

```json
{
  "configVersion": 1,
  "enabled": true
}
```

Reload MysticEssentials after editing:

```text
/mystic reload
```

You can also reload only Custom Commands:

```text
/customcommands reload
```

Aliases for the admin command are:

```text
/ccmd
/customcmd
/mecustomcommands
```

## Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/customcommands list` | Lists loaded commands with state and usage counts. | `mysticessentials.customcommands.list` |
| `/customcommands info <command>` | Shows full details for a command definition. | `mysticessentials.customcommands.info` |
| `/customcommands reload` | Reloads config and definitions. Added and removed commands are registered or unregistered immediately. | `mysticessentials.customcommands.reload` |
| `/customcommands enable <command>` | Enables one command and persists the change to its file. | `mysticessentials.customcommands.manage` |
| `/customcommands disable <command>` | Disables one command and persists the change to its file. | `mysticessentials.customcommands.manage` |
| `/customcommands test <command> [args...]` | Runs a command for testing while ignoring cooldown and disabled-state checks. | `mysticessentials.customcommands.test` |
| `/customcommands validate` | Re-checks every definition and reports all issues. | `mysticessentials.customcommands.validate` |

## Permissions

`customcommands.admin` grants every admin node below. Access to custom commands themselves is defined per command in that command's JSON file.

| Permission | Grants |
| --- | --- |
| `mysticessentials.customcommands.admin` | All custom command admin permissions. |
| `mysticessentials.customcommands.list` | `/customcommands list` |
| `mysticessentials.customcommands.info` | `/customcommands info <command>` |
| `mysticessentials.customcommands.reload` | `/customcommands reload` |
| `mysticessentials.customcommands.manage` | `/customcommands enable <command>` and `/customcommands disable <command>` |
| `mysticessentials.customcommands.test` | `/customcommands test <command> [args...]` |
| `mysticessentials.customcommands.validate` | `/customcommands validate` |
| `mysticessentials.customcommands.bypass.cooldown` | Bypasses every custom command cooldown. |
| `mysticessentials.customcommands.bypass.cooldown.<name>` | Bypasses one command's cooldown. |
| `mysticessentials.customcommands.command.<name>` | Implicit use node for a command using `permission.mode: single` with no explicit `permission.node`. |

## Module config example

This example shows the main areas of `modules/customcommands/config.json`:

```json
{
  "configVersion": 1,
  "enabled": true,
  "serverName": "",
  "generateExamples": true,
  "allowOverrideExisting": false,
  "safety": {
    "maxActionsPerChain": 32,
    "maxExecutionDepth": 8,
    "blockedCommands": [
      "stop",
      "shutdown",
      "op",
      "deop"
    ]
  },
  "cooldowns": {
    "persist": true
  },
  "crossServer": {
    "syncCooldowns": true,
    "syncReloads": true
  },
  "logging": {
    "usage": false,
    "audit": false
  }
}
```

### Config fields

| Field | Description |
| --- | --- |
| `enabled` | Enables the module after the main config module toggle is also enabled. |
| `serverName` | Optional server name used by placeholders and logging. |
| `generateExamples` | Generates safe example commands on first startup. |
| `allowOverrideExisting` | Allows custom commands to override existing commands when supported. Keep this off unless intentional. |
| `safety.maxActionsPerChain` | Maximum number of actions a command chain may execute. |
| `safety.maxExecutionDepth` | Maximum nested execution depth. Helps prevent command recursion loops. |
| `safety.blockedCommands` | Command names that custom command actions are not allowed to run. |
| `cooldowns.persist` | Saves cooldown state through the storage abstraction. |
| `crossServer.syncCooldowns` | Syncs cooldown starts over Redis when Redis is enabled. |
| `crossServer.syncReloads` | Syncs reload events over Redis when Redis is enabled. |
| `logging.usage` | Enables usage logging. |
| `logging.audit` | Enables audit logging. |

> [!CAUTION]
> Keep dangerous administrative commands in `safety.blockedCommands`. Avoid allowing custom commands to run shutdown, permission escalation, or server-management commands.

## Command definition

Each custom command is a JSON file in `modules/customcommands/commands/`.

Example `modules/customcommands/commands/discord.json`:

```json
{
  "name": "discord",
  "description": "Shows the server Discord link.",
  "enabled": true,
  "aliases": ["dc"],
  "permission": {
    "mode": "none"
  },
  "cooldown": {
    "seconds": 10,
    "message": "&cPlease wait &f{cooldown_remaining}&c before using this again."
  },
  "arguments": [],
  "conditions": [],
  "runAs": "console",
  "actions": [
    {
      "type": "message",
      "lines": [
        "&b&lDiscord",
        "&7Join our community: &fhttps://discord.gg/example"
      ]
    }
  ]
}
```

### Definition fields

| Field | Description |
| --- | --- |
| `name` | Primary command name. Do not include `/`. |
| `description` | Command description shown in info output. |
| `enabled` | Whether the command is active. |
| `aliases` | Extra command names that trigger the same definition. |
| `permission` | Permission mode, node, node list, and denial message. |
| `cooldown` | Cooldown seconds, bypass permission, and cooldown message. |
| `arguments` | Typed argument list. |
| `conditions` | Requirements checked before the command actions run. |
| `runAs` | Default executor context for command actions. |
| `actions` | Ordered list of actions to execute. |

## Permission modes

| Mode | Description |
| --- | --- |
| `none` | No command permission required. |
| `single` | Requires one permission. Uses `permission.node`, or the implicit `mysticessentials.customcommands.command.<name>` if no node is set. |
| `all` | Requires every node listed in `permission.nodes`. |
| `any` | Requires at least one node listed in `permission.nodes`. |

Example permission block:

```json
{
  "permission": {
    "mode": "single",
    "node": "myserver.vip",
    "denyMessage": "&cYou need VIP to use this command."
  }
}
```

## Cooldowns

Cooldowns are defined per command.

```json
{
  "cooldown": {
    "seconds": 60,
    "bypassPermission": "myserver.cooldown.bypass",
    "message": "&cYou can use this again in &f{cooldown_remaining}&c."
  }
}
```

Cooldown bypass options:

| Bypass node | Scope |
| --- | --- |
| `mysticessentials.customcommands.bypass.cooldown` | Bypasses all custom command cooldowns. |
| `mysticessentials.customcommands.bypass.cooldown.<name>` | Bypasses one command's cooldown. |
| `cooldown.bypassPermission` | Optional custom bypass node defined inside the command JSON. |

## Argument types

Arguments are parsed in order. Required arguments use `<name>` in usage output; optional arguments use `[name]`.

| Type | Description |
| --- | --- |
| `string` | Text value. |
| `word` | Single word. |
| `number` | Decimal number. |
| `integer` | Whole number. |
| `boolean` | `true` or `false`. |
| `player` | Online player. |
| `offline_player` | Player name that may be offline. |
| `duration` | Time duration, such as `5m`. |
| `greedy_string` | Consumes the rest of the command input as text. Best used as the last argument. |

Example arguments:

```json
{
  "arguments": [
    {
      "name": "target",
      "type": "player",
      "required": true,
      "description": "Player to greet."
    },
    {
      "name": "message",
      "type": "greedy_string",
      "required": false,
      "defaultValue": "Welcome!",
      "description": "Greeting message."
    }
  ]
}
```

Use arguments in actions with `{arg:name}`:

```json
{
  "type": "message",
  "text": "&7You greeted &f{arg:target}&7 with: &f{arg:message}"
}
```

## Action types

Actions run in order.

| Type | Required fields | Description |
| --- | --- | --- |
| `message` | `text` or `lines` | Sends one or more messages to the sender. |
| `command` | `command` | Runs a command. Supports `runAs`. |
| `broadcast` | `text` | Broadcasts a message. |
| `delay` | `seconds`, `ticks`, or `millis` | Delays the chain before continuing. |
| `condition` | `if` or `conditions`, plus `then` and/or `else` | Runs nested actions depending on conditions. |
| `notification` | `title` or `text` | Sends a client notification/toast. Optional `body`, `style`, and `target`. |
| `sound` | `sound` or `soundEvent` | Plays a 2D sound event. Optional `category`, `volume`, `pitch`, and `target`. |

### Message action

```json
{
  "type": "message",
  "lines": [
    "&6&lServer Rules",
    "&71. Be respectful.",
    "&72. No cheating.",
    "&73. Use common sense."
  ]
}
```

### Command action

```json
{
  "type": "command",
  "command": "msg {arg:target} {arg:message}",
  "runAs": "sender"
}
```

Valid executor specs:

| `runAs` | Meaning |
| --- | --- |
| `console` | Runs as console. |
| `server` | Runs as server. |
| `sender` | Runs as the command sender. |
| `arg:<argumentName>` | Runs as the player supplied by an argument. |
| `player:<name>` | Runs as a named player. |

### Delay action

```json
{
  "type": "delay",
  "seconds": 3
}
```

You may also use `ticks` or `millis`.

### Conditional action

```json
{
  "type": "condition",
  "if": {
    "type": "permission",
    "node": "myserver.vip"
  },
  "then": [
    {
      "type": "message",
      "text": "&aThanks for supporting the server!"
    }
  ],
  "else": [
    {
      "type": "message",
      "text": "&7VIP perks are available in /store."
    }
  ]
}
```

### Notification action

```json
{
  "type": "notification",
  "title": "Welcome",
  "body": "Thanks for joining {server_name}!",
  "style": "success",
  "target": "sender"
}
```

Known notification styles include `default`, `danger`, `error`, `warning`, `warn`, and `success`.

### Sound action

```json
{
  "type": "sound",
  "soundEvent": "ui_button_click",
  "category": "ui",
  "volume": 1.0,
  "pitch": 1.0,
  "target": "sender"
}
```

Known sound categories include `music`, `ambient`, `sfx`, `ui`, and `voice`.

## Conditions

Conditions can be placed on the command itself or inside a `condition` action.

| Type | Fields | Description |
| --- | --- | --- |
| `permission` | `node`, `negate`, `denyMessage` | Requires or blocks a permission. |
| `world` | `world` or `worlds`, `negate`, `denyMessage` | Requires the sender to be in one of the listed worlds. |
| `server` | `server`, `minOnline`, `maxOnline`, `denyMessage` | Checks server name and/or online count. |
| `placeholder` | `placeholder`, `operator`, `value`, `denyMessage` | Compares a placeholder value. |

Known placeholder operators:

| Operator | Meaning |
| --- | --- |
| `equals` | Value must match exactly. |
| `not_equals` | Value must not match. |
| `contains` | Placeholder value must contain the configured value. |
| `greater_than` | Numeric placeholder value must be greater than the configured value. |
| `less_than` | Numeric placeholder value must be less than the configured value. |

Example command-level conditions:

```json
{
  "conditions": [
    {
      "type": "world",
      "worlds": ["world", "hub"],
      "denyMessage": "&cYou cannot use this command in this world."
    },
    {
      "type": "placeholder",
      "placeholder": "%mystic_group%",
      "operator": "not_equals",
      "value": "banned",
      "denyMessage": "&cYou cannot use this command."
    }
  ]
}
```

## Placeholders

Custom command messages, commands, conditions, and action text can use built-in placeholders:

| Placeholder | Meaning |
| --- | --- |
| `{player_name}` | Player name. |
| `{player_uuid}` | Player UUID. |
| `{sender_name}` | Sender name. |
| `{sender_uuid}` | Sender UUID. |
| `{server_name}` | Server name. |
| `{server_online}` | Online player count. |
| `{arg:name}` | Parsed command argument. |
| `{cooldown_remaining}` | Remaining cooldown time. |
| `%placeholderapi_placeholder%` | PlaceholderAPI value when PlaceholderAPI is installed. |

## Full example: greet command

Create `modules/customcommands/commands/greet.json`:

```json
{
  "name": "greet",
  "description": "Greets a player.",
  "enabled": true,
  "aliases": ["hello"],
  "permission": {
    "mode": "single",
    "node": "myserver.greet",
    "denyMessage": "&cYou do not have permission to greet players."
  },
  "cooldown": {
    "seconds": 60,
    "message": "&cYou can greet again in &f{cooldown_remaining}&c."
  },
  "arguments": [
    {
      "name": "target",
      "type": "player",
      "required": true
    },
    {
      "name": "message",
      "type": "greedy_string",
      "required": false,
      "defaultValue": "Welcome!"
    }
  ],
  "conditions": [],
  "runAs": "console",
  "actions": [
    {
      "type": "message",
      "text": "&7You greeted &f{arg:target}&7."
    },
    {
      "type": "command",
      "command": "msg {arg:target} {arg:message}",
      "runAs": "sender"
    }
  ]
}
```

Then reload and test:

```text
/customcommands validate
/customcommands reload
/greet PlayerName Welcome to the server!
```

## Validation workflow

Use this flow when adding or editing commands:

1. Create or edit the JSON file in `modules/customcommands/commands/`.
2. Run `/customcommands validate`.
3. Fix any reported issues.
4. Run `/customcommands reload`.
5. Check `/customcommands list`.
6. Check `/customcommands info <command>`.
7. Test with `/customcommands test <command> [args...]`.
8. Test normally to verify permissions and cooldowns.

## Cross-server behavior

When Redis is enabled in the main MysticEssentials config:

- reloads can sync across servers when `crossServer.syncReloads` is enabled,
- cooldown starts can sync across servers when `crossServer.syncCooldowns` is enabled,
- cooldown and usage data can persist through shared storage.

This is useful for networks where commands like `/kitpreview`, `/vote`, `/daily`, or `/reward` should not be bypassed by switching servers.

## Troubleshooting

| Problem | Fix |
| --- | --- |
| Command does not exist | Make sure the module is enabled, the command JSON is in `modules/customcommands/commands/`, and `/customcommands reload` was run. |
| Command appears disabled | Check the command's `enabled` field or run `/customcommands enable <command>`. |
| JSON file does not load | Check commas, quotes, arrays, and object structure. Run `/customcommands validate`. |
| Permission does not work | Check `permission.mode`, `permission.node`, `permission.nodes`, and LuckPerms assignments. |
| Cooldown never applies | Check `cooldown.seconds`, bypass permissions, and `cooldowns.persist`. |
| Command action does nothing | Make sure the `command` field does not include a leading `/` unless the target command system explicitly supports it. |
| Command action is blocked | Check `safety.blockedCommands`. |
| Command loops forever | Lower `safety.maxExecutionDepth`, check nested command actions, and avoid commands calling themselves. |
| Redis reload sync does not work | Confirm Redis is enabled in the main config and `crossServer.syncReloads` is true. |

## Best practices

- Use one JSON file per command.
- Keep public commands simple and safe.
- Use `permission.mode: single` for VIP or staff commands.
- Use `greedy_string` only as the final argument.
- Prefer `runAs: sender` unless a command truly needs console/server execution.
- Keep `stop`, `shutdown`, `op`, and `deop` blocked.
- Run `/customcommands validate` before every reload.
- Use audit logging on production networks when commands can run other commands.