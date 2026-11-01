# Mystic Essentials Changelog

## Unreleased — The real "Loading..." fix, a bigger wiki editor, every texture

Server-side checks only (build, `check`); not yet seen in a game client.

- **Pages opened from the menu no longer lock on their first click.** Closing a
  page makes the client answer with a Dismiss; when a card closed the menu and
  then opened Mail, that late Dismiss landed on Mail, the engine forgot Mail
  while the player still saw it, and the next click went nowhere. `close()` now
  waits 150 ms and only closes if no page replaced it, and a page that receives
  such a stray Dismiss re-registers itself. This covers every close-then-open
  path (menu cards, Shortcuts rows, the wiki's Main Menu button, vaults,
  inventory restore, crate preview). Pages override `onClosed`, not `onDismiss`.
- **Wiki editor at 1440×860**, toolbar labels no longer cut off.
- **Images tab searches every texture the client has** — icons, items, model
  renders, model/block/NPC/particle textures, the mod's and the server's — with
  Icons / Items / Models / All filters and the name or path on hover.
- **Server PNGs are optimized automatically**: capped at 128 px, stripped and
  recompressed into the mod's cache; the server owner's folder is only read.
  Subfolders work (`custom:models/dragon`), sources up to 32 MB, and new files
  are picked up within 15 seconds without a reload.

## Unreleased — No more stuck "Loading...", Hyzion icons, a new wiki

Server-side checks only (build, `check`); not yet seen in a game client.

### Pages never sit on "Loading..."

- **Every page event now gets an answer.** A button bound the default way locks
  the page until the server replies; a handler that only chatted (the Server
  Browser's Connect on an offline card), or a next page that failed to build
  (swallowed silently), left players locked out. `MysticPage` now wraps every
  handler: failures are logged and the page unlocked at once, and a handler
  that sent nothing is unlocked after 1.2 s, which leaves room for the ones that
  answer after async work. All 61 handlers moved to `onEvent`.
- Inputs that fire per keystroke (search boxes, the Rail Editor's dropdown, the
  dialog editor's fields) are no longer locking. The engine drops events that
  arrive while the client still owes an acknowledgement, and a dropped locking
  event is a page stuck for good.

### Icons

- **Hyzion's icon set** (35 icons) is shipped and used across the rail, menu,
  Shortcuts page, mail, kits, vaults, homes, warps, the server browser and the
  wiki. Shipped at 64 px from 128 px masters in `art/icons/hyzion/`
  (`scripts/optimize-icons.py`): 952 KB down to 303 KB for every client to
  download, and closer to the 20–44 px they are drawn at.
- **Padlocks** on anything the player cannot use: menu cards, Shortcuts rows,
  locked server cards, kits, vaults, hidden warps.
- **Server icons**: PNGs in `mods/MysticEssentials/icons/` load at startup and on
  reload, pushed to online players, and are named `custom:<name>`.
- **Kits** show their items in turn (or a set `icon`) instead of a coloured
  square; kits behind a permission are listed with a padlock
  (`showLockedKits`, default on). Status reads in colour.
- Warp, home and player-warp rows show icons in place of colour squares.

### Wiki

- **One bigger window** (1050×700): search, categories and pages on the left,
  the page with its icon on the right. Search updates only the list.
- **Markup**: `###` subheadings, `>` notes, colours, `{{image:…}}`,
  `{{icons:…}}`, `{{item:…}}`, `{{items:…}}`, `{{page:…}}` embeds, and
  `[label](https://…)` / `[label](/command)` links next to `[[page]]`. Inline
  links are drawn blue and collected as buttons under the page.
- **Editor**: live preview as you type, an Images tab that searches icons and
  items (click to add, right-click for the page icon), a toolbar for structure
  and colours, and a page icon field. Save errors no longer wipe what was typed.

### Sizes

- The Shortcuts page is two columns (channels | destinations) so all nine fit.
- The Mail rewards dropdown no longer pushes out past its column.

## Unreleased — Server Browser, Rail Editor, icons everywhere

Server-side checks only (build, `check`); not yet seen in a game client.

### Server Browser

- **Slot 8, the menu card and `/servers` open a real Server Browser**: region
  tabs, server cards three to a row under group headings, each with its icon,
  region, description, live player count (vanished players left out) and a
  Connect button. States: online, you are here, open (address only), offline,
  coming soon, locked.
- Servers come from `browser.servers` in the shell config. With nothing listed
  and `showNetwork` on, it shows this server and every server on the Redis
  roster, so a network works without setup.
- Connect re-resolves the card before moving anyone, then refers the player
  through the roster (the proxy, when one is set) or straight to the
  configured address. Building the page reads only in-memory state.
- A slot 8 `target` still wins: a network with its own selector keeps it.

### Rail Editor

- **`/shortcuts edit`** (and **Edit Rail** on the Shortcuts page) for holders of
  the new `mysticessentials.shell.admin`: change a slot's job, label, command,
  letter, permission and icon, picked from a grid of the game's own icons.
- Nothing is saved until Save, which re-checks the permission, writes the
  config off the world thread (in order, a stale write is dropped) and redraws
  every player's rail. The live slot list is swapped, never edited in place.

### Icons

- **Mail folders** (Inbox, Compose, Sent, Archived, Deleted, Announcements,
  Attachments) and the **menu's section rail** now carry icons; the Mail and
  Account cards have them too.
- Every icon path lives in one `VanillaIcons` class as a whole literal, and the
  new `verifyVanillaIconPaths` build check (part of `check`) looks each one up
  in the game's Assets.zip — a wrong path draws nothing on the client and
  would otherwise go unnoticed.
- A slot or card `icon` can be a picker name (`Portal`, `Fist Bump`) as well as
  a path.

### Rail position and chat

- The rail moved up to centre on the chat panel (`offsetY` 196 to 316) and its
  title is now `Chat`, which fits; the old `CHAT CH` was cut to `CHAT...`.
  Existing configs move only if they still held the old defaults.
- `/channels` works as an alias of `/channel`.

## Unreleased — ALT shortcuts, vanilla icons, AFK water fix

Server-side checks only (build, `check`); not yet seen in a game client.

### ALT+1–9

- **Hold Alt and press 1–9 to run a rail slot.** There is no keybind API, but
  Alt is Hytale's default walk key and a digit selects a hotbar slot — both
  reach the server — so a slot change that lands while walking is the chord.
  The hotbar is restored on server and client, so you keep your held item.
- Scrolling is not a shortcut: a one-slot move counts only with an Alt press
  under 350 ms old. Alt released up to 800 ms before the digit still counts, and
  a digit that beats the walk state to the server is honoured for 250 ms.
- Restoring the slot raises the same slot event. With nine digits bound, that
  echo would otherwise read as another ALT+digit and loop; it is marked and
  ignored.
- `keys.altShortcuts` (default on). The rail footer reads `Alt+#` while it is
  live, and the Shortcuts page shows `Alt+N` per row.
- Two ECS systems, registered once and gated by the module: one boolean read per
  player per tick, map writes only on a walk start or stop.

### Icons and a smaller rail

- Rail tiles, the Shortcuts page and the menu use **vanilla icons** — Hytale's
  flat category icons (Signs, Container, Shields, Ore, Recipe, Portal, Natural,
  TechnicalBlocks) plus the fist-bump emote for Party and map markers for
  homes, spawn and player warps. Set by `AssetPath` at runtime, the same way
  Endgame & QoL's HUD does; nothing is copied into the mod. Every path is
  checked against `Assets.zip`. Per-slot `icon` override, `none` for the letter.
- Rail tiles are **34 px (from 46)**, icons 20 px, labels 8 px. Unavailable
  slots are veiled rather than recoloured. Group dividers are real gaps now,
  not a line drawn over a tile's edge.
- `rail.tileSize` is gone: it never resized the tiles, which the document fixes.

### AFK zones

- **Players are never placed with water over their head.** Fluid is a separate
  layer from the block, so a lake bed reads as air with water in it; water is
  not a blocked fluid (wading pools work), so the scan accepted spots that
  drowned an AFK player over time. Every cell above the feet must now be dry.
- **A zone is the box its preview shows.** The pos1/pos2 preview drew whole
  blocks; the zone itself tested the raw foot positions, so the edge blocks were
  only partly inside; and the size message rounded a third way. All three now
  use whole blocks, inclusive. Existing zones read the same way, no data change.

## Unreleased — Hytale 0.7.5, and the tick comes back

Server-side checks only (build, `check`, UI document validation, the 0.7.5
server jar); nothing here has been seen in a game client yet.

### Hytale 0.7.5

- Builds against `0.7.5`. Nothing the mod uses was removed or re-signed: the
  release adds QUIC pacing and frame checks, `ConnectionHandler#handlesPacket`,
  `CertificateTrustPolicy` and a Sentry filter, all engine-internal. The
  manifest's `>=0.7.0 <0.8.0` already covered it.

### Crate effects no longer cost a world tick

The idle-effect loop ran **on the world thread** and did a spatial query per
crate per tick, plus a registry lookup and a markup strip per crate for the
floating name. A few hundred crates meant hundreds of spatial queries a second
whether or not a player stood anywhere near one. That was the lag.

- The tick now **plans off-thread and only spawns on-thread**. Player positions
  are read from `PlayerRef#getTransform` / `#getWorldUuid`, which are plain
  field reads rather than entity-store lookups, so "who is near what" is pure
  computation on the scheduler. A world with nothing to draw is never woken at
  all.
- Per-crate spatial queries are gone, replaced by one squared-distance test per
  player per crate against a position snapshot.
- Nameplate text is built off-thread and only for crates a player is near.
- Idle timers are pruned by walking the timers rather than rebuilding a set of
  every crate-by-slot pair each tick, and the crate list is read through a new
  uncopied `CrateService#all()` instead of the sorting `list()`.

### Reveal animation off the world thread

Each frame hopped to the world thread and built its command list there. Now the
step claims its slot and builds the frame on the scheduler, and the world
thread does only `sendUpdate`, which is the part that reads the player. Step
claiming and landing are taken under the page's monitor, because frames are
timed on a pool and Skip lands the reveal from the world thread.

### Scheduler

- The shared pool was **2 threads** for every timer the mod owns — AFK, teleport
  warmups, RTP search, playtime, crate effects and now every reveal frame. It
  is now half the host's processors (2–8), overridable with
  `performance.schedulerThreads`.
- The pool starts after the config that sizes it is read.
- The tutorial autosave no longer parks a scheduler thread on `saveAll().join()`.

### Networking: smaller UI packets

An old-style page sends its whole command array in **one** `CustomPage` packet;
the newer `UISurface` chunks at 64. A server with several hundred warps was
sending thousands of commands in a single packet, and 0.7.5's
`QuicheChannel#allocateLargePacketBuffer` logs a warning and allocates an
oversized buffer for exactly that shape.

- `MysticPage.MAX_LIST_ROWS` (150) caps how many rows one build draws, applied
  to warps, the wiki page list and article bodies, kits, the LootBox catalog
  and receipt history.
- Every capped list **says it was cut** ("150 of 480 warps — search to narrow").
  Lists bounded by one object (a mail's attachments, a kit's items, a reveal's
  reward table) are untouched.

## Unreleased — Hyzion shell: shortcut rail, server wiki, theme pass

Server-side checks only (build, `check`, UI document validation, engine source);
nothing here has been seen in a game client yet.

### Navigation Shell (new module `shell`)

- **Shortcut rail** beside the chat panel: nine numbered tiles, the active chat
  channel marked with a gold rim, unavailable slots dimmed **with the reason**
  (no guild, no party, store unconfigured, module off). Position, size, caption,
  separators and every slot are configurable; each player can hide it and the
  choice is remembered.
- **`/1` … `/9`**, `/shortcuts`, `/shortcuts <1-9>` and `/shortcuts rail`.
- **`/menu`** — the Adventurer's Menu: a section rail (Overview, Adventure,
  Community, Services, Account, Settings, Staff) over a two-column card grid.
  Cards come from Essentials' own modules, from the first-party ecosystem
  (MysticQuests, MysticRPG, MysticGuilds, MysticEconomy, MysticIdentity,
  MysticNameTags, MysticModeration, MysticGeneration) detected by plugin id with
  no compile-time dependency, and from cards other mods register.
- A module that is absent, not started, disabled or not permitted shows as an
  explained card rather than vanishing. Cards are re-resolved when pressed and
  every action authorises server-side.
- **`ShellService` / `MenuDestination`** for other mods, via
  `MysticEssentialsProvider.get().getShellService()`.

**Correction:** this release first said ALT+1–9 was impossible from a server-side mod. It is possible — see the ALT shortcuts entry above.

### Server Wiki (new module `wiki`)

- In-game, editable wiki: categories, search across the whole wiki, `[[links]]`
  between pages, per-article view permissions, and a last-edited footer.
- **Editor in game** (`/wiki new|edit <id>`, or Edit on a page): title,
  category, summary, tags, view permission and body. A page's id is fixed after
  the first save so existing `[[links]]` cannot be silently broken.
- **Other mods' pages**: `WikiService#registerEntry` adds a page rendered from
  its owner on every open, so a mod's reference cannot go stale and leaves with
  the mod. Stored and contributed pages share one id space, one search and one
  link syntax.
- The shell publishes its own contributed page, written per reader, listing the
  slots that player can actually use.
- Articles persist to `data/modules/wiki/articles.json`, hand-editable and
  re-read by `/mystic reload`.

### Hyzion theme pass

- `MysticTheme.ui` now carries the Master UI/UX Design Bible's Hyzion palette
  (canvas `#101b2c`, panel `#16263b`, card `#203550`, border `#3a506b`, royal
  `#b38a4e`, gold `#d9b572`, blue `#67a5da`, parchment text `#f3ebdd`), and the
  legacy literals were retinted across all 121 `.ui` documents and the
  UI-facing Java colour tables. Config defaults (chat formats, notification and
  item-view settings) were left alone so saved server configs are untouched.
- Every text token still passes WCAG AA on the game's window fill; the existing
  contrast check is unchanged and passes.

## Unreleased — Notifications fix and LootBox presentation v2

Server-side checks only (build, verify tasks, engine source); nothing here has
been seen in a game client yet.

### Notifications

- **Announcements show their text again.** The event title's second line was lost
  two ways: a one-line title passed a null secondary that the engine dereferences,
  and coloured text became an empty message with children. Both lines now go out
  as plain single-line text, cut at 48 / 110 characters (chat keeps the full text).
- The title's stay and fade times were passed in the wrong order, so it showed for
  the fade-in time only. Fixed.
- Only `critical` notices use the large Major title; everything else uses Default.
- The banner shows the title and the text, not the bare title.
- **AFK notices are chat-only** (no title, toast, banner, action bar or sound).

### LootBox

- **Reveal:** presentation adapters with a **horizontal reel** (default),
  **grid roulette** and **quick reveal** (`opening.presentation`; reduced motion is
  always quick). The result is drawn and recorded before the reveal starts; the
  reel is planned around it, its filler is not weighted, and it pushes at most
  8 updates a second. Every reward's exact chance stays on screen beside it.
- **Pity & Duplicates** tab in the pool editor, plus `duplicateReplacement`: an
  owned EXCLUDE_OWNED reward's chance can go to a chosen reward instead of being
  spread. The pool editor is now 1050 wide.
- **Crate effect stack:** any number of extra particles and sounds per crate, with
  phase, height, scale and interval, picked from the server's loaded ids, under a
  per-world and per-player idle particle budget.
- **Floating crate names:** the holder entity now has vanilla's empty model, and
  `crates.nameplates` defaults to on. Still unconfirmed in a client.
- **Tab completion** for `/lootbox` and `/lb`: subcommands and the ids after them.
- Not in this update: multi-line glyph / PNG crate holograms.

## Unreleased — LootBox Advanced Behaviour and Network (Phases 3–4)

Everything here is checked on the server side only; none of it has been tried in
a game client or against a real MySQL and Redis pair yet.

### LootBox: advanced pools

- **Bundles** (one outcome, up to 16 actions) and up to 4 **bonus rolls** with
  their own labelled odds, shown as their own sections of the odds list.
- **Eligibility conditions** on pools and rewards: permission, world, region,
  date window, plus provider conditions. Ineligible rewards are either dropped
  from the player's odds or block the opening, as the pool says. A condition the
  server can't answer refuses the opening.
- **Pity** (hard and soft) and **duplicate protection** (`duplicates:
  EXCLUDE_OWNED`). Players see their own exact "your next opening" odds and a
  plain-language note before they confirm; the confirmation is bound to that table.
- **Scheduled and time-limited odds** (`startsAt`, `endsAt`, `afterEnd`), a banner
  in the preview, `/lootbox schedule`, and scheduled rollbacks
  (`/lootbox rollback <pool> <rev> at <instant>`).
- **`/lootbox chance <pool> <itemId>`** and a "chance of getting each item"
  section: exact, over every path.
- **Provider API** for other plugins (`RewardProvider`, `ConditionProvider`), with
  `IDEMPOTENT`, `RECONCILABLE` or `AT_MOST_ONCE` delivery. A pool whose provider is
  missing can't be opened, and nothing is ever substituted. `/lootbox providers`.
- **COSMETIC and RPG rewards** through providers; COSMETIC rewards must say how
  duplicates are handled. Built-in COSMETIC provider `permission` (LuckPerms).
- **Mystic integrations**, each optional and found by class presence:
  MysticEconomy deposits, MysticQuests (`QUEST_COMPLETED`, and the quest event
  `mysticessentials:lootbox_key`), MysticRPG (pending its 0.7 build),
  MysticModeration (flags become reports), and the placeholders
  `lootbox_keys`, `lootbox_keys_<ns>_<name>` and `lootbox_pending`.
- **Paid grants are stricter:** under `PAID_RANDOM_RESTRICTED`, a PAID grant now
  also needs MysticIdentity to report a known non-managed account and, while
  `integrations.identity.requireAgeSignal` is on (the default), an adult signal
  MysticIdentity doesn't have yet. Until then PAID grants are refused.
- **Native key items** (`identityMode: REGISTERED_CUSTOM_ASSET`), behind
  `keys.nativeKeyItems`, off until checked in game.

### LootBox: network and analytics

- New config section `network`. With the shared MySQL/MariaDB provider, Redis and
  the same `secret.key` everywhere, pools with `scope: NETWORK` open under a
  per-player lock with a fencing token; a stale server can't spend or deliver.
  Without any of those, NETWORK pools refuse to open and spend nothing.
- Pool and key changes reach the other servers at once, and a NETWORK opening
  re-checks the shared revision before it spends.
- **`/lootbox stats`**: observed against published chances with a z-score per
  reward; hourly anomaly entries in the audit log; unusual luck flagged to staff.
- **Retention** for settled openings and statistics (`retention.*`), at least 365
  days of everything under `PAID_RANDOM_RESTRICTED`.
- Audit exports now carry pseudonyms instead of player UUIDs unless the exporter
  has the new `mysticessentials.lootbox.audit.identifiers`.

### Core

- `StorageService.updateShared`: a read-modify-write that is atomic across every
  server sharing the MySQL/MariaDB store (one transaction holding the row lock,
  retried on deadlocks and races). On JSON storage it is the usual `update`.

## Unreleased — LootBox Creator (Phase 2)

The in-game LootBox creator. Pools, keys and crates can now be built, published,
placed and looked after without touching a file or typing a staff command, and
the player screens gained reduced motion and a readability pass. None of these
screens has been tried in a game client yet.

### LootBox

- The `/lootbox admin` Manager lists every pool with its draft status: Draft,
  Invalid (with the first problem), Ready to publish, Published or Disabled. It
  has a search box, a status filter, and one action bar for the selected pool.
- **New Pool** writes an empty draft, **Clone** copies a draft under a new id,
  and **Archive** moves a draft file to `lootboxes/_archive/` after asking. Nothing
  is ever deleted, and a published revision stays live until the pool is closed.
- **Publish** in the Manager now asks first, and only appears when the draft is
  valid and differs from the live revision.
- Draft files are read and written through a draft store that refuses to save
  over a file changed since it was loaded, and audits every create, save, clone
  and archive.
- **Pool editor** (Manager → Edit; New Pool opens it too). One working copy across
  four tabs:
  - General: name with a coloured preview, description, icon from a searchable
    item picker (or the held item), and odds mode. Switching between weights and
    exact percentages converts the numbers exactly where it can and lists every
    reward it had to round.
  - Keys & Rules: link published keys, cooldown, extra permission node.
  - Rewards: the list with each reward's live chance and "1 in N", Add,
    Duplicate, Up/Down and Delete (asks first). The selected reward has its id
    (fixed once published), name, rarity, weight or percent, and type. Item
    rewards take an item from the picker and a quantity; economy rewards an
    amount; permission rewards a node and duration; console commands a
    template, an allowlisted pattern and a "safe to repeat" switch. There is an
    announce switch, and the total weight, or how much of 100% is assigned.
    Typed text that does not parse stays as typed and blocks switching rewards,
    so it is never applied to the wrong one. Changing COMMAND rewards needs
    `rewards.command`.
  - Publish: what blocks publishing, and every change from the live revision with
    chances as old % → new %. Publish asks first and saves unsaved changes before
    publishing.
- Saving is explicit and never silent about conflicts: if the draft file changed
  since it was opened, Save is refused and offers Load From Disk or an audited
  Overwrite. Closing with unsaved changes asks first.
- **Key Manager** (Manager → Keys): every key definition with its status and the
  pools it opens, search, a status filter, New Key, Clone, Archive and Publish
  (both ask first; keys already given out keep their current look).
- **Key editor**: base item from the item picker, name with a colour picker and
  Bold/Italic switches, five lore lines, uses and expiry, a live preview of the
  key item, the pools it opens, and its problems and changes. Saving, conflicts
  and Publish work like the pool editor.
- New permission `mysticessentials.lootbox.edit` for New Pool, Clone, Archive and
  the editors; `mysticessentials.lootbox.keys` also opens the Key Manager.
- **Key grants** (Key Manager → Grants, or `/lootbox key grants <keyId>`): give a
  key to a typed player name or to online players picked from a list, look up and
  revoke a serial or a batch, and read the key's recent history. Giving more than
  one key, or to more than one player, asks first. A PAID source is still refused,
  and the refusal is recorded. Viewing needs `keys`, giving `keys.give` and
  revoking `keys.revoke`, each checked again on every press.
- **Key deposit and withdraw**: `/lootbox key deposit` turns the key in the active
  hotbar slot into virtual balance (a serialized key credits its remaining uses;
  keys that expire can't be deposited). `/lootbox key withdraw <keyId> [count]`
  turns balance back into key items, which open once and never expire; what
  doesn't fit goes to `/lootbox pending`. Both run as a recoverable saga, finished
  on join and by the 5-minute sweep, and every conversion is audited. The Catalog
  has a **Deposit Held Key** button too. `keys.allowConversion` switches both off.
- **Stackable keys** (opt-in per key, Stacking: On in the key editor): a grant is
  issued as one stack instead of separate items, and each item of the stack opens
  once. A stack is weaker against duplication: the batch counter, not the item,
  decides how many openings are left, so a copied stack item uses up the batch.
  Stacks only form when the base item stacks, and different grants never merge.
  `key info` and `key revoke` accept `batch-…` ids; revoking a batch kills every
  item in it. A stackable key must have 1 use; publishing refuses more.
- **Crate Manager** (Manager → Crates): every placed crate with its world,
  position, pool and status, with search, a filter, Edit, Disable/Enable, Remove
  (asks first) and Verify This World. **Convert Looked-At Block** opens the crate
  editor on the block you look at.
- **Crate editor**: the placement workflow as steps. Block and Check (the
  compatibility report and its warnings), Pool (published pools only), Use (preview
  first, or straight to the confirm screen, which still lists every reward with its
  exact odds), Protect (can only tighten the server's protection), Visuals, and
  Finish (Create or Save, and Test in World, which is a real opening and spends a
  key). Crates the server turned off get a Repair step: turn it back on, use the
  block that is there now, or remove it. A save is refused if someone else saved
  the crate since you opened it.
- New crate commands: `/lootbox crate edit [crateId]`, `crate rebind <crateId>
  <pool>`, `crate remove <crateId> [force]`, and `crate effects [on|off]` for
  players. `crate select` lists compatibility warnings.
- **Crate visuals**: idle and opening particles and an opening sound per crate,
  shown only to nearby players who haven't turned them off. New crates get the
  defaults from `crates.defaultIdleParticle`, `defaultOpenParticle` and
  `defaultOpenSound`; existing crates keep none. **Floating names** (off by
  default, `crates.nameplates`) are the same for everyone. Glow is not supported.
- **Generated block variants** (off by default, `crates.generatedVariants`): a
  plain block can become a crate through a look-alike written to
  `mods/MysticEssentialsGenerated/`. The swap keeps rotation, removing the crate
  puts the original back, and unused look-alikes are cleaned up at startup. Only
  single blocks without block data, states or connections qualify. Removing such a
  crate needs its chunk loaded; `crate remove <id> force` skips the restore.
- **Simulation** (Manager → Simulate): runs 100,000 draws at once (or
  `simulation.maxTrials` if lower), then any number up to that cap. Each reward
  shows its exact chance, the share it got, the count against the expected count
  and the gap, with two bars. It warns when the rarest reward is expected fewer
  than 10 times. Nothing is awarded, spent or recorded.
- **Audit History** (Manager → Audit, or `/lootbox audit`): filter by pool or
  target, actor or player, action or group, and time window, 10 records a page,
  newest first. **Export** writes the filtered records as JSON or CSV to
  `exports/` in the module's data folder and records the export.
- **Template Library** (Manager → Templates): save a key look from a key draft, a
  reward set from a pool draft, or a crate look (particles, sound, floating name)
  from a placed crate. Apply a look to a key draft or a crate, insert a set into
  a pool draft, rename or delete (to `templates/_archive/`). Applying shows what
  changes first and is refused if the target changed meanwhile. Applying a crate
  look also needs `crates.manage`.
- **Reduced motion**: `/lootbox motion [reduced|full|default]` and a Motion button
  on the Catalog. Reduced shows each reveal's result at once; the odds and the
  result never change. `opening.reducedMotionDefault` sets it for players who
  never chose.
- **Readability**: hints, notes and odds footers on the LootBox screens now read
  at 4.5:1 contrast or better. Long names shrink to fit their row instead of
  running past it; problem lists, diffs, confirmation lines, preview rules and the
  key lore preview wrap. The item picker's search box has focus when it opens. No
  LootBox window is larger than the game's own pages (1050 × 700).

### Changed

- The theme's dim text colour (section titles, key labels, counts) is a little
  lighter on every Mystic Essentials page (`#778292` → `#858e9d`), so it reads at
  4.5:1 on the window background.

### Fixed

- Text fields in the LootBox creator screens were bound to `##Id.Value`, so they
  would neither show nor send their values on a client.

### Verification

- `verifyLootBox` covers the editor's odds conversion (exact both ways where
  possible, reported rounding, refusals), the publish diff, the reward list
  operations, that the editor's chances equal the player preview's, key name
  styling (colour, bold, italic) and the key diff.
- New `verifyLootBoxDrafts` check: save/load round trips, conflict detection,
  clone and archive, and the Draft → Invalid → Ready → Published lifecycle.
- New `verifyLootBoxKeys`: the bounded grant history, the conversion saga's
  rules and recovery, and stack spending, splitting and revoking.
- New `verifyLootBoxCrates`: compatibility, repair decisions, protection and
  crate documents, including old ones.
- New `verifyLootBoxScreens`: simulation rows and warnings, audit filters,
  paging, export escaping and caps, and templates (key looks, reward sets with
  colliding ids, crate looks, optimistic saves).
- New `verifyLootBoxAccessibility`: reduced motion, the contrast of every theme
  text colour and every LootBox label's runtime colour, wrapping and fitting text,
  window sizes and focus.
- On a 0.7.2 dev server, republishing an existing pool and key still answers
  "unchanged" after the draft store change. Every creator screen, reduced motion,
  floating names and generated variants still need an in-game test.

## 1.0.6 — LootBox Module

Adds LootBox, a free module for reward pools that show their exact odds before
anything is spent, opened with signed keys from crates or a menu. It ships
disabled.

### Compatibility

- Built and verified against Hytale Server `0.7.2`; the supported range stays
  `>=0.7.0 <0.8.0`.
- The module is off by default. Enable it with `"lootbox": true` in the main
  config's `modules` map and `enabled: true` in `modules/lootbox/config.json`.

### LootBox

- Pools are authored as draft files in `modules/lootbox/lootboxes/` and frozen
  into immutable, numbered revisions with `/lootbox publish`. Publishing validates
  the whole draft and refuses it with a list of problems. `/lootbox rollback`
  republishes an older revision as a new one.
- Odds are `WEIGHTED` or `EXACT_PERCENT` (summing to exactly 100%). Previews,
  confirmations and receipts show each reward's exact chance, "1 in N" and the
  revision's odds id. A non-zero chance is never displayed as 0%.
- Keys are defined in `modules/lootbox/keys/`. Physical keys are any item carrying
  an HMAC-signed, serial-numbered marker; virtual keys are a per-player balance.
  Staff issue keys with `/lootbox key give` and can revoke a serial.
- Opening: the player confirms, then the key is spent and the result drawn with
  `SecureRandom` in a single write. A skippable reveal follows, then the reward is
  delivered. Interrupted openings finish on join and every 5 minutes, item
  deliveries that cannot be confirmed are held for `/lootbox reconcile` instead
  of being given twice, and repeated confirms are deduplicated.
- Rewards: items, economy deposits, LuckPerms permission nodes (optionally
  timed), and console commands that must match an allowlisted pattern. Items that
  do not fit go to a pending inbox (`/lootbox pending`).
- Crates: bind any block that has a Use interaction (chests, doors, benches) with
  `/lootbox crate select` and `/lootbox crate convert`. Crates are protected from
  breaking, damage and explosions. If a crate's block is replaced or destroyed,
  the crate disables itself, and staff are alerted when it is destroyed.
- New **Mystic Crate** block (`MysticEssentials_LootBox_Crate`) in the bundled
  asset pack, for builders who want a dedicated crate. The module logs at startup
  whether it loaded.
- Player screens: catalog, preview, confirmation, reveal, history and pending
  inbox. Staff get a manager screen with Publish, Close/Reopen and Simulate.
- `/lootbox simulate` draws up to a million times without awarding anything and
  compares expected with observed rates.
- The `FREE_GAMEPLAY_ONLY` policy profile (the default) refuses key grants marked
  `PAID`.
- Hourly audit counters are kept for `audit.retentionDays` (400).
- `LootBoxApi` (`getModule("lootbox")`) and `MysticEvent`s for addons.
- New permissions under `mysticessentials.lootbox.*`; see `PERMISSIONS.md`.

### Core API

- `PermissionService` gains `canGrant()` and `grant(player, node, duration)`,
  backed by LuckPerms. The LuckPerms node classes load only when LuckPerms is
  present, so servers without it are unaffected.

### Verification

- New `verifyLootBox` check: odds resolution, formatting, pick boundaries, a
  chi-square test over a million draws, key markers, command templates, the
  recovery decision table and the draft validator.
- New `verifyLootBoxSaga` check, run over an in-memory store:
  - a confirmation is refused if the odds were republished after the preview;
  - a doubled confirmation produces a single opening;
  - multi-use keys spend one use per opening;
  - virtual balances never go below zero under concurrent openings;
  - after a crash at each saga step, recovery delivers exactly once.
- Every new UI document passes `validateUiDocuments`.
- On a 0.7.2 dev server, from the console: publishing, simulating, rolling back,
  closing and reopening pools, publishing keys, and reloading after a restart.
  Player flows (screens, keys, crates) still need an in-game test.

## 1.0.5 — Hytale 0.7.0

Mystic Essentials 1.0.5 moves to Hytale Server 0.7.0, whose chunk and spawn APIs
changed shape.

### Compatibility

- Built and verified against Hytale Server `0.7.0`.
- Supports Hytale Server `>=0.7.0 <0.8.0`; 0.6.x servers stay on 1.0.4.
- Compiles against the MysticIdentity API `0.1.1`.

### Hytale 0.7.0 Migration

- `/tp world` and world portals resolve spawn points through the asynchronous
  spawn-provider API. A portal applies its teleport on the player's world thread
  once the point resolves.
- `/top` and random-teleport ground sampling read column heights from the chunk's
  `HeightmapColumn` component and blocks from chunk-section components, replacing
  the removed `WorldChunk#getHeight` and `BlockChunk#getBlock`. The height is the
  same opacity top as before, so destinations do not move.
- Event-title notifications use the `EventTitleStyle` overload instead of the
  deprecated `isMajor` flag.

## 1.0.4 — Integration Upgrade and Lifecycle Reliability

Released August 30, 2026.

Mystic Essentials 1.0.4 upgrades every bundled integration dependency, corrects
optional-plugin metadata and load ordering, and makes integration reload and
shutdown behavior safe for long-running Update 6 servers.

### Compatibility

- Built and verified against Hytale Server `0.6.2`.
- Supports the Update 6 patch line: Hytale Server `>=0.6.0 <0.7.0`.
- Keeps every third-party plugin integration optional; missing providers no
  longer prevent Mystic Essentials from loading.

### Dependency Upgrades

- Updated the Hytale compile target from `0.6.0` to `0.6.2`.
- Updated VaultUnlocked to `2.20.1` and migrated economy calls to its current
  `BigDecimal` formatting and account-creation API.
- Updated HikariCP to `7.0.2`, MariaDB Connector/J to `3.5.8`, MySQL Connector/J
  to `26.7.0`, Jedis to `7.4.1`, and jsoup to `1.23.2`.
- Retained PlaceholderAPI-Hytale `1.0.8` and LuckPerms API `5.5`, which remain
  their compatible current API lines.

### Optional Plugin Integrations

- Corrected `manifest.json` so LuckPerms and VaultUnlocked are optional instead
  of hard dependencies.
- Added optional load-order declarations for MysticVanish and MysticRPG.
- Removed compile-time dependencies on local MysticVanish and MysticModeration
  jars; both bridges now remain unbundled and fail safely when absent.
- Reworked MysticVanish access around its current provider API.
- Fixed MysticModeration discovery when it loads after Essentials by resolving
  the live plugin through its own classloader, without creating a dependency
  cycle.
- Documented the complete integration surface: LuckPerms, PlaceholderAPI,
  VaultUnlocked, MysticVanish, MysticModeration, MysticRPG, QuestLines,
  SimpleEnchantments, LuxReforge, Redis, MySQL, and MariaDB.

### Cross-Server (Redis)

- Every connected server now publishes a roster of its online players over
  Redis. `/tpa`, `/tpahere`, `/msg` and `/mail send` tab-complete and resolve
  players on any server in the network, and the Teleport Requests UI lists
  remote players with their server id.
- Teleport requests to a player on another server are stored in Redis (keyed
  by the target, expiring with `requestExpirySeconds`) as well as announced
  over pub/sub. The target's server pulls the stored requests whenever the
  player opens the UI or runs `/tpaccept`/`/tpdeny`, so a request is never lost
  to a missed announcement or a server switch; `/tpcancel` withdraws the stored
  copies too. Incoming notifications and UI rows name the requester's server.
- Accepting a cross-server request refers the mover to the destination server
  and completes the teleport once they are ready in a world there (no warmup,
  no movement cancellation — a freshly spawned player settles for a moment and
  the old connect-time attempt was cancelling on that or firing before the
  entity was placed). Configurable via `crossServer.arrivalDelaySeconds` and
  `crossServer.arrivalTimeoutSeconds`.
- `/tp <player>` and `/tphere <player>` accept players on other servers: `/tp`
  sends the staff member to that server and completes the move on arrival,
  `/tphere` brings the player over and then to the staff member.
- The arrival details for a transfer travel in the engine's referral payload
  (`referToServer(host, port, data)` → `PlayerSetupConnectEvent.getReferralData()`
  on the destination), with the Redis record kept only as a fallback.
- Referral payloads are only honoured when they match the arrival record the
  referring server wrote to Redis, so a client cannot hand-craft one to be
  teleported to another player on connect.
- `storage.redis.proxyHost`/`proxyPort`: with a network proxy (MysticGate) in
  front, every referral goes to the proxy with the destination server in the
  payload, so backends can stay on private addresses. The payload contract a
  proxy must honour is documented under Storage.
- `/mystic network` (`mysticessentials.network`) shows this server's advertised
  address and every other server on the roster with its address, player count
  and heartbeat age. Referrals are logged on both ends.
- Servers advertise the address other servers refer players to. It is
  auto-detected from the engine (bound game port, bound interface or first
  LAN/public IPv4) and logged at startup; `storage.redis.advertisedHost` /
  `advertisedPort` override it behind NAT, Docker or a proxy. Accepting a
  request whose destination has no usable address now replies with a clear
  message and logs a warning instead of a generic "player is no longer online".
- Chat lines arriving from another server in a `crossServer` channel are
  prefixed with their origin (`channels.crossServerPrefix`, default
  `&8[&b{server_id}&8] `, skipped when the channel format already places
  `{server_id}`).
- Private messages to a player on another server resolve through the roster
  and relay by UUID; the sender's echo shows the recipient's name, and a player
  online nowhere falls back to mail instead of a relay nobody receives.

### Build

- The plain `jar` task now writes `MysticEssentials-<version>-thin.jar`. It
  used to share the shaded jar's file name, so a `gradle build` could leave a
  jar without Jedis/JDBC/jsoup in `build/libs` — deploying that made Redis fail
  at startup with a class-not-found wrapped as "connection failed" and every
  cross-server feature silently local-only. Deploy `MysticEssentials-<version>.jar`.

### Lifecycle and Reliability

- `/mystic reload` now reapplies LuckPerms, PlaceholderAPI, VaultUnlocked,
  MysticVanish, MysticModeration, and Redis configuration changes.
- PlaceholderAPI expansions and aliases now unregister cleanly on reload and
  shutdown; generation-aware retries cannot resurrect disabled registrations.
- LuckPerms and Vault provider state now resets correctly when an integration is
  disabled or disappears during reload.
- Economy calls now reject invalid UUIDs, negative amounts, and non-finite
  values, while safely containing provider failures.
- Redis now uses Jedis' supported pooled `RedisClient`, retains registered
  logical handlers across reconnects, and prevents stale subscriber threads
  from surviving a reload.
- Redis command clients now close safely during failed startup, reload, and
  server shutdown.

### Hytale API Corrections

- Migrated RTP block reads to Update 6's component-backed `BlockChunk` API.
- Migrated message BSON decoding to the current codec method with `ExtraInfo`.
- Replaced manual item-stack reconstruction with `ItemStack.withQuantity` where
  the current immutable API is available.
- Narrowly marked unavoidable raw item-metadata reads as compatibility
  boundaries; Hytale does not yet expose a non-deprecated API for lossless
  enumeration of complete stack BSON.
- Fixed rank prefixes and suffixes disappearing from the Server Players list on
  the map screen. Update 6 moved the engine's roster broadcast onto
  `PlayerReadyEvent`, which fires after `PlayerConnectEvent` and overwrote the
  decorated names; the list service now follows that event, which is keyed and
  so requires a global event registration.
- Decorated rows are now re-asserted when a player's spectator or
  remaining-lives state changes, because Update 6 re-broadcasts the plain
  username on both transitions.
- Player-list rows are no longer sent to a viewer who has that player hidden,
  matching how Update 6 filters the engine's own player-list broadcasts.
- Added strikethrough text support. Update 6 added a `strikethrough` field to
  the `FormattedMessage` protocol, so `&m` and the new
  `<strikethrough>`/`<st>`/`<s>` tags now render instead of being discarded;
  `&k` obfuscated still has no field and is still dropped.
- Tutorial page buttons with a `sound` action now play the sound. The action had
  been logged and skipped against an out-of-date note claiming no verified
  play-sound-to-player API existed; it uses the same
  `SoundEvent` asset-map lookup plus `SoundUtil.playSoundEvent2dToPlayer` path
  as the custom-command sound action.
- Re-verified every remaining Hytale API note against the 0.6.2 jar and
  corrected the ones Update 6 changed. Notably: the native `UpdateBossBar`
  packet is entity-bound (`entityNetworkId`, `name`, `hide`) with no progress
  value, so the notification banner stays a custom HUD document; death still has
  no event, so the inventory death-snapshot poll stays; processing benches still
  have no craft veto; and the `Inventory` convenience accessors
  (`getItemInHand` and its whole family) are now deprecated for removal with no
  replacement that expresses "item in hand".

### Interface Redesign

- Every custom UI page now shares one design system, `MysticTheme.ui`: a
  single palette and type scale, bordered panels for whatever is being viewed
  or edited, flat wells for lists, and gold reserved for "this one".
- Master/detail pages (Homes, Warps, Player Warps, Kits, Chat Channels, the
  dialog builder) now mark the selected row with a gold spine and tint instead
  of leaving the list unmarked.
- Rows gained an icon well for their colour chip or item icon, two-line
  name/meta typography, hover and press feedback with the game's button sounds.
- Action bars follow one rule everywhere: primary action first, destructive
  action pushed to the far end.
- Forms (Warp Admin, Portal, Vault Editor, temp channels, Nick, Mail Admin,
  Dialog Builder) use uppercase field eyebrows, consistent 38/44 px rows and
  grouped sections; long forms scroll while their footer stays fixed.
- Notification and mention settings toggles became full-width setting rows;
  filter and audience choices became tab strips.
- Patch-note added/removed lines now carry their green/red tint (the span
  colour was silently overriding the template before) and show +/- markers.
- HUD overlays (warmup, RTP status, banner, action bar) share one quiet
  accent-spine style.
- `validateUiDocuments` also checks `MysticTheme.ui` components, and requires
  every theme parameter to carry a default.
- Removed the unreferenced `ItemStatRow.ui` template.

### Verification

- Added `verifyIntegrationContracts` to Gradle's standard `check` lifecycle.
- The contract check protects optional manifest metadata and the external
  LuckPerms, PlaceholderAPI, VaultUnlocked, Redis, SQL, and GUI-parser APIs.
- Verified the shaded jar contains its required database, Redis, and jsoup
  runtime libraries while excluding all optional plugin APIs.
- Passed a clean compile, the complete compatibility suite, 347 UI component
  validations, and the shaded release build.

### Upgrade Notes

1. Back up the server and Mystic Essentials configuration directory.
2. Replace the previous jar with `MysticEssentials-1.0.4.jar`.
3. Restart the server for the jar upgrade. Later integration configuration
   changes can be applied with `/mystic reload`.
4. No configuration migration is required from 1.0.3.

## 1.0.3 — Update 6 Compatibility, Craft Blocking & Safer RTP

Mystic Essentials 1.0.3 completes the migration to Hytale Update 6, adds server-side craft blocking and MysticRPG-aware random teleport safety, expands ItemView metadata support, and refines notification history and sound behavior.

### Compatibility

- Requires Hytale Server `>=0.6.0 <0.7.0`.
- Built and verified against Hytale Server `0.6.0` and protocol `hytale/3`.
- Update 5 servers and clients are not compatible with this release.

### Hytale Update 6

- Migrated flight handling to Update 6's three-state `FlyMode` API.
- Updated commands that intentionally have no permission requirement to use the new explicit command API.
- Updated player-list packets for spectator state and remaining-lives data.
- Updated Editor Builder block-change packets to the expanded Update 6 layout.
- Migrated chunk and fluid checks to the new chunk-component APIs.
- Migrated inventory, mail, vault, and related item operations to ECS inventory components.
- Updated the bundled manifest and documentation for the new server version range and protocol.

### Craft Blocking

- Added a new CraftBlock module for denying configured recipes or output items during bench and hand crafting.
- Configure case-insensitive recipe or item IDs in `modules/craftblock/config.json`; `*` wildcards are supported.
- A matching output item blocks every recipe that produces it.
- Added `/craftblock` to inspect the active block list and `/craftblock check` to inspect the held item.
- Added global and per-item bypass permissions:
  - `mysticessentials.craftblock.bypass`
  - `mysticessentials.craftblock.bypass.<item id>`
- Added optional toast, chat, and server-log feedback with notification cooldown protection.
- Processing-bench recipes are not intercepted by Hytale's craft pre-event and are not covered by this module.

### MysticRPG-Aware Random Teleport

- Added an optional, soft integration with MysticRPG's World module.
- RTP now checks candidate and final destinations against the requesting player's current RPG level when the integration is available.
- The default accepted content range is 10 levels below through 3 levels above the player and is configurable under `mysticRpgSafety` in `modules/teleportation/rtp.json`.
- MysticRPG safe regions remain valid regardless of content level.
- Centered RTP shapes narrow inward when content is too high-level and expand outward when it is too low-level.
- Added configurable behavior for integration errors and additional admin diagnostics.
- Servers without MysticRPG continue to use the normal RTP safety checks.

### ItemView and Mod Metadata

- Expanded portable item metadata rendering for enchantments, LuxReforge, and MysticRPG gear.
- Added structured MysticRPG display support for names, descriptions, templates, rarity, quality, affixes, durability, binding, and gear details when present.
- Improved native item-quality resolution with runtime quality-asset lookup and validated configuration overrides.
- Improved Item Details layout and rich-text handling for larger and more complex metadata sets.
- Unknown metadata remains visible in technical form instead of being silently discarded.

### Notifications and Announcements

- Important and critical notices are stored in notification history by default.
- Low and normal notices are stored only when the sender explicitly opts in.
- Manual `/broadcast` and `/alert` messages are transient by default at every priority; use `--history` to retain one or `--no-history` to force transient delivery.
- Manual broadcasts and alerts play a sound only when `--sound` is supplied.
- Rotating announcements continue to use their configured broadcast sound.

### Reliability and Verification

- Added compatibility coverage for portable item metadata and native quality assets.
- Added Item Details layout checks.
- Added MysticRPG RTP level-safety and fluid-safety checks.
- Verified all UI assets through the component-instantiation validator.
- Verified the release with a clean Update 6 build.

### Upgrade Notes

1. Back up the server and Mystic Essentials configuration directory.
2. Replace the previous jar with `MysticEssentials-1.0.3.jar`.
3. Update the server and clients to Hytale Update 6 before starting this version.
4. Review the new `modules/craftblock/config.json` file before enabling recipe restrictions.
5. Review `mysticRpgSafety` in `modules/teleportation/rtp.json` if MysticRPG is installed.
6. Review notification automations that depended on implicit history or manual alert sounds; use `--history` and `--sound` explicitly where required.

Existing CustomContent licensing and fallback behavior are unchanged in 1.0.3.
