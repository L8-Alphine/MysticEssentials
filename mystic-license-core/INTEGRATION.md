# Integration: gating a real module end to end

A worked example of one feature, from the license file on disk to what an
operator sees in chat. The example is the module this repository actually gates:

- **Product:** `mysticessentials`
- **Feature:** `module.customcontent`
- **Module:** `customcontent` — CustomGUIs and CustomDialogs

The same shape applies to `mysticguilds` / `module.npc.guards`; only the two
identifiers change.

---

## 1. The adapter

`LicenseSupport` is the entire Hytale-facing layer. It supplies the data
directory and a logger; the server UUID is left to the library, which persists
one in `server-id.txt`.

`src/main/java/org/hyzionstudios/mysticessentials/core/license/LicenseSupport.java`

```java
public static LicenseGate create(MysticCore core) {
    return LicenseGate.builder(Products.ESSENTIALS)
            .dataDir(core.paths().root())        // mods/MysticEssentials
            .modVersion(core.getVersion())
            .logger(adapt(core))                 // info + warn onto the plugin logger
            .build();
}
```

## 2. Verifying once, at startup

`MysticCore.enable()` builds the gate and starts it **before** any module is
registered, so every module's licensing question is answered from cache.

```java
license = LicenseSupport.create(this);
license.start();          // one log line; cannot throw

registerCoreCommands();
registerCoreListeners();

moduleManager = new ModuleManagerImpl(this);
ModuleBootstrap.registerBuiltins(moduleManager);
moduleManager.enableAll();
```

Note the ordering: licensing sits between the services and the modules. It is
not the first thing that happens, because it needs a logger, and it is not the
last, because modules ask about it.

## 3. Declaring the requirement

A gated module names its feature. That is the whole change to the module — no
`if` statements inside it, and no licensing code in its logic.

`CustomContentModule.java`

```java
@Override
public String licensedFeature() {
    return Products.Essentials.MODULE_CUSTOM_CONTENT;
}
```

`AbstractMysticModule.licensedFeature()` returns `null` by default, which is
what every other module inherits: free.

## 4. The check

`ModuleManagerImpl.enableModule` treats a locked module exactly like a
config-disabled one — one line, skip it, carry on with the rest.

```java
if (!licensed(module)) {
    return;
}
```

```java
private boolean licensed(MysticModule module) {
    if (!(module instanceof AbstractMysticModule base)) {
        return true;
    }
    String feature = base.licensedFeature();      // null for free modules
    if (feature == null
            || core.license().hasFeature(Products.ESSENTIALS, feature)) {
        return true;
    }
    core.log(Level.INFO, "Module '" + module.id() + "' needs the '" + feature + "' ...");
    return false;
}
```

Using `Products.Essentials.MODULE_CUSTOM_CONTENT` rather than the string
`"module.customcontent"` matters more than it looks. An unrecognised feature id
does not fail loudly — it returns `false` forever, and presents as "the feature I
paid for never unlocks", diagnosed by reading two codebases. The constant turns
that into a compile error.

---

## What the operator sees

### Licensed

```
[INFO] [mysticessentials] License: VALID | id=lic_01K1A2BCDEF3456789ABCDEFGH | type=patreon | expires=2026-08-27T18:00:00Z | server=20bf6b33-b798-43bb-b248-e4162a26ce28
[INFO] Enabled module 'customcontent' v1.0.0
```

One line for licensing. Nothing further, however many times the module or
anything else asks about a feature.

### No license file

```
[INFO]    [mysticessentials] Generated this server's licensing id: 20bf6b33-b798-43bb-b248-e4162a26ce28 (stored in mods\MysticEssentials\server-id.txt)
[INFO]    [mysticessentials] No license found. Upload mods\MysticEssentials\license-request.json to the licensing portal to register this server.
[WARNING] [mysticessentials] License: MISSING | server=20bf6b33-b798-43bb-b248-e4162a26ce28 | no license file at mods\MysticEssentials\license.mclicense
[WARNING] [mysticessentials] Place license.mclicense in mods\MysticEssentials and restart. Everything else keeps working.
[INFO]    Module 'customcontent' needs the 'module.customcontent' license feature, which this server does not have; skipping. Run /mystic license for details. Everything else is unaffected.
[INFO]    Enabled module 'teleportation' v1.0.0
[INFO]    Enabled module 'spawn' v1.0.0
...
[INFO]    Mystic Essentials is ready (storage=json).
```

The server starts. Every other module enables. `/customgui` and the dialog
builder simply are not registered — the same as if the module had been switched
off in config.

On first run the mod also writes `license-request.json`, which the operator
uploads to the portal instead of transcribing a UUID by hand.

### Grace period

```
[INFO]    [mysticessentials] License: GRACE_PERIOD | id=lic_01K1... | type=patreon | expires=2026-08-10T18:00:00Z | server=20bf6b33-... | expired at 2026-08-10T18:00:00Z, grace period ends 2026-08-13T18:00:00Z
[WARNING] [mysticessentials] This license has expired and is running on its grace period. Renew it in the portal before the grace period ends.
[INFO]    Enabled module 'customcontent' v1.0.0
```

The module stays on. The reminder is logged once per server start, never per
check.

### Bound to a different server

```
[WARNING] [mysticessentials] License: WRONG_SERVER | id=lic_01K1... | server=9c2f1a04-... | license is bound to [20bf6b33-b798-43bb-b248-e4162a26ce28]
[WARNING] [mysticessentials] This license is bound to a different server UUID. This server's id is 9c2f1a04-... Use the portal's server-replacement flow to move it.
```

Both UUIDs are in the log, which is what makes this answerable without a
support round-trip.

---

## The admin command

`/mystic license` — requires `mysticessentials.license`.

```
[mysticessentials] License: VALID | id=lic_01K1A2BCDEF3456789ABCDEFGH | type=patreon | expires=2026-08-27T18:00:00Z | server=20bf6b33-b798-43bb-b248-e4162a26ce28
Server licensing id: 20bf6b33-b798-43bb-b248-e4162a26ce28
Licensed features: editor.kit, module.customcontent
```

`/mystic license reload` re-reads the file from disk, for an operator who has
just renewed. If the reload unlocks a module that is not currently running, the
command says to follow it with `/mystic reload`, which enables newly-eligible
modules without a restart.

Verification happens at startup and on that command. Never on a timer.

---

## Adding another gated module

1. Add the feature id to `Products.Essentials` (and to the portal's
   `config/entitlements.json`, or nothing can be issued against it).
2. Override `licensedFeature()` on the module.

There is no step 3.

---

## Testing feature code

Depend on `MysticLicenseService`, not on `LicenseGate`. Then the locked state is
free to test:

```java
MysticLicenseService license = NoopMysticLicenseService.INSTANCE;   // grants nothing
```

For the granted state, `TestLicenses` in the core's test sources mints real
signed licenses with a throwaway keypair.
