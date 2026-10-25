# Work order — 2026-10-25 · Notifications + LootBox presentation

Queued by the user on 2026-10-23 for an unattended run at **Sun 2026-10-25 02:00** local
(America/New_York). Everything lands on **one feature branch**, never on `master`.

Companion spec: [opening-experience-bible-v2.0.md](opening-experience-bible-v2.0.md)
(extracted from `MysticEssentials_LootBox_Opening_Experience_Upgrade_Bible_v2.0.docx`
in this folder). Existing plan of record: [implementation-plan.md](implementation-plan.md),
[phase2-work-packages.md](phase2-work-packages.md), [phase0-spike-results.md](phase0-spike-results.md).

## Ground rules

1. **Verify every engine fact through Hytale Workshop MCP.** No engine API, UI tag,
   particle id, sound id or component may be written from memory. The repo has no
   `CLAUDE.md`; the `hytale-modding` skill and the `hytale-reviewer` /
   `hytale-contract-verifier` agents are the house style here. Run
   `validate_hytale_code_refs` over every file touched and `validate_hytale_ui` over
   every `.ui` file touched before committing.
2. **`.ui` files are load-bearing.** A mod `.ui` document that fails to parse breaks
   *other* mods' documents and disconnects everyone at join. Two known causes:
   a missing required `@param`, and an `@param` assigned after a plain property.
   Validate before commit, every time.
3. **Don't regress what works.** The crate editor, pool editor, odds engine, key
   service and opening transaction are shipped and working. This order changes
   presentation and adds editor steps; it does not re-architect the committed-outcome
   transaction path.
4. **Scope control.** Items 1–8 below are the deliverable. The Bible v2.0 is the
   direction of travel for items 6 and 7, not a mandate to build all eight opening
   modes in one night. If time runs short, finish items in the listed order and leave
   the rest untouched with a clear note in the PR body — do not leave half-wired code.

---

## 1 · Announcements render a blank Event Title and a blank banner

**Symptom** (user screenshot): with any announcement on the server, the top banner and
the centre Event Title both show only `Hyzion Survival` — the announcement text itself
never appears. Announcements, broadcasts and alerts also render with text too large.

**Where it lives**

- `src/main/java/org/hyzionstudios/mysticessentials/core/notification/NotificationDelivery.java`
  — `title(...)` builds `primary = notification.title()`, `secondary = notification.subtitle()`
  and calls `EventTitleUtil.showEventTitleToPlayer(player, primary, secondary, style, icon, fadeIn, stay, fadeOut)`.
- `src/main/java/org/hyzionstudios/mysticessentials/modules/announcements/AnnouncementModule.java`
  — `announcement(...)` (~line 160) sets `.title(config.broadcastTitle).subtitle(message).message(message).showAsTitle(true)`;
  the `/broadcast` and `/alert` path at ~line 445 fills `title` from `config.alertTitle`/`config.broadcastTitle`
  and copies `message` into `subtitle` when it is empty.
- `src/main/java/org/hyzionstudios/mysticessentials/core/notification/NotificationConfig.java`
  — `Profile.subtitle` defaults to `false`.

**What to do**

- Find out *why* the secondary line is dropped. `NotificationConfig.Profile.subtitle = false`
  is the first suspect — check whether it gates the subtitle before it reaches
  `EventTitleUtil`. Verify the real `showEventTitleToPlayer` signature and the
  `ShowEventTitle` packet contract through Hytale Workshop MCP before changing anything;
  confirm what the client does with a `null`/blank secondary and with markup in either line.
- Decide and implement the shape the user asked for: **the Event Title is the surface**.
  The yellow line is the *title*, the announcement text is the *subtitle*. Mentions,
  personal notifications and short alerts use the Event Title with a short message.
- Fix the oversized text. Check `MysticText` markup stripping and the `EventTitleStyle`
  choice in `NotificationDelivery.title(...)` (`Major` vs `Default`) — `Major` is
  currently used for anything at `IMPORTANT` or above, which is every alert.
  The user's screenshots show the styles available: `DEFAULT`, `MAJOR`, `GOBLIN BREACH`,
  `VOID EVICTION`. Confirm the full enum through Workshop MCP and pick per category.
- Audit the banner surface too (`showAsBanner`) — the blank blue-bar banner in the
  screenshot comes from the same notification.

**Done when** a `/broadcast`, `/alert` and an auto-announcement each show their actual
text on screen at a readable size, and the chosen style differs sensibly by priority.

## 2 · AFK messages must be chat-only

**Symptom** the AFK notice fires the Event Title / banner surfaces; it should only hit chat.

**Where it lives**
`src/main/java/org/hyzionstudios/mysticessentials/modules/afk/AfkModule.java:489`
calls `announcements.broadcastLocal("&8* &7" + message)`, which routes into the same
`announcement(...)` builder as a real broadcast and therefore inherits `showAsTitle(true)`.

**What to do** give AFK its own send path (or a `broadcastLocal` variant / a
`NotificationCategory` of its own) that sets `showInChat(true)` and leaves `showAsTitle`,
`showAsToast`, `showAsActionBar` and `showAsBanner` off. Do not change the behaviour of
genuine broadcasts to achieve it. Check whether any other module leans on
`broadcastLocal` the same way (grep it) and fix those the same way if they are also
chat-only by intent.

**Done when** going AFK writes one chat line and nothing else, while `/broadcast` is
unchanged.

## 3 · Crate nameplate mode does not show a nameplate

**Where it lives**

- `src/main/java/org/hyzionstudios/mysticessentials/modules/lootbox/hytale/CrateVisuals.java`
  — `ensureNameplate(...)` (~line 360) spawns a holder entity and adds
  `Nameplate.getComponentType()`; `nameplateText(CrateInstance)` at ~line 352.
- `src/main/java/org/hyzionstudios/mysticessentials/modules/lootbox/config/LootBoxConfig.java:117-123`
  — `crates.nameplates` (default `false`), `crates.nameplateHeight` (1.25).
- `src/main/java/org/hyzionstudios/mysticessentials/modules/lootbox/model/CrateInstance.java:84-94`
  — per-crate `visuals.nameplate`.
- Gate at `CrateVisuals.java:257`: `config.nameplates && crate.visuals.nameplate`.

The in-code comments admit this is **unverified** — the nameplate spike (S4) never ran,
and it is unknown whether the client draws a nameplate on an entity with no model.

**What to do** this is the root cause to establish first, before the enhancements in
item 4. Through Workshop MCP, find what the engine actually requires for a nameplate to
reach the client: which components the entity tracker needs (model/visual, `Intangible`,
display, network id), whether `Nameplate` alone is enough, and how vanilla does it.
Then make the existing simple nameplate work end to end — the editor's "HIDE FLOATING
NAME" toggle on step 6 Visuals must produce a visible floating name.

**Done when** enabling the toggle on a crate shows its name above the block in game, and
the editor's warning text (`CrateEditorPage`, step 6) is updated to match reality.

## 4 · Nameplate hologram editor — multi-line, glyphs, PNG banners

This is the feature the user asked for on top of item 3. **Reference implementation:
`F:\Development\MysticNameTags`** (sibling repo, local only). Read it before designing:

- `src/main/java/com/mystichorizons/mysticnametags/nameplate/GlyphNameplateManager.java`
- `src/main/java/com/mystichorizons/mysticnametags/nameplate/GlyphNameplateFollowTask.java`
- `src/main/java/com/mystichorizons/mysticnametags/nameplate/glyph/` (`GlyphAssetManager`,
  `GlyphAssets`, `GlyphInfoCompat`)
- `src/main/java/com/mystichorizons/mysticnametags/nameplate/packet/` (`PacketGlyphSender`,
  `PacketGlyphIdFactory`, `PacketGlyphState`)
- `src/main/java/com/mystichorizons/mysticnametags/nameplate/banner/` (`BannerAssetManager`,
  `BannerInfo`, `BannerQuadModels`)
- `src/main/resources/Common/NPC/MysticNameTags/*.blockymodel`, `glyphfonts/`,
  `server/Models/MysticNameTags/GlyphLineBase.json`
- `src/main/java/com/mystichorizons/mysticnametags/tags/TagDefinition.java` and
  `TagConfigValidator.java` for the config shape and validation

**Requirements**

- **Multiple lines** above the crate, authored in a hologram editor that opens from the
  crate editor's step 6 Visuals when floating-name mode is on.
- **Glyph Mode** — banner-coloured text built from glyph assets, the way MysticNameTags
  does it, with **placeholder support**. Wire placeholders through the existing
  `LootBoxPlaceholders` (`modules/lootbox/integration/LootBoxPlaceholders.java`) and the
  core placeholder service, so crate name, pool name, key requirement and odds summary
  can appear.
- **PNG banners** — a raw image above the crate, usable on its own or mixed with glyph
  lines. Follow the MysticNameTags banner asset path; note the repo's verified finding
  that runtime asset registration works via a RAW `Parent` + inline JSON pack in a
  `mods/` dir, while `writeAssetToDisk` output fails at boot.
- **Per-crate persistence.** Extend `CrateInstance.Visuals` (currently schema 3 — bump it),
  and keep `CrateRules.java:259` / `CrateService.java:315` signature + change-log lines in
  step with the new fields. `TemplateLibrary.java:139,205` also copies `visuals.nameplate`.
- Keep the existing boolean working as the simple case; the hologram config is additive.

**Known constraint** (verified earlier, recorded in project memory): engine nameplates are
**global** — they cannot be shown per player. If the MysticNameTags packet-glyph approach
gives per-viewer control, say so explicitly in the PR body; otherwise keep the global
caveat in the editor text.

**Done when** a crate can carry several lines of glyph or PNG content with placeholders
resolved, edited entirely in game, surviving a restart.

## 5 · More crate particles and effects

**Where it lives** `CrateInstance.Visuals.idleParticle` / `openParticle` / `openSound`
(`model/CrateInstance.java:81-130`), driven by `CrateVisuals` and edited on step 6 of
`ui/editor/CrateEditorPage.java`. Today each is a single free-text asset id with a
dropdown.

**What to do** give the admin a real effects picker instead of one id per slot:

- Enumerate the particle and sound registries through Workshop MCP
  (`list_hytale_registry`) rather than hardcoding a short list, so the dropdown offers
  everything the pinned build actually has.
- Support **more than one** effect per phase (idle / opening / reveal), with per-effect
  offset, interval and scale where the engine allows it. Verify what the particle API
  actually accepts before designing the model.
- Keep the existing per-player "crate effects off" opt-out and the nearby-players-only
  send, and keep the per-world/per-client particle budget from the Bible (§09).
- `PREVIEW EFFECTS` on step 6 must preview the whole stack, not one id.

## 6 · Replace the simple reward-reveal animation

**Where it lives** `src/main/resources/Common/UI/Custom/MysticEssentials/LootBoxReveal.ui`
(760×640, a scrolling list with a `#Hl` highlight the server walks down) and
`LootBoxRevealRow.ui`, driven from `modules/lootbox/ui/` + `service/OpeningService.java`.

**What to do** implement the presentation-adapter framework from Bible v2.0 §03/§05/§08/§09
rather than a one-off animation:

- A `PresentationAdapter` registry with, at minimum, **Quick Reveal**, **Horizontal Reel**
  and **Grid Roulette** (Bible §14 phase order: ship Quick Reveal and Reel *before* any
  premium cinematic). Wheel / Cards / Showcase / World / Mythic are explicitly out of
  scope for this run — leave the extension points, not stubs that half-work.
- The outcome stays **committed server-side before presentation** (`OpeningService`
  already does this — do not move the roll into the adapter). `TimelinePlanner` builds a
  cosmetic strip whose stop index equals `winningRewardId`.
- The **OddsCatalog is mandatory and can never be hidden** — numeric effective odds
  present in every phase, scrollable, with enough precision that a nonzero chance never
  renders `0.00%`. `odds/OddsFormatter.java` already exists; reuse it.
- Respect the existing `/lootbox motion [reduced|full|default]` preference
  (`service/MotionRules.java`) as the reduced-motion path, falling back to Quick Reveal.
- Honour the stepped-update budget from Bible §09 (baseline ≤ 8 diff pushes/sec per
  opening); do not stream per-frame UI updates.
- Follow the repo's UI design system (`MysticTheme.ui`, the row/selection pattern, the
  validator and audit tooling) and the sizing/colour rules: `Common.ui` fixes sizes after
  the `@Anchor` spread so override `Anchor:` directly; `TextSpans` colour overrides
  `LabelStyle`; vanilla max page is **1050×700**.

**Done when** a crate opening plays a real reel/roulette that lands on the committed
reward, with odds on screen throughout, Skip working, and no change to what the player
actually receives.

## 7 · Pity and duplicate-replacement steps in the creation workflow

**Symptom** the model supports both but the editor never asks.

**Where it lives**

- `model/LootBoxDraft.java:47-48` — `public Pity pity;` (P3-WP4)
- `model/LootBoxDraft.java:144-162` — `duplicates` (`ALLOW` / `EXCLUDE_OWNED`)
- `model/LootBoxDraft.java:187-203` — `Pity` with `hardAfter`, `softAfter`, soft-pity
  weight growth per opening
- `model/PlayerLootState.java:146` — per-player openings since the last pity target
- `ui/editor/PoolEditorPage.java:65-68` — the four tabs today:
  `General`, `Rewards`, `Keys & Rules`, `Publish`
- Page is `LootBoxEditor.ui`, `Anchor: (Width: 960, Height: 700)`

**What to do**

- Add the missing steps to the **pool** editor (pity and duplicates are pool-level, not
  crate-level): a `Pity & Duplicates` tab, or new sections under `Keys & Rules` if that
  reads better — pick one and say why in the PR body.
- Pity: on/off, target rewards, hard pity after N, soft pity after N with the per-opening
  weight increase, and a plain-language preview of what it means
  ("guaranteed by opening 90").
- Duplicates: `ALLOW` vs `EXCLUDE_OWNED`, plus the replacement reward to grant when an
  owned reward is excluded.
- Both must feed the **odds snapshot** that is shown to the player and pinned at
  confirmation (Bible §10: personalized chances resolve *before* confirmation, and the
  UI must re-confirm if they change between preview and commit). Check
  `odds/OddsEngine.java`, `odds/PlayerOdds.java` and `service/EligibilityRules.java`
  already honour `pity`/`duplicates`; wire them if they don't.
- `validate/DraftValidator.java` must reject incoherent settings (pity target not in the
  pool, hard < soft, replacement reward missing).
- **Grow the page.** 960×700 will not fit this. Vanilla's maximum is **1050×700** —
  go to 1050 wide and restructure vertically rather than exceeding it. If the content
  genuinely cannot fit, add a sub-page rather than clipping.

## 8 · `/lootbox` has no tab completion

**Cause** `modules/lootbox/command/LootBoxCommand.java` parses `String[] args` by hand and
calls `allowExtraArguments()`, so it publishes no command tree and the client has nothing
to complete. Other commands declare typed arguments instead.

**Reference** `src/main/java/org/hyzionstudios/mysticessentials/platform/command/MysticArgTypes.java`
documents the verified mechanism (checked against Server 0.6.2): per-argument
`Argument.suggest(...)` **never reaches the client**; the client sends
`ArgValuesRequest{argTypeId, partial}` and the server answers from
`CommandManager.getArgTypeById(argTypeId).suggest(...)`. Suggestions are therefore a
property of the **argument type**, and every `ArgumentType` instance gets its own id — so
share instances, one per kind of suggestion. Re-verify this against the version this repo
now targets before building on it.

**What to do** give `/lootbox` (and alias `/lb`) real completion for its subcommands and
their arguments: the subcommand literal set, lootbox/pool ids, key ids, crate ids, player
names, and the enum-ish values (`motion` → `reduced|full|default`; `reconcile` →
`regrant|dismiss`). Add reusable arg types to `MysticArgTypes` rather than one-offs.
Keep the manual dispatch in `run(...)` working — the goal is completion, not a rewrite of
the parser — and keep permission checks server-side exactly where they are.

Check the other manually-parsed commands in the repo (`/playervault` is named in
`LootBoxCommand`'s own javadoc as the pattern it copied) and note in the PR body whether
they have the same gap, without fixing them here.

**Done when** typing `/lootbox ` in game offers the subcommands, and the next argument
completes to real ids.

---

## Delivery

- Branch: `feature/lootbox-presentation-v2` off `master`.
- Commit in logical chunks, one concern per commit, each building.
- `./gradlew build` must pass. Run the Hytale validators (code refs, `.ui`, gamedata)
  over everything touched.
- Open a PR against `master` with: what landed, what did not and why, every engine fact
  that had to be verified (and the Workshop MCP evidence for it), and an explicit list of
  what still needs testing on a live server — the user tests in game, so be honest about
  what is unverified.
- Update `CHANGELOG.md`, and `wiki/` where behaviour changed (wiki links are bare
  basenames — no `.md`, no directory prefix).
