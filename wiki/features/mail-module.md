# Mail

Mail lets players send messages — and optionally items — to each other, even when the recipient is offline. Messages wait in an inbox until they are read, and staff can send server-wide mail with rewards attached.

## Commands

| Command | What it does | Permission |
| --- | --- | --- |
| `/mail` | Open the Mail UI | `mysticessentials.mail.use` |
| `/mail inbox` | List your inbox in chat | `mysticessentials.mail.use` |
| `/mail read <id>` | Read a mail item | `mysticessentials.mail.use` |
| `/mail send <player> <message>` | Send mail (players online anywhere on a Redis network count as online) | `mysticessentials.mail.send`; offline targets need `mysticessentials.mail.send.offline` |
| `/mail sendall <message>` | Send mail to all known players | `mysticessentials.mail.send.all` |
| `/mail delete <id>` | Delete a mail item | `mysticessentials.mail.use` |
| `/mail clear` | Clear your inbox | `mysticessentials.mail.use` |
| `/mailadmin` | Open the mail admin center | `mysticessentials.mail.announce` |

Mail from a player the recipient ignores (`/ignore`) is not delivered — from
`/mail send`, the composer (before any attachment is taken) or another mod's
`MailService.send` with a player sender — and the sender is told they can't mail
that player right now. Holders of `mysticessentials.chat.ignore.exempt` always get
through; server mail, `/mail sendall` and admin announcements are never refused.

## Item attachments and announcements

- Players with `mysticessentials.mail.attach` can attach items to normal mail; the items are taken from the sender's inventory and claimed by the recipient when they read the mail.
- A message may carry up to `maxAttachments` stacks. `blockedItemIds` prevents selected items from being attached.
- Staff with `mysticessentials.mail.announce` can send **admin announcements** — targeted mail carrying item and command rewards — from the mail admin center. Audiences may be all online players, all known players, one player, or players with a permission.
- Command rewards run as console only when claimed and may use `{player}` and `{uuid}`. Set `allowAnnouncementCommands` to `false` to disable them.

## Inbox limits

When an inbox reaches `maxInboxSize`, Mystic removes the **oldest read** message first. If no read messages exist, it removes the oldest message overall. Set `maxInboxSize` or `maxMessageLength` to `0` for unlimited.

Players can be notified of unread mail on join with `notifyUnreadOnJoin`.

## Configuration

File:

```text
modules/mail/config.json
```

| Setting | Default | Description |
| --- | --- | --- |
| `maxInboxSize` | `50` | Maximum messages per inbox; `0` = unlimited |
| `maxMessageLength` | `2000` | Maximum mail body length; `0` = unlimited |
| `notifyUnreadOnJoin` | `true` | Show unread count when a player joins |
| `allowPlayerItemAttachments` | `true` | Allow players with `mysticessentials.mail.attach` to attach inventory items |
| `maxAttachments` | `9` | Maximum item stacks per mail |
| `allowAnnouncementCommands` | `true` | Allow command rewards on admin announcement mail |
| `blockedItemIds` | `[]` | Item ids players cannot attach |
| `pageSize` | `6` | Rows shown per UI page |
| `broadcastBatchSize` | `50` | Recipients processed per admin broadcast batch |

## See also

- [Permissions Reference](permissions)
- [Configuration Reference](configuration)
