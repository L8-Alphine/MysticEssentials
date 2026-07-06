---
title: Chat, Mail, Announcements, AFK, and Greetings
description: Social and communication features in Mystic Essentials.
---

# Chat, Mail, Announcements, AFK, and Greetings

## Private messages

Private messaging supports:

- `/msg`, `/tell`, `/w`, `/whisper`.
- `/reply` and `/r`.
- Social spy.
- Social spy exemption.
- Optional Redis-backed cross-server delivery.
- Optional offline-to-mail fallback.

With MysticVanish installed, vanished players are treated as offline for private messages.

## Channels

Channels control who sees chat and what format is used.

Channel properties include:

| Property | Meaning |
| --- | --- |
| `id` | Stable channel id |
| `displayName` | UI name |
| `scope` | Server, permission, or other configured scope |
| `format` | Chat format template |
| `groupFormats` | LuckPerms primary-group overrides |
| `prefix` | Short UI/chat prefix |
| `aliases` | Commands that switch to the channel |
| `password` | Optional password |
| `joinPermission` | Permission to join/listen |
| `speakPermission` | Permission to speak |
| `listenPermission` | Permission to receive |
| `moderatorPermission` | Permission to moderate/manage |
| `crossServer` | Redis-backed cross-server delivery |
| `redisTopic` | Redis topic name |
| `radiusBlocks` | Local radius, when used by channel implementation |

Temporary channels are session channels without Redis. With Redis enabled, they can be restored for `temporaryChannelDefaultMinutes`.

## Mail

Mail is stored per recipient and works for online and known offline players. Server-wide mail is available to admins.

Inbox limits protect storage growth. When a mailbox is full, old read mail is removed before unread mail.

## Announcements

Manual announcements:

- `/broadcast <message>` uses `broadcastPrefix`.
- `/alert <message>` uses `alertPrefix`.

Auto-broadcasts rotate through configured messages. Entries can be simple strings or multi-line clickable objects.

## AFK

AFK state can be manual or automatic. Activity is refreshed by movement, mouse input, chat, and other tracked interaction.

AFK rewards are optional. They can require:

- Permission.
- A configured reward zone.
- No recent combat.
- Session and daily cap checks.

## Greetings

Greetings handles:

- MOTD on join.
- First-join message.
- Join broadcasts.
- Leave broadcasts.

If another mod or the server itself already handles join and leave messages, disable Mystic's join/leave broadcasts to avoid duplicates.
