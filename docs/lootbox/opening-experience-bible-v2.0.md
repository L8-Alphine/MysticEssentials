
MYSTIC ECOSYSTEM  /  MYSTICESSENTIALS
LOOTBOX
OPENING EXPERIENCE
UPGRADE BIBLE
NATIVE HYTALE UI ANIMATIONS  +  WORLD CINEMATICS
DESIGN INTENT
One unified, reliable animation framework: CrazyCrates-inspired reel, wheel, roulette, card, and quick-reveal UI modes for everyday crates, plus Hytale camera-driven Mythic cinematics. All integrated into MysticEssentials, with immutable odds and server-authoritative outcomes.

RELEASE
DOCUMENT TYPE
RELATIONSHIP
v2.0  •  23 Oct 2026
Design upgrade / implementation specification
Addendum to LootBox Master Design Bible v1.0

Status: Proposed architecture. Feature names, JSON contracts, UI layouts and timing targets are design specifications, not assertions that every capability is implemented in the current Hytale build.

01  Document Control & Executive Decisions
This upgrade amends the opening-presentation portion of the existing MysticEssentials LootBox Master Design Bible v1.0. All established requirements for keys, universal crates, rewards, probabilities, policy profiles, database integrity and integrations remain in force unless this upgrade explicitly tightens them.
Non-negotiable decisions
DECISION
ENGINEERING CONSEQUENCE
Same mod
All opening experiences ship as a toggleable submodule within MysticEssentials. No new standalone jar or required client mod.
Many opening modes
UI animations and world/camera cinematics share one secure opening session and result model.
Style ≠ rarity
An administrator can assign any compatible animation preset to any LootBox. Mythic is a signature presentation choice, not a hard lock.
Always-visible odds
Complete eligible reward odds stay present and accessible as readable, numerical values in preview, confirmation, animation and result.
Server-first reward
The outcome is selected and durably recorded before presentation; skip/lag/reconnect cannot reroll.
Graceful fallbacks
Unsupported UI effects or blocked cameras must resolve to a readable, reliable reveal.
All Ages safety
Free gameplay reward model remains the default; paid randomized rewards are blocked by policy profile.

Document map
02 Scope & terminology • 03 Experience library • 04 Player journeys • 05 Interaction/UI layouts • 06 Admin studio • 07 Mythic cinematic • 08 Runtime architecture • 09 Timing/render strategy • 10 Probabilities • 11 Recovery/security • 12 Hytale implementation • 13 Configuration/API • 14 Testing & rollout • 15 Developer checklist & references.
DESIGN PHILOSOPHY
Use familiar Minecraft crate mechanics as interaction inspiration, but create original, Hytale-native layouts, animations, audio and assets. Do not reproduce third-party UI textures, proprietary code or branding.

02  Scope, Boundaries & Terms
Inside this upgrade
Custom Hytale reward UI modes: Quick Reveal, Horizontal Reel, Prize Wheel, Grid Roulette, Card Shuffle/Reveal and Showpiece Panel.
In-world modes: Block Activation / World Reveal and Mythic camera-driven cinematic.
In-game Animation Studio, reusable presets, per-crate selection and preview/testing flows.
Universal animation session protocol, precise odds overlay, reduced-motion and fallback modes.
API, storage extensions, performance targets, validation, testing and migration strategy.
Not redesigned here
The underlying lootbox/key editor, key item metadata and true custom asset ID strategy, world-block crate binding, economy reward contract, pool probabilities and published revision model stay governed by the Master Design Bible v1.0. The animation system consumes their published results and never reimplements their authority.
Key concepts
TERM
DEFINITION
LootBox Definition
Published reward pool, key/access rules and immutable reward revision.
Crate Instance
World-bound block or portable item associated with a LootBox.
Opening Profile
Selected presentation mode plus timing, theme, sound, accessibility and fallback options.
Opening Session
Single durable, server-authoritative transaction / reward result with ephemeral presentation state.
Visual Timeline
Presentation-only deterministic sequence built around a committed outcome.
Odds Snapshot
Exact effective reward probabilities, revision hash and modifiers used at commitment.

03  Opening Experience Library
Modes are independent of lootbox rarity. An administrator may select a simple reel for a Mythic crate, or a camera-based cinematic for an event crate, subject to compatibility and player settings. All modes render the same outcome and odds snapshot.
MODE / ID
PLAYER EXPERIENCE
DEFAULT RANGE
FALLBACK
Quick  •  QUICK_REVEAL
Short pulse, item pop, rarity frame and receipt.
0.5–2 s
Static reward card
Reel  •  HORIZONTAL_REEL
Items travel across a horizontal track beneath a fixed pointer, ease to the committed reward.
3–7 s
Stepped carousel
Wheel  •  PRIZE_WHEEL
Circular prize wheel turns beneath fixed pointer with smooth/stepped deceleration.
4–8 s
Segment list + reveal
Roulette  •  GRID_ROULETTE
Highlight navigates a matrix of reward icons and ends on committed result.
3–7 s
Step highlight + reveal
Cards  •  CARD_REVEAL
Cards shuffle/flip; visual reveal shows committed result, not a hidden player choice reroll.
3–7 s
Static card reveal
Showpiece  •  ITEM_SHOWCASE
Large item panel, rarity treatment, quantity, source and roll receipt.
2–5 s
Static reward card
World  •  WORLD_REVEAL
Placed block glows; particles/audio cue the reward appearance.
3–6 s
UI showcase
Mythic  •  MYTHIC_CINEMATIC
Camera keyframes, altar energy, starfield/runes and floating 3D reward sequence.
6–12 s
World reveal → UI

Ranges are configurable design defaults and must be validated on representative clients and network conditions.
Recommended profile examples
CRATE CATEGORY
DEFAULT MODE
AUDIO/VFX DIRECTION
Common
Quick Reveal / Reel
Warm click, subtle glow, minimal camera
Uncommon
Reel / Roulette
Short tick sequence, small burst
Rare
Reel / Wheel
Clear deceleration, richer colors, reward flare
Legendary
Wheel / Showpiece
Layered cue, special item frame, low camera movement
Mythic
Mythic Cinematic
Astral Convergence signature; manual skip always available

Visual honesty
Items displayed in a reel or wheel are illustrative eligible outcomes, not a literal live RNG process. The UI must not imply it is selecting rewards during spin; the result is already committed. Avoid exploitative fake near-misses, false win states or decoy paid value representations. A displayed reward never differs from its stored reward definition.
04  Player Journeys & Interaction Rules
Standard path (menu-based)
01   Player interacts with crate or portable lootbox; server opens the Preview page.
02   Preview lists all eligible rewards and exact effective numerical odds with restrictions, key requirement, duplicate/pity rules and animation preference.
03   Player selects Open; server revalidates revision, key, access rules and matching odds snapshot.
04   Server reserves/consumes the key, draws the reward and commits the result and fulfillment outbox.
05   Presentation adapter starts the chosen UI animation with a fixed winning reward ID and odds snapshot; it never executes the roll.
06   Player sees result card; reward delivery status reads Delivered or Pending Recovery. Closing/skip/reconnect never changes reward.
In-world / Mythic path
01   Preview and explicit confirmation behave exactly as the standard path.
02   Check world/camera suitability, player accessibility preferences and server FX budget.
03   Create client-scoped cosmetic effects; start camera sequence only after successfully snapshotting/restoring camera state.
04   Keep the odds overlay readable above the scene during every phase; allow immediate Skip / Reduced Motion.
05   Finalize with item showcase and receipt; cancel all cosmetic entities and restore controls/camera on every exit path.
Skip, multi-open and fast-open
Skip ends only presentation; it never cancels an already committed opening or restores a spent key.
Quick-open batch mode may summarize multiple independent committed rolls but must show per-roll results and the underlying odds policy clearly.
UI should prevent repeated keypresses from submitting multiple openings accidentally; server request deduplication remains authoritative.
Player preference for reduced motion and no camera override outranks administrator preset settings.
05  Hytale UI Layout System
Shared UI composition
Each Hytale Custom UI opening page shares a theme shell: title and crate name; mode-specific animation canvas; always-visible numerical reward/odds catalog; result/receipt region; Skip and Close; status and key balance. The odds catalog has a stable independent container that cannot be hidden by scene transition or animation masks.
Wireframe A — Horizontal Reel
┌──────────────────────────────────────────────────────────────┐
│ AVALON ROYAL CRATE                        SKIP  |  CLOSE      │
│                                                              │
│             ▼ CENTER WINNER MARKER                           │
│  [SWORD] [GOLD] [TOKEN] [SHIELD] [COMPANION] [GEM] → →       │
│             ▲ fixed target window                            │
│  EASING...   /  No secondary RNG                              │
├──────────────────────────────────────────────────────────────┤
│ REWARD ODDS (scroll if needed, never dismissed)              │
│ Iron Sword 45.00%  |  Gold 30.00%  |  Token 15.00% ...        │
├──────────────────────────────────────────────────────────────┤
│ KEY USED: 1 AVALON ROYAL KEY  |  OPENING ID: ...              │
└──────────────────────────────────────────────────────────────┘

Wireframe B — Prize Wheel / Roulette
┌──────────────────────────────────────────────────────────────┐
│ AVALON EVENT CRATE                   [Skip] [Reduced motion]  │
│ ┌────────────────────────────────┐ ┌──────────────────────┐ │
│ │        PRIZE WHEEL             │ │  FULL REWARD ODDS    │ │
│ │       /--pointer--\           │ │  Sword      45.00%   │ │
│ │    [sections + icons]          │ │  Gold       30.00%   │ │
│ │        rotating                │ │  Token      15.00%   │ │
│ │                                │ │  ... [scroll]         │ │
│ └────────────────────────────────┘ └──────────────────────┘ │
│ WINNER REVEAL: [ICON] ITEM NAME × QUANTITY                    │
└──────────────────────────────────────────────────────────────┘

Wireframe C — Mythic world overlay
[WORLD CAMERA: swirling runes / altar / 3D relic]
┌────────────────────── Mythic Crate ──────────────────────────┐
│ Reward odds: full visible scrollable list, pinned            │
│ ITEM A 45.00% | ITEM B 30.00% | ITEM C 15.00% | ...           │
│                                                              │
│ [SKIP] [REDUCED MOTION]            STEP 03/06: SUMMONING     │
└──────────────────────────────────────────────────────────────┘
                  ↑ HUD/overlay cannot be faded out

UI specifications
Use a reusable OddsCatalog component across all modes; pre-render effective percentages with enough precision that nonzero probability never renders as 0%.
Animated frame borders, glow textures and item portraits should be reusable theme tokens. Use a fallback placeholder for unsupported 3D previews.
Controller and keyboard focus order: Close/Skip → Odds Catalog → optional controls. Do not trap focus during animation.
Do not depend on per-frame packets for moving UI components; support client-capable transitions if officially available and stepped server update fallback.
Screen-size behavior: preserve odds first; reduce animation canvas before squeezing odds below legible sizes.
Persist selected reward accessibility and details into a final receipt even if the opening page closes unexpectedly.
06  Animation Studio — In-Game Administrative Workflow
Add “Opening Experience” as a tab in the LootBox Editor. Administrators can pick a preset, preview safely, and publish validated revisions without editing JSON. Advanced design supports reusable global presets and per-lootbox overrides.
Main editor screen / inspector
SECTION
EDITOR CONTROLS
Preset library
Browse Built-in, Custom, Drafts; search; clone; test; select; restore defaults.
Basic
Mode, title, description, theme, category, orientation and profile assignment.
Timing
Duration, pre-roll delay, easing/deceleration, pause, reveal hold and skip policy.
Item rendering
Icon size, row/grid dimensions, highlight, award frame, rarity badge and quantity display.
FX & audio
Per-phase sounds, pitch/volume clamps, particles, glow, background, rarity override.
Camera / World
Safe orbit radius, FOV, keyframe preset, collision fallback, world effect anchor.
Accessibility
Reduced-motion substitute, no flashing, no forced camera, contrast and audio mute.
Odds & disclosure
Preview mandatory OddsCatalog and edge-case personalized odds; cannot disable.
Preview / QA
Mock reward mode, seeded demonstration, slow-motion preview, packet/update counter and publish validation.

Editor journey
01   Open MysticEssentials → LootBox → Manage → Opening Experience.
02   Choose a built-in mode or a saved template; review supported features and fallback behavior.
03   Customize timing, motion, item layout, sounds, reward frame, and theme. Preview a *demo* roll, labeled as not granting an actual prize.
04   Run validation against reward pool size, odds list fit, animation duration, supported assets, active API capabilities and accessibility.
05   Save as Draft / Publish as immutable preset version; assign to one or multiple LootBoxes.
06   Server begins serving new preset for new openings only. In-progress sessions continue against their committed preset version.
Tier-specific override versus fixed outcome
An animation may change final-frame particles and sound based on the already-selected reward tier. It must not load another reward pool or change its odds after the player confirms. Restrict high-flash or loud cues for accessibility.
07  Signature Mythic Cinematic — Astral Convergence
Astral Convergence should be the premium signature preset, drawing visual inspiration from the uploaded celestial starfield/relic-reveal clip: orbiting light, dark void, a suspended item and layered sound. The reference clip is aesthetic guidance, not evidence of a Hytale implementation or licensed assets.
Storyboard & camera beats
PHASE / TIME
CAMERA
WORLD FX / AUDIO
UI CONTRACT
01  Trigger 0–1s
Hold player camera; ease toward altar.
Key glow, first bell/low hum.
Odds panel pinned; Skip active.
02  Ritual 1–3s
Smooth arc/orbit with collision-safe bounds.
Sigils, pulsing rings, rising shards.
Odds remain readable.
03  Ascend 3–5s
Rise/tilt toward celestial axis.
Light beam, starfield and bass lift.
No full-screen opacity masks over odds.
04  Manifest 5–7s
Slow dolly toward reward center.
3D relic appears; rarity effect by committed result.
Exact won-item odds visible.
05  Reveal 7–9s
Hold focal plane on item.
Item name, quantity, rarity cue, chime.
Receipt: result + delivery status.
06  Return 9–10s
Blend to saved camera, restore HUD.
Particles decay, cleanup FX.
Odds & history remain accessible.

Cinematic safety constraints
All temporary scene objects are cosmetic, client-scoped where practical, and never modify shared placed blocks or player inventories.
Use Hytale server camera sequence capability when supported on the pinned server build; validate keyframe API and restoration against the exact version.
No forced teleport, player damage, movement-lock or destructive world edits are needed for a cinematic.
If camera sequence fails, frame is obstructed, a client opts out, or world is unloading: fall back to World Reveal or Item Showcase.
Persistent Skip must close camera and effects immediately without altering committed reward.
Never close or conceal the probability catalog. In a high-contrast starfield scene the catalog becomes opaque, not hidden.
Other special variants
VARIANT
ART DIRECTION
CAMERA CHARACTER
Arcane Convergence
Blue-purple runes, sigils, magical shards
Short orbital sweep
Divine Relic
Gold shafts, stone light, choir-like chord
Measured upward reveal
Void Emergence
Restrained dark energy and silhouette
Quick draw-in with reduced flashing option
Royal Treasury
Locks, doors, gold fragments, mechanical chime
Slow entrance/treasure focal point

08  Runtime Architecture & Opening Protocol
MysticEssentials  [single mod]
└── Modules/LootBox
    ├── Core: pools / keys / crates / policy / rewards [unchanged]
    ├── OpeningExperience
    │   ├── OpeningProfileRegistry      (immutable preset revisions)
    │   ├── ExperienceOrchestrator       (session lifecycle)
    │   ├── PresentationAdapterRegistry (UI / World / Camera)
    │   ├── TimelinePlanner             (deterministic reveal)
    │   ├── UIPageController            (.ui binding / input)
    │   ├── WorldEffectController       (particles / temporary visuals)
    │   ├── CameraSequenceController    (snapshot / play / restore)
    │   ├── OddsOverlayController       (mandatory catalog)
    │   ├── AccessibilityService        (preference + fallback)
    │   └── RecoveryCoordinator         (cleanup / resume)
    └── Audit + Metrics + Integrations  [existing + extension]

Authority split
SERVER AUTHORITY
PRESENTATION ONLY
Key eligibility, reward pool, personalized odds and immutable revision
Which animation, decorative track layout and colors to show
Atomic key debit and server CSPRNG roll
Easing, rotation, particle timing and camera movement
Opening transaction, reward commands and fulfillment outbox
Skip / pause / reduced motion / display mode
Deduplication, audit ledger, replay and reconciliation
Result visuals loaded from the committed reward ID

Opening state machine
NEW → PREVIEWED → CONFIRMING → COMMITTED → PRESENTING
              ↘ CANCELLED      │           │
                                │           ├─ SKIPPED → RECEIPT
                                │           ├─ INTERRUPTED → RECEIPT/RESUME
                                │           └─ COMPLETED → RECEIPT
                                └─ FULFILLMENT_PENDING → DELIVERED
                                    (fulfillment is independent of animation)

A successful COMMITTED state is irreversible from the player presentation. Fulfillment begins from a durable outbox independently; DISPLAY states do not gate award delivery. FAILED_RECOVERY represents presentation cleanup trouble or unresolved fulfillment, not a license to reroll.
OpeningSession fields
FIELD
CONTRACT
openingId / requestId
Stable UUIDs; requestId deduplicates repeats from the same player.
playerId / crateId
Trusted identities and world/instance key.
lootboxRevision / profileRevision
Immutable versions pinned at confirmation.
oddsSnapshotHash
Hash over numerical chances, modifiers, eligibility and pool revision.
winningRewardId / fulfilledActions
Immutable committed outcome, typed grant status and idempotency keys.
presentationMode / checkpoint
Recoverable visual mode + last acknowledged stage; never reroll.
cameraRestoreToken
Ephemeral cleanup handle; invalidated on disconnect and world change.

09  Animation Timing and Rendering Strategy
A smooth reel is a visual problem, not a network packet race. Standard Hytale Custom UI is server-driven; the server must not attempt to stream dozens of high-frequency UI updates each second without measuring network and client cost. This is especially important for 100 ms+ RTT players.
Three rendering strategies
STRATEGY
WHEN USED
CONSTRAINT
Native client-side UI transitions
Only if supported and stable in pinned build for the relevant properties.
Never assume unverified animation markup or proprietary API.
Stepped UI rendering
Legacy Custom UI fallback for reels / roulette / cards.
Update only at tested interval, send diffs, preserve winner and odds.
Camera/world sequence
Mythic and World modes leveraging verified server camera & FX hooks.
Collision, interruption, cleanup and opt-out required.

Deterministic strip planning
After commitment, TimelinePlanner builds a cosmetic reel around the selected result: deterministic visible icons, a stop index, reveal keyframe, duration and easing curve. All entries must be legitimate possible rewards or clearly decorative placeholders. Do not infer drop rates from reel spacing: the OddsCatalog is the sole authoritative probability display. A displayed stopping slot must match winningRewardId exactly.
Performance budgets (engineering targets; measure in QA)
TARGET
PROPOSED THRESHOLD / METHOD
Functional UI updates
Cap mode-specific diff pushes using load tests; baseline target ≤ 8/s per active opening.
Concurrent opens
Soak-test 25 / 100 / 250 viewers; each session independent and bounded.
Low-bandwidth behavior
Adaptive stepping on high RTT and packet backlog, never change committed result.
World VFX
Particle budgets per client and per-world cap; degrade effects before game TPS.
Camera cleanup
Restore camera/HUD and clear effects on all exit transitions and world unload.
Monitoring
Capture p50/p95 session latency, packet diff count, timeouts, and delivery backlog.

10  Odds Disclosure and Ethical Presentation
HARD INVARIANT
Before, throughout, and after every opening, show numerical effective chances for all obtainable rewards. The OddsCatalog remains present, readable and immediately scrollable; no separate hidden menu is required to access chance information. If layout cannot fit, reduce the animation first.

Personalized chances
Pity counters, duplicates, player eligibility or modifiers must be resolved before confirmation. OpeningSession stores exact effective odds for that player. UI previews require revalidation; if probabilities change between preview and commit, force a refreshed confirmation. The cinematic must use that stored snapshot, not a later live pool.
Rendering rules
Use up to required decimal precision so a positive nonzero chance does not appear as 0.00%.
Reward pool catalog supports scroll and pagination while maintaining visible numeric labels on each entry.
Display single-outcome versus independent bonus roll semantics explicitly; do not sum unrelated chances as if exclusive.
At reveal show selected reward name, quantity, actual effective probability and delivery status; also provide a receipt/history view.
Color and rarity labels are supplements only; never substitutes for numerical probabilities.
Do not create urgency, deceptive near misses, hidden transitions to premium offers, or misleading countdowns.
Policy profiles
FREE_GAMEPLAY_ONLY remains the default and required profile for All Ages Hyzion deployments. Paid Random Items are prohibited on All Ages Hytale servers. For any legitimately eligible Teen/Mature implementation, monetization controls, accurate odds, expected-cost disclosures, history and current policy review are mandatory, but not an intended v2.0 shipping feature. See official Hytale policy source in References.
11  Reliability, Recovery, Security and Accessibility
Failure / interruption matrix
EVENT
REQUIRED BEHAVIOR
Double-click, duplicate packet
Deduplicate by requestId; one key debit, one roll, one receipt.
Close / skip during animation
Stop visuals; show committed receipt; delivery continues.
Disconnect / reconnect
Restore latest committed result and delivery status; do not spend again.
Server restart after commit
Replay outbox idempotently; show receipt on reconnect.
Camera path blocked / entity removed
Switch to World or UI Showcase; cleanup all temporary resources.
Client high latency
Use stepped animation / shortened presentation; never reroll.
Inventory full
Pending reward inbox (per base Bible), not ground-drop loss.
Command reward times out
Mark ambiguous and reconcile; never blindly retry non-idempotent external command.
Pool published mid-opening
Session keeps committed lootbox & profile revision.
Two players at same crate
Separate private camera/UI presentation sessions and reward transactions.

Player preferences & content comfort
Skip animations, Quick Reveal preference, disable camera takeover, reduced motion, reduced flashes, mute cinematic audio and low particle settings.
Settings stored per player, optionally synchronized through MysticIdentity if available; disabled integrations must not break opening.
A confirm page remains keyboard/controller navigable with visible focus and escape-to-close.
World camera sequence does not override critical accessibility settings or hide odds under vignette effects.
Provide staff/admin debug controls for forced cleanup of a stuck presentation without touching award records.
Anti-exploit and cleanup requirements
Presentation requests authenticate trusted player context, ensure session ownership, and ignore client-declared winning items or probabilities. The camera and effect controllers must use scoped resources registered to openingId; session cleanup is idempotent, has timeouts, and runs on world unload, logout, cancel/skip, successful completion and plugin disable.
12  Hytale Build Compatibility and UI Contracts
Target version must be pinned by the implementation team. Public Hytale docs currently show a 0.7.x release track, but not all UI animation properties are equally available. The legacy Custom UI layout/.ui plus Java event handler pattern is the production baseline until an officially supported replacement is verified. Experimental dev_-prefixed Noesis server APIs are not a basis for launch.
Capability gates
CAPABILITY
STATUS / REQUIRED VALIDATION
Custom .ui page & events
Known pattern; validate exact syntax, UI assets, input and dynamic patch behavior in target build.
Smooth client UI reel movement
Must benchmark/verify; stepped server-driven update is mandatory fallback.
Wheel rotation / clipping / masks
Verify support; provide segmented highlight mode if unsupported.
Camera sequence keyframes
Hytale Update 6 docs discuss CameraSequenceBuilder and depth-of-field; confirm signatures in installed jars.
3D item presentation
Prefer supported cosmetic entity / visual representation; validate spawn, rotation, view scope and cleanup.
Controller + multiple screen sizes
Test complete focus and sizing; no assumption that mouse hover is available.
Custom item texture/model
Use currently registered asset and icon handling; do not copy arbitrary third-party assets.

Proposed UI resource layout (logical only)
MysticEssentials/Resources/Common/UI/Custom/LootBox/
  Opening/
    QuickReveal.ui       HorizontalReel.ui    PrizeWheel.ui
    GridRoulette.ui      CardReveal.ui        ItemShowcase.ui
    WorldOverlay.ui      MythicOverlay.ui     OpeningReceipt.ui
  Shared/
    OddsCatalog.ui       RewardSlot.ui        ItemFrame.ui
    RarityTheme.ui       ProgressIndicator.ui
  Admin/
    AnimationLibrary.ui  OpeningProfileEditor.ui
    AnimationPreview.ui  OpeningDiagnostics.ui
  Themes/
    Standard/            Legendary/          Mythic/

Actual packaged paths, layout syntax and event bindings follow the existing MysticEssentials build conventions and validated Hytale UI framework; filenames are proposed.
13  Configuration, Extension API and Events
Illustrative profile JSON (proposed MysticEssentials schema)
{
  "schemaVersion": 2,
  "profileId": "avalon_royal_reel",
  "revision": 4,
  "mode": "HORIZONTAL_REEL",
  "themeId": "avalon_royal",
  "durationMs": 5200,
  "reel": { "visibleSlots": 7, "easing": "EASE_OUT_CUBIC" },
  "overlay": { "oddsCatalogEnabled": true, "hideOddsAllowed": false },
  "options": { "allowSkip": true, "showReceipt": true },
  "fallback": ["STEP_REEL", "ITEM_SHOWCASE", "QUICK_REVEAL"],
  "accessibility": { "reducedMotionMode": "QUICK_REVEAL" }
}

Mythic profile example (proposed schema)
{
  "profileId": "mythic_astral_convergence",
  "mode": "MYTHIC_CINEMATIC",
  "cameraPreset": "ASTRAL_ORBIT_06",
  "durationMs": 10000,
  "worldEffects": ["RUNIC_CIRCLE", "STELLAR_BEAM", "RELIC_MANIFEST"],
  "clientScoped": true,
  "fallback": ["WORLD_REVEAL", "ITEM_SHOWCASE"],
  "overlay": { "oddsCatalogEnabled": true, "hideOddsAllowed": false },
  "options": { "allowSkip": true, "restoreCamera": true }
}

API contracts (pseudocode, not verified Hytale classes)
interface OpeningExperienceService {
  PreviewData preview(PlayerId player, LootBoxId lootbox);
  CommitResult confirm(PlayerId player, String requestId, String oddsHash);
  void present(OpeningId id, OpeningProfileRevision profile);
  void skip(OpeningId id, PlayerId requester);
  OpeningReceipt receipt(OpeningId id, PlayerId requester);
}
interface PresentationAdapter {
  boolean supports(ClientCapabilities caps, WorldContext world);
  void start(CommittedOpening opening, DisplayContext context);
  void cleanup(OpeningId id);
}

Extension events
EVENT
DESCRIPTION
OpeningCommitted
Emitted after durable reward commit; carries winningRewardId and odds hash.
PresentationStarted
Specific mode and profile revision; telemetry only.
PresentationSkipped
Player-initiated skip; cleanup trigger.
PresentationFallback
Reason code, requested/actual modes; no reward mutation.
OpeningReceiptViewed
History / UI audit event.
PresentationCleanupFailed
Escalate to Watchdog / admin diagnostics; repair job.

Mystic integration touchpoints
MysticIdentity optionally stores player accessibility preferences and policy gating; MysticQuests/Dungeons may select opening profiles as part of a reward experience; MysticRPG supplies rarity labels/themes; MysticModeration records admin changes; MysticWatchdog may monitor stuck UI/camera cleanup and update load; MysticGate can synchronize profile revisions and opening receipts only after secure cross-server ownership is verified. All integrations are optional.
14  Test Strategy, Acceptance Criteria & Rollout
Core end-to-end acceptance matrix
ID
TEST / REQUIRED RESULT
UI-01
Each of six UI modes opens and stops on the precommitted reward; exact odds visible throughout.
UI-02
Use reward pools 1 / 10 / 100 items; full odds immediately scrollable without clipping.
UI-03
Wide, 16:9, narrow and scaled UI; input via mouse, keyboard and supported controller mappings.
CIN-01
Mythic camera follows keyframe storyboard and restores on completion / skip / disconnect.
CIN-02
Blocked or unsupported camera safely falls back; no permanent HUD/camera changes.
ODDS-01
Nonzero rare probabilities render nonzero; pity/duplicate changes reflected in snapshot.
TX-01
Repeated confirm or skip generates one roll, one key debit, one fulfillment only.
TX-02
Crash/restart just after commit recovers receipt and delivery without reroll.
LOAD-01
Parallel sessions do not cross-contaminate item results or world FX.
POL-01
All Ages FREE_GAMEPLAY_ONLY policy blocks prohibited paid randomized paths.
ADM-01
Admin can create/edit/preview/publish/version/restore presets entirely in game.
A11Y-01
Reduced-motion + camera-opt-out play readable reward and odds presentation.

Benchmark matrix
SCENARIO
VERIFY / LOG
Low RTT + high FPS
Animation readability, input state, smooth deceleration.
100–250ms RTT / packet loss
Stepped fallback, server update volume, unchanged result.
50+ nearby players, concurrent opens
TPS, memory, packet pressure, particle budget, camera isolation.
Frequent restarts/world unload
No stranded camera, invisible UI, lost reward or duplicate key debit.
Weak graphics / low effects
Crate stays usable; instant or short mode preferred.
Mod conflict + bad asset
Per-mode capability fallback, diagnostic log, safe failure.

Recommended delivery phases
01   Foundation: OpeningSession model, OddsCatalog, profile registry, capability flags, receipt UI and Quick Reveal.
02   Native UI library: Horizontal Reel + Grid Roulette, then Wheel, Cards and Item Showcase after target-build measurement.
03   World layer: Block Activation and World Reveal with client-scoped FX and cleanup.
04   Mythic cinematic: Astral Convergence keyframes, soundtrack, camera guardrails and accessibility.
05   Animation Studio: in-game profile editor, dry-run, templates, publishing / revisions and rollback.
06   Soak & compatibility: network RTT, concurrency, restart recovery, content policy audit, beta telemetry and staged enablement.
Priority & scope control
Ship a stable Quick Reveal and Reel before spending time on premium cinematic content. The My­thic sequence is the showcase, but reliability and disclosure across every mode are release blockers. Avoid dependency on experimental UI capabilities. Keep existing lootbox pool, key, crate and core transaction logic intact; replace only the presentation interface with the adapter framework.
15  Definition of Done, Decisions & Sources
Definition of done
All opening modes are selectable in-game, validated, tested and isolated inside the MysticEssentials LootBox module.
Odds list is numerical, accurate and available without dismissal during every animation phase.
No reward, key, command or inventory change originates from a presentation-only update.
Every user action has a recoverable opening ID and unambiguous receipt/delivery status.
Skip/reduced-motion/fallback paths preserve camera/player control, original result and accessibility.
No remaining camera locks, effects, or pending promise/state after disconnect, world change, plugin disable or restart.
Compatibility evidence is recorded against exact target Hytale version and pinned MysticEssentials APIs.
Decisions / validation register
STATUS
TOPIC
ACTION
LOCKED
One plugin
LootBox lives under MysticEssentials; no separate mod.
LOCKED
Presentation independence
Result committed before animation; no visual reroll.
LOCKED
Odds transparency
Persistent exact numerical odds in every mode.
LOCKED
Mythic signature
Astral Convergence camera sequence is the premium preset.
VALIDATE
Smooth in-UI easing
Benchmark legacy UI update path; native transition only when confirmed.
VALIDATE
3D item/camera API
Test target build and collision/cleanup behavior.
VALIDATE
Multi-client FX isolation
Benchmark particle and temporary-entity scoping.
DEFER
Full timeline editor
Begin with templates and parametric controls; add arbitrary keyframe authoring later.

References / source-of-truth links
Reference gallery (inspiration only): https://modrinth.com/plugin/crazycrates/gallery
Official Hytale Update 6 camera-related patch notes: https://hytale.com/news/2026/5/pre-release-patch-notes-update-6
Official Hytale Update 7 modder notes: https://hytale.com/news/2026/10/2026-10-12-modder-facing-patch-notes-update-7/
Official Hytale Update 7 pre-release notes (experimental Noesis status): https://hytale.com/news/2026/9/pre-release-patch-notes-update-7
Official Hytale server operator policies: https://hytale.com/server-policies
Hytale documentation (versioned API/asset reference): https://docs.hytale.com/
Community technical overview of Hytale Custom UI data flow: https://github.com/HytaleModding/site/blob/main/content/docs/en/established-information/server/interface/ui-customization.mdx
Verified online for this document on 23 October 2026. Release builds and API features change; verify all implementation assumptions against the installed server jars and current official Hytale requirements. CrazyCrates and the uploaded reference video are design inspiration only.
