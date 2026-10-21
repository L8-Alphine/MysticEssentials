# LootBox Phase 0 — Spike Results

| | |
|---|---|
| Date | 18 Oct 2026 |
| Engine | Hytale release 0.7.2 (local dev server via `./gradlew runServer`, installed client and server both 0.7.2) |
| Harness | `src/spikes/` → `build/libs/MysticLootBoxSpikes-0.1.0.jar` (`./gradlew spikesJar`). A separate dev-only plugin, never packed into the mod jar |
| Plan | [implementation-plan.md](implementation-plan.md) §10 |

## Summary

| Spike | What's settled | Still needs the game client | Verdict so far |
|---|---|---|---|
| **S2** generated block variants | **Server side passes.** A variant registers live in about 40 ms, inherits every checked field, and its inline `Use` root interaction resolves. A variant persisted as a minimal `Parent` + inline-`Use` document loads at boot. The engine's own `writeAssetToDisk` output **fails** at boot | Swap with rotation in a loaded chunk, identical rendering, `UseBlockEvent.Pre` firing on the variant, a placed variant surviving a restart | Likely pass. Go for P2-WP6 using the RAW design (§3) |
| **S3** UI widgets | Every test document passes the build's `Common.ui` check and lints clean against the client grammar (0.6.8 corpus; the 0.7.2 `.ui` corpus isn't indexed) | Open each page and check the client log for "Markup Error" | Pending client |
| **S6** hit to select | **"Hit to select" fails as designed**: the 0.7.2 source skips `DamageBlockEvent` in common cases (§4). Creative-mode breaks fire `BreakBlockEvent` for any block, and a look-direction raycast works for any block | Confirm with `/lbspike select` and `/lbspike look` | Design change: select by raycast (§6) |
| **S7** reveal cadence | Nothing can be measured without a client | Run `/lbspike anim` at 5, 10 and 20 Hz | Pending client |
| **S5** (bonus, partial) | Damage with no instigator, such as explosions, fires a **cancellable** world-level `DamageBlockEvent` *before* damage. The final no-instigator break (`EnvironmentBreakBlockEvent`) can't be cancelled | Optional TNT and fire test with the probe on | Crate protection gets better (§6) |

**Version bump (P0-1):** with `hytale_version`/`server_version` at 0.7.2, `./gradlew build` passed, compiled against `Server-0.7.2.jar`, and all 10 `verify*` checks ran. `validateUiDocuments` passes against the 0.7.2 `Common.ui` from the local install. On the dev server, MysticEssentials booted on 0.7.2 with every enabled module up and only the expected warnings (no license file, Redis off).

---

## 1. S2 — server-side runs (five dev-server boots)

| Boot | Action | Result |
|---|---|---|
| 1 | Plain boot | Plugin `start()` runs **after** assets load: the BlockType map already holds 6,138 entries, so start-time checks see every asset |
| 2 | Autotest: live variant | **PASS.** `Rock_Stone_Cobble_Mossy_LBSpikeCrate` registered in 41 ms (index 6138). Its `Use` became the child root `*Rock_Stone_Cobble_Mossy_LBSpikeCrate_Interactions_Use` and resolves. drawType, customModel, material, opacity, hitbox, textures and gathering all match the parent |
| 2 | Autotest: generated pack | **PASS.** `mods/LootBoxSpikeGenerated/` was created with a manifest and registered at runtime with `AssetModule.registerPack(…, PackSource.MODS)`. The engine loaded assets from it straight away |
| 2 | Autotest: persisted via `writeAssetToDisk` | **FAIL**, `NoSuchFileException`: `writeAssetToDisk` writes with `Files.writeString` and **never creates folders**, so the caller has to |
| 3 | Same, folders created first | **PASS** at write time (49 ms). The engine also **loaded the generated pack from `mods/` at boot by itself** (`registered at boot=true`, writable) |
| 4 | Boot report for boot 3's file | **FAIL.** `writeAssetToDisk` writes the **fully resolved** asset: 2,802 characters, **no `Parent`**, and `Use` points at the generated child id `*…_Interactions_Use`. At boot the child doesn't exist, validation fails (`RootInteraction '*Rock_Stone_Aqua_LBSpikeCrate_Interactions_Use' doesn't exist`) and the engine **removes the asset**, so a placed block would load as unknown |
| 4 | Autotest: RAW mode | Wrote our own 107-character `{"Parent": …, "Interactions": {"Use": {inline}}}` and loaded it with `AssetStore.loadAssetsFromPaths`, the boot path. Registered, root resolves. The pack's file watcher also **hot-reloaded** both changed files by itself (about 30 ms) |
| 5 | Boot report, no autotest | **PASS.** The RAW variant `Rock_Stone_Cobble_Corner_LBSpikeCrate` (inherits via `Parent`) loaded at boot, isn't unknown, and its root exists. The ENGINE variant failed again, as expected |

## 2. S2 — static findings (0.7.2 source)

- **Writing blocks:** `BlockOperations.setBlock(chunkStore, sectionRef, x, y, z, id, blockType, rotationIndex, filler, settings)`, the same path prefab pastes use. Read the current state with `BlockSection.get / getRotationIndex / getFiller(x, y, z)` from `chunkStore.getChunkSectionReferenceAtBlock(…)`. Run it on the world thread.
- **Chunk saves store block keys**, not indexes. `BlockSection#deserialize` maps each key through `BlockType.getBlockIdOrUnknown`. A variant whose asset is missing at load becomes an "unknown" placeholder **that keeps its key**, so the save isn't damaged and the block resolves once the asset is back.
- **External directory packs** in `mods/` load at boot when `DefaultModsEnabled` (or the mod config) allows it. They're writable unless they contain `CommonAssetsIndex.hashes`.

## 3. Production design for generated variants (feeds P2-WP6)

1. Keep one generated directory pack, `mods/MysticEssentialsGenerated/`, with a fixed manifest. If it isn't registered (first run), register it at runtime with `registerPack`.
2. For each variant, write **our own minimal document**, `{"Parent": "<original>", "Interactions": {"Use": {"Interactions": [{"Type": "MysticCrate"}]}}}`, to `Server/Item/Block/Blocks/MysticCrate/<key>.json`, creating the folders. **Never use `writeAssetToDisk`** for assets with inline child assets.
3. Make it live with `AssetStore.loadAssetsFromPaths(packName, [file])`, which decodes exactly the way boot does.
4. Swap the block with `BlockOperations.setBlock`, passing the original rotation and filler. Record the original key so the swap can be undone.
5. At enable, check every recorded variant key resolves and isn't unknown, and that the generated pack registered at boot. If `DefaultModsEnabled` is off, tell the operator to enable the generated pack.
6. Because the document uses `Parent`, a variant follows later changes to its original block. If the original disappears, the variant fails validation and the crate shows up in the repair wizard.

The same RAW approach should work for **native custom key items (S1)** with the `Item` store. That still needs a client check.

## 4. S6 — static findings (0.7.2 source)

`BlockHarvestUtils#damageSingleBlock` returns **before** building `DamageBlockEvent` when:
- the environment forbids changing blocks;
- the block has **no `Gathering`** config;
- the tool doesn't match and `matchTool` is set;
- the held item has **no power against the block** and the block isn't soft: a bare hand or a **weapon** on stone, or any non-tool item on a hard block.

Damage with no instigator calls `entityStore.invoke(event)` (world level), so it never reaches an `EntityEventSystem`. In **creative mode**, `BreakBlockInteraction` calls `performBlockBreak` straight away, which fires the cancellable `BreakBlockEvent` for any block the world lets you break. `TargetUtil.getTargetBlock(ref, maxDistance, accessor)` raycasts along the player's look direction and works on every block.

## 5. S3 — what's ready

There are eight single-widget documents (`numberfield`, `progressbar`, `tabs`, `tabstext`, `itemgrid`, `reorderable`, `itempreview`, `colorpicker`), each isolated so a rejected widget only aborts its own page. After mounting, the server writes a value into each one (`#Num.Value`, `#Bar.Value`, `#Grid.ItemStacks` from the held item, `#Preview.ItemId`) to prove it can drive the widget, and value changes are logged from `handleDataEvent`. I removed four optional properties (`DisplayItemQuantity`, `DropIndicatorBackground`, `DropIndicatorAnchor`, a `Background` on `ReorderableListGrip`) because they've never been seen in real client files.

## 6. Plan changes these results drive

- **§4.6 selection:** the primary method is `/lootbox crate select` raycasting the block you look at (`TargetUtil`). Creative-mode break capture is a convenience. "Hit to select" in survival is dropped.
- **§4.6 protection:** add a world-level `DamageBlockEvent` system that cancels damage to crate blocks, which blocks unattributed explosion damage before it lands. The player-level `DamageBlockEvent` and `BreakBlockEvent` systems stay. `EnvironmentBreakBlockEvent` remains disable-and-alert.
- **§4.6 generated variants / P2-WP6:** the RAW design above. Risk drops from Medium to Low on the server side.
- **§10:** S2 is server-side PASS; S6 is resolved by the raycast change.

---

## 7. In-game checklist (needs your 0.7.2 client)

The dev server is ready: `run/mods/` has the spike jar and the generated pack, with one RAW variant definition. Use a **development server only**.

**Setup**
- Start the server:

  ```bash
  ./gradlew runServer -Phytale_home="$APPDATA/Hytale/install/release/package/game/latest"
  ```

- If your client can't join (the log shows "No server tokens configured"), run `/auth login` in the server console.
- Join at `localhost`. Make yourself operator, or grant `mysticlootboxspikes.use`.
- Results go to `run/mods/org.hyzionstudios_MysticLootBoxSpikes/spike-results.log`. Tell me when you're done and I'll read it.

**S6 — selection (about 5 min)**
1. `/lbspike look` at stone, then a chest, then a flower. Expect a raycast line for each.
2. `/lbspike select`, then hit **stone bare-handed**. Expected: **no capture** (the source predicts no event).
3. Hit stone **with a pickaxe**. Expected: captured via `DamageBlockEvent`.
4. Hit stone **with a sword**. Expected: no capture.
5. Hit a **flower**. Expected: captured (soft block).
6. Switch to `/gamemode creative` and hit anything. Expected: captured via `BreakBlockEvent`.

**S2 — generated variant (about 10 min)**

7. Look at a plain stone or plank block and run `/lbspike s2 variant persist`. Expected: "Swapped …", and the block **looks exactly the same** (check that yourself). Repeat on a **rotated log** to check rotation.
8. Press **Use (F)** on it. Expected: chat says "Pre fired for the variant and was cancelled".
9. `/lbspike s2 letrun on`, then Use again. Expected: "Variant Use interaction ran". Then `/lbspike s2 letrun off`.
10. Restart the server, rejoin, go to the block and run `/lbspike s2 verify`. Expected: `PASS verify` (key, rotation, not unknown).
11. `/lbspike s2 restore`. Expected: the original block is back.

**S3 — widgets (about 10 min)**

12. Hold any item, then run `/lbspike ui <name>` once for each of `numberfield progressbar tabs tabstext itemgrid reorderable itempreview colorpicker`.
13. For each: did the page open, does the widget show, does it respond (type a number, switch tabs, drag a row, pick a colour)?
14. Afterwards, search `%APPDATA%/Hytale/UserData/Logs/*_client.log` for "Markup Error". If a page doesn't open, check the next one still does (the documents are isolated).

**S7 — cadence (about 3 min)**

15. Run `/lbspike anim 5 4`, then `/lbspike anim 10 4`, then `/lbspike anim 20 4`. For each, note smoothness, flicker, and whether the odds column stays stable. The server logs the worst gap between frames.

**S5 — optional**

16. With the probe on (the default), set off TNT or start a fire next to a block. Watch for `S5` lines.

**Cleanup:** when you're done, delete `run/mods/MysticLootBoxSpikes-0.1.0.jar` and `run/mods/LootBoxSpikeGenerated/`. `run/` is gitignored.

## 8. Dev-server notes

- `prepareRunServer` makes `run/mods/org_hyzionstudios_MysticEssentials` a **junction to `src/main/resources`** (a live asset link). Nothing may write into that folder. MysticEssentials' own data goes to `run/mods/MysticEssentials/`, so it's safe today.
- Stopping `runServer` on Windows leaves the **server JVM running**. Find it by its `build\classes\java\main` classpath and stop it before the next boot.
- Plugin data folders are named `<Group>_<Name>` with a dot inside the group (`org.hyzionstudios_MysticLootBoxSpikes`).
