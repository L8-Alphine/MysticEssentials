# Mail

Mail lets players send messages — and optionally items — to each other, even when the recipient is offline. Messages wait in an inbox until they are read, and staff can send server-wide mail with rewards attached.

## Commands

| Command | What it does | Permission |
| --- | --- | --- |
| `/mail` | Open the Mail UI | `mysticessentials.mail.use` |
| `/mail inbox` | List your inbox in chat | `mysticessentials.mail.use` |
| `/mail read <id>` | Read a mail item | `mysticessentials.mail.use` |
| `/mail send <player> <message>` | Send mail | `mysticessentials.mail.send`; offline targets need `mysticessentials.mail.send.offline` |
| `/mail sendall <message>` | Send mail to all known players | `mysticessentials.mail.send.all` |
| `/mail delete <id>` | Delete a mail item | `mysticessentials.mail.use` |
| `/mail clear` | Clear your inbox | `mysticessentials.mail.use` |
| `/mailadmin` | Open the mail admin center | `mysticessentials.mail.announce` |

## Item attachments and announcements

- Players with `mysticessentials.mail.attach` can attach items to normal mail; the items are taken from the sender's inventory and claimed by the recipient when they read the mail.
- Staff with `mysticessentials.mail.announce` can send **admin announcements** — server-wide mail carrying item and command rewards — from the mail admin center.

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
| `maxMessageLength` | `256` | Maximum mail body length; `0` = unlimited |
| `notifyUnreadOnJoin` | `true` | Show unread count when a player joins |

## See also

- [Permissions Reference](permissions)
- [Configuration Reference](configuration)
