# Tutorial Module

Cinematic and UI-page tutorials for onboarding, first-join introductions, server tours, rules walkthroughs, and guided player education.

## Overview

The **Tutorial** module lets server owners create tutorial flows that can run a machinima scene, freeze or protect the player during the sequence, then open a custom tutorial page when the scene ends.

It is designed for:

- first-join onboarding,
- spawn or hub introductions,
- RPG class introductions,
- feature tutorials,
- rule confirmations,
- quest or season intros,
- staff-triggered guided tours.

> [!NOTE]
> This module is disabled by default. Enable it in both the main MysticEssentials module map and the tutorial module config before using tutorial commands.

## File locations

Tutorial files live under `mods/MysticEssentials/`:

```text
config.json
modules/tutorial/config.json
modules/tutorial/tutorials/*.json
modules/tutorial/pages/*.json
data/modules/tutorial/
```

| Path | Purpose |
| --- | --- |
| `config.json` | Main MysticEssentials config. Enables or disables the module. |
| `modules/tutorial/config.json` | Tutorial module settings, defaults, first-join behavior, UI, storage, and failsafes. |
| `modules/tutorial/tutorials/*.json` | Tutorial definitions. Each file defines one tutorial flow. |
| `modules/tutorial/pages/*.json` | Tutorial UI page definitions shown by `/tutorial page` or tutorial completion actions. |
| `data/modules/tutorial/` | Player completion data, history, and tutorial storage data. |

## Enabling the module

First, enable the module in the main `config.json` modules map:

```json
{
  "modules": {
    "tutorial": true
  }
}
```

Then enable it inside `modules/tutorial/config.json`:

```json
{
  "configVersion": 1,
  "enabled": true,
  "debug": false
}
```

Reload MysticEssentials after editing:

```text
/mystic reload
```

You can also reload only tutorial config, tutorial definitions, and pages:

```text
/tutorial reload
```

## How tutorials work

A tutorial follows this flow:

1. A player joins, runs a command, clicks a page button, or is started by staff.
2. Mystic checks tutorial requirements, replay rules, and permissions.
3. The module snapshots the player's current state.
4. Player restrictions are applied, such as freeze, interaction lock, damage protection, HUD hiding, or chat lock.
5. The machinima scene starts if enabled.
6. The tutorial waits for scene completion, timeout, skip, stop, failure, or disconnect.
7. The player is restored safely.
8. Completion data is saved.
9. Optional completion pages and commands run.

> [!IMPORTANT]
> Player restoration is part of the module's safety design. Players are restored on completion, stop, skip, failure, timeout, disconnect, and shutdown.

## Commands

Optional arguments are shown in `[brackets]`, required arguments are shown in `<angle brackets>`.

| Command | Description | Permission |
| --- | --- | --- |
| `/tutorial list` | List loaded tutorials. | `mysticessentials.tutorial.list` |
| `/tutorial info <tutorial>` | Show details for a tutorial. | `mysticessentials.tutorial.info` |
| `/tutorial play <tutorial>` | Start a tutorial for yourself. | `mysticessentials.tutorial.play` |
| `/tutorial <tutorial>` | Shortcut for playing a tutorial. | `mysticessentials.tutorial.play` |
| `/tutorial play <tutorial> <player> [--force]` | Start a tutorial for another player. | `mysticessentials.tutorial.play.others` |
| `/tutorial stop` | Stop your current tutorial and restore your state. | `mysticessentials.tutorial.stop` |
| `/tutorial stop <player>` | Stop another player's current tutorial. | `mysticessentials.tutorial.stop.others` |
| `/tutorial skip` | Skip your current tutorial. | `mysticessentials.tutorial.skip` |
| `/tutorial skip <player>` | Skip another player's current tutorial. | `mysticessentials.tutorial.skip.others` |
| `/tutorial reset <tutorial> <player>` | Clear a player's completion for a tutorial. Works for offline players. | `mysticessentials.tutorial.reset` |
| `/tutorial complete <tutorial> <player>` | Mark a tutorial as completed. Works for offline players. | `mysticessentials.tutorial.complete` |
| `/tutorial status` | Show your current session and completion status. | `mysticessentials.tutorial.status` |
| `/tutorial status <player>` | Show another player's tutorial status. | `mysticessentials.tutorial.status.others` |
| `/tutorial page <page>` | Open a tutorial UI page for yourself. | `mysticessentials.tutorial.page` |
| `/tutorial page <page> <player>` | Open a tutorial UI page for another player. | `mysticessentials.tutorial.page.others` |
| `/tutorial reload` | Reload config, tutorials, and pages. | `mysticessentials.tutorial.reload` |
| `/tutorial debug <on\|off>` | Toggle tutorial debug logging. | `mysticessentials.tutorial.debug` |

## Permissions

`tutorial.admin` grants every tutorial node below.

| Permission | Grants |
| --- | --- |
| `mysticessentials.tutorial.admin` | All tutorial permissions. |
| `mysticessentials.tutorial.list` | `/tutorial list` |
| `mysticessentials.tutorial.info` | `/tutorial info <tutorial>` |
| `mysticessentials.tutorial.play` | `/tutorial play <tutorial>` and `/tutorial <tutorial>` for self. |
| `mysticessentials.tutorial.play.others` | `/tutorial play <tutorial> <player> [--force]` |
| `mysticessentials.tutorial.stop` | `/tutorial stop` for self. |
| `mysticessentials.tutorial.stop.others` | `/tutorial stop <player>` |
| `mysticessentials.tutorial.skip` | `/tutorial skip` for self. |
| `mysticessentials.tutorial.skip.others` | `/tutorial skip <player>` |
| `mysticessentials.tutorial.reset` | `/tutorial reset <tutorial> <player>` |
| `mysticessentials.tutorial.complete` | `/tutorial complete <tutorial> <player>` |
| `mysticessentials.tutorial.status` | `/tutorial status` for self. |
| `mysticessentials.tutorial.status.others` | `/tutorial status <player>` |
| `mysticessentials.tutorial.page` | `/tutorial page <page>` for self. |
| `mysticessentials.tutorial.page.others` | `/tutorial page <page> <player>` |
| `mysticessentials.tutorial.reload` | `/tutorial reload` |
| `mysticessentials.tutorial.debug` | `/tutorial debug <on\|off>` |
| `mysticessentials.tutorial.bypassfirstjoin` | Exempts a player from the first-join tutorial when bypass checks are enabled. |

## Module config example

This example shows the main areas of `modules/tutorial/config.json`:

```json
{
  "configVersion": 1,
  "enabled": true,
  "debug": false,
  "firstJoin": {
    "enabled": true,
    "tutorialId": "first_join",
    "delayTicksAfterJoin": 40,
    "runOnlyOnce": true,
    "respectBypassPermission": true,
    "bypassPermission": "mysticessentials.tutorial.bypassfirstjoin"
  },
  "sceneProvider": {
    "type": "machinima",
    "fallbackToNoOp": true,
    "logMissingSceneProvider": true
  },
  "defaults": {
    "freezePlayer": true,
    "disableMovement": true,
    "disableInteraction": true,
    "disableDamage": true,
    "disableChat": false,
    "hideHud": false,
    "hideOtherPlayers": false,
    "restoreLocationAfterTutorial": false,
    "allowSkip": false,
    "skipPermission": "mysticessentials.tutorial.skip"
  },
  "failsafe": {
    "enabled": true,
    "maxDurationSeconds": 180,
    "unfreezeOnError": true,
    "restoreStateOnError": true,
    "showFallbackPageOnError": true,
    "fallbackPageId": "tutorial_error"
  },
  "ui": {
    "enabled": true,
    "useNoesisGui": true,
    "defaultCompletionPage": "getting_started",
    "closeButtonEnabled": true
  },
  "storage": {
    "type": "json",
    "autosaveSeconds": 60,
    "saveCompletionHistory": true,
    "saveReplayHistory": true
  },
  "logging": {
    "logStarts": true,
    "logCompletions": true,
    "logCancels": true,
    "logErrors": true
  }
}
```

## Tutorial definition

Tutorial definitions are stored in `modules/tutorial/tutorials/*.json`.

Example `modules/tutorial/tutorials/first_join.json`:

```json
{
  "id": "first_join",
  "displayName": "First Join Tutorial",
  "description": "Introduces new players to the server spawn, rules, and core features.",
  "enabled": true,
  "replay": {
    "allowReplay": false,
    "adminCanForceReplay": true,
    "countReplayAsCompletion": false
  },
  "requirements": {
    "permissions": [],
    "worlds": [],
    "mustHaveCompleted": [],
    "mustNotHaveCompleted": []
  },
  "playerState": {
    "freezePlayer": true,
    "disableMovement": true,
    "disableInteraction": true,
    "disableDamage": true,
    "disableChat": false,
    "hideHud": false,
    "hideOtherPlayers": false,
    "restoreLocationAfterTutorial": false
  },
  "machinima": {
    "enabled": true,
    "sceneId": "first_join_intro",
    "pathId": "spawn_tour",
    "waitForCompletion": true,
    "startDelayTicks": 20,
    "timeoutSeconds": 180
  },
  "completion": {
    "markCompleted": true,
    "showPage": true,
    "pageId": "getting_started",
    "runCommands": false,
    "commands": []
  },
  "failsafe": {
    "enabled": true,
    "timeoutSeconds": 180,
    "restoreState": true,
    "unfreezePlayer": true
  }
}
```

### Tutorial fields

| Field | Description |
| --- | --- |
| `id` | Unique tutorial ID. Usually matches the file name. |
| `displayName` | Friendly name shown in commands and admin output. |
| `description` | Short summary of what the tutorial teaches. |
| `enabled` | Whether the tutorial can run. |
| `replay` | Controls whether players can replay a completed tutorial and whether admins can force it. |
| `requirements` | Optional permission, world, and completion requirements. |
| `playerState` | Per-tutorial overrides for freeze, movement, damage, chat, HUD, visibility, and restore behavior. |
| `machinima` | Scene/path playback settings. |
| `completion` | Completion marking, completion page, and optional command rewards. |
| `failsafe` | Per-tutorial timeout and recovery settings. |

## Tutorial pages

Tutorial pages are stored in `modules/tutorial/pages/*.json` and can be opened by commands, completion settings, or page buttons.

Example `modules/tutorial/pages/getting_started.json`:

```json
{
  "id": "getting_started",
  "title": "Getting Started",
  "subtitle": "Welcome to the server!",
  "layout": "default",
  "content": [
    {
      "type": "text",
      "text": "&7You finished the intro. Here are a few places to begin:"
    },
    {
      "type": "text",
      "text": "&fUse &a/spawn &fto return here, &a/warps &fto explore, and &a/help &ffor commands."
    }
  ],
  "buttons": [
    {
      "id": "rules",
      "text": "Read Rules",
      "icon": "Book",
      "action": {
        "type": "command",
        "value": "rules"
      }
    },
    {
      "id": "spawn",
      "text": "Go to Spawn",
      "icon": "Compass",
      "action": {
        "type": "teleport",
        "value": "spawn"
      }
    },
    {
      "id": "close",
      "text": "Close",
      "icon": "Barrier",
      "action": {
        "type": "close",
        "value": ""
      }
    }
  ]
}
```

### Page fields

| Field | Description |
| --- | --- |
| `id` | Unique page ID. Usually matches the file name. |
| `title` | Main page title. |
| `subtitle` | Smaller text below the title. |
| `layout` | Layout identifier. Use `default` unless you have a custom renderer/layout. |
| `content` | List of content rows. Current content items use `type` and `text`. |
| `buttons` | List of clickable page buttons. |

### Button actions

| Action type | Value meaning |
| --- | --- |
| `close` | Closes the tutorial page. |
| `page` | Opens another tutorial page by ID. |
| `command` | Runs a player command. Do not include the leading `/`. |
| `console_command` | Runs a command as console/server. |
| `tutorial` | Starts another tutorial by ID. |
| `message` | Sends a message to the player. |
| `url` | Opens a URL. Use trusted `https://` links. |
| `teleport` | Teleports to a supported destination, such as a configured spawn/warp target if implemented by your server setup. |

> [!WARNING]
> Be careful with `console_command` buttons. Only use them for safe actions that cannot be abused by players.

## First-join tutorial setup

To run a tutorial for new players:

1. Enable the module in the main config.
2. Enable `firstJoin.enabled` in `modules/tutorial/config.json`.
3. Set `firstJoin.tutorialId` to the tutorial ID.
4. Keep `firstJoin.runOnlyOnce` enabled unless you want the flow to repeat.
5. Grant `mysticessentials.tutorial.bypassfirstjoin` to staff or test accounts that should not see the first-join tutorial.
6. Run `/tutorial reload`.
7. Test with a new player profile or reset a player's completion with `/tutorial reset first_join <player>`.

## Replay and completion behavior

| Setting | Description |
| --- | --- |
| `replay.allowReplay` | Allows players to replay a completed tutorial. |
| `replay.adminCanForceReplay` | Allows staff to use `--force` when starting a tutorial for a player. |
| `replay.countReplayAsCompletion` | Controls whether replay sessions update completion records. |
| `completion.markCompleted` | Marks the tutorial complete when it finishes. |
| `completion.showPage` | Opens a page after the tutorial completes. |
| `completion.pageId` | Page ID opened after completion. Empty uses the configured default completion page. |
| `completion.runCommands` | Runs the configured completion commands. |
| `completion.commands` | Commands to run after completion. |

## Placeholders and formatting

Tutorial page text and messages support MysticEssentials message formatting, including legacy colour codes, hex colours, gradients, links, and placeholders such as:

| Placeholder | Meaning |
| --- | --- |
| `{player_name}` | Player name. |
| `{player_uuid}` | Player UUID. |
| `{server_name}` | Server name. |
| `%placeholderapi_placeholder%` | PlaceholderAPI values when PlaceholderAPI is installed. |

## Recommended workflow

1. Create the tutorial definition in `modules/tutorial/tutorials/`.
2. Create any completion or error pages in `modules/tutorial/pages/`.
3. Run `/tutorial reload`.
4. Run `/tutorial info <tutorial>` to verify it loaded.
5. Test with `/tutorial play <tutorial>`.
6. Test staff flow with `/tutorial play <tutorial> <player> --force`.
7. Test stop, skip, disconnect, and timeout recovery.
8. Enable first-join only after the flow is verified.

## Troubleshooting

| Problem | Fix |
| --- | --- |
| `/tutorial list` is empty | Make sure the module is enabled and tutorial JSON files are in `modules/tutorial/tutorials/`. Run `/tutorial reload`. |
| First-join tutorial does not start | Check `firstJoin.enabled`, `firstJoin.tutorialId`, completion data, and bypass permission. |
| Player can move during tutorial | Check `defaults.disableMovement`, `defaults.freezePlayer`, and the tutorial's `playerState` overrides. |
| Scene does not play | Check `machinima.sceneId`, `machinima.pathId`, and `sceneProvider.type`. If machinima is unavailable, `fallbackToNoOp` allows safe fallback. |
| Completion page does not open | Check `completion.showPage`, `completion.pageId`, `ui.enabled`, and that the page JSON exists. |
| Player stays frozen after an error | Keep failsafes enabled and check the console/logs. Use `/tutorial stop <player>` if needed. |
| JSON does not load | Validate commas, quotes, arrays, and object structure, then run `/tutorial reload`. |

## Best practices

- Keep first-join tutorials short.
- Use completion pages for longer instructions instead of overloading the cinematic scene.
- Always include a fallback page for errors.
- Give staff bypass permission so they can test without triggering the live first-join flow.
- Use `/tutorial reset` often while testing.
- Keep `failsafe.enabled` and `restoreStateOnError` enabled on production servers.
- Avoid running reward commands until the tutorial flow is fully tested.