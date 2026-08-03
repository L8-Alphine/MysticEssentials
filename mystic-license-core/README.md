# mystic-license

Offline verification of `license.mclicense` files, and a small API for switching
individual mod features on and off based on what a license grants.

No network access, ever. Once the file is on disk the mod never contacts the
licensing server, never phones home, and never needs an internet connection to
start.

---

## The one rule

**A licensing failure disables a licensed feature. It never disables the mod and
never breaks the server.**

Everything in this library is arranged around that sentence:

- No method on `MysticLicenseService` throws. Ever. `hasFeature` returns `false`
  and that is the whole error path.
- `LicenseGate` never throws from construction, `start()`, `reload()`, or any
  accessor — including when this build's own embedded keys are malformed.
- A corrupt, truncated, empty or missing file is a status, not an exception.
- Unlicensed functionality is never switched off because a licensed feature is
  locked.
- One log line at startup. Feature checks are silent, however often they run.

Turning a billing question into an outage is the worst outcome available here.
An operator whose Patreon payment failed should lose one module, not their
server.

---

## Install

The library is a subproject of this repository.

```kotlin
// settings.gradle.kts
include("mystic-license-core")

// build.gradle.kts
dependencies {
    implementation(project(":mystic-license-core"))
}
```

Java 17 or newer. Ed25519 arrived in Java 15; 17 is the floor because it is the
oldest LTS a Hytale server realistically runs on.

`mystic-license-core` has **no runtime dependencies**, so it shades into a mod
jar without relocation and cannot collide with anything the server already
loads.

---

## Wiring a mod

```java
LicenseGate license = LicenseGate.builder(Products.ESSENTIALS)
        .dataDir(modDataDirectory)      // license.mclicense + server-id.txt live here
        .modVersion("1.0.2")
        .logger(myLoggerAdapter)        // two methods: info, warn
        .build();

license.start();                        // verifies once, logs one line

// Gate the licensed module. Everything else registers unconditionally.
license.whenLicensed(Products.Essentials.MODULE_CUSTOM_CONTENT,
        this::registerCustomContentModule);
```

That is the whole integration. Feature code should depend on the
`MysticLicenseService` interface, not on `LicenseGate`, so it can be stubbed with
`NoopMysticLicenseService` in tests.

### The three integration points

Hytale has no stable public modding API to target, so the core compiles and
tests with **zero** Hytale dependencies and takes these from the host instead:

| What | How | Default |
| --- | --- | --- |
| Where the license file is | `.licenseFile(Supplier<Path>)` | `<dataDir>/license.mclicense` |
| This server's UUID | `.serverUuid(Supplier<String>)` | `ServerIdentity`, persisted in `<dataDir>/server-id.txt` |
| Logging | `.logger(LicenseLog)` | `System.Logger` |

The supplied UUID is normalised to lowercase before the binding check, and a
supplier that throws is treated as "not available" rather than as a failure.

`mystic-license-example-mod` is a working adapter with no Hytale types in it.
For the real thing, see `INTEGRATION.md`.

### Caching and reload

`start()` verifies once and caches an immutable snapshot. `hasFeature` is then a
map lookup, cheap enough to call from game code. Nothing re-verifies on a timer.
`reload()` is the only way to re-read, and exists so an operator who has just
renewed can drop the new file in without restarting — wire it to an admin
command.

The cached state is published through a volatile field, so game threads always
see a complete snapshot, never a half-initialised one.

---

## Status table

What each status means for a server operator.

| Status | What happened | What the operator does |
| --- | --- | --- |
| `VALID` | Everything checks out. | Nothing. |
| `GRACE_PERIOD` | Expired, still inside the grace window. **Features stay on.** | Renew before grace ends. |
| `MISSING` | No license file in the data directory. | Register the server in the portal, download `license.mclicense`, drop it in, restart. |
| `INVALID_FORMAT` | The file is not a readable MCL1 envelope — truncated, empty, edited, or not a license at all. | Re-download it. Do not edit it by hand. |
| `INVALID_SIGNATURE` | Ed25519 verification failed. The file was modified or forged. | Re-download it. |
| `DECRYPTION_FAILED` | The signature was fine but the content key does not match. | Update the mod to a build carrying the right key. |
| `WRONG_SERVER` | Bound to a different server UUID. | Check `server-id.txt`; use the portal's server-replacement flow. |
| `WRONG_PRODUCT` | Valid, but does not cover this product or feature. | Check what the tier includes. |
| `NOT_YET_VALID` | Current time is before `not_before`. | Check the machine's clock. |
| `EXPIRED` | Past expiry *and* past grace. | Renew. |
| `UNSUPPORTED_VERSION` | Newer envelope or payload format than this build understands. | Update the mod. |
| `UNKNOWN_SIGNING_KEY` | Signed by a key this build does not trust. | Update the mod. |
| `UNKNOWN_ENCRYPTION_KEY` | Encrypted with a content key this build does not carry. | Update the mod. |

`VALID` and `GRACE_PERIOD` grant access. Every other status means the licensed
feature stays off and everything else keeps working.

---

## Key rotation, from the mod's side

Licenses already downloaded name the key that signed them, so rotation cannot be
a swap — a build that trusts only the new key turns every outstanding license
into `UNKNOWN_SIGNING_KEY` the moment operators update.

`McLicenseVerifier` trusts several `signing_key_id`s at once. The sequence:

1. The portal generates the new key and starts issuing with it.
2. Ship a mod build that trusts **both** ids — add a second `trustSigningKey`
   call in `EmbeddedKeys.trustAll`, and nowhere else.
3. Wait for every license signed by the old key to expire.
4. Drop the old key in a later release.

To regenerate the embedded values, in the portal repository:

```bash
npm run keys:show-public
```

```bash
npm run keys:content
```

---

## Why there is no JSON library

The core bundles a ~200-line strict parser (`MiniJson`) instead of depending on
Gson or Jackson.

The alternative was `compileOnly` plus a shadow-relocated Gson. That works, but
it means every consuming mod has to configure relocation correctly, and getting
it wrong puts an unrelocated JSON library on a classpath shared with a server
that has its own. The failure mode is a `NoSuchMethodError` at runtime on
somebody else's server, which is exactly the class of problem this library
exists not to cause.

The parser only ever *reads*. It never serialises, so canonicalisation — the
genuinely hard part of JSON-based crypto formats — is not this library's
problem. Two properties are pinned by tests: integers survive exactly as
`long` (a `double` would round `grace_period_seconds`), and malformed input
throws rather than parsing into something half-sensible. It also caps nesting
depth, because a `StackOverflowError` on a game thread is the outage this
library is supposed to prevent.

---

## The format, briefly

`license.mclicense` is UTF-8 JSON. Its own formatting is not signed.

The Ed25519 signature covers a length-prefixed byte sequence, not serialised
JSON:

```
UTF8("mystic-license-envelope-v1")
uint32be(9)
uint32be(len(field)) || UTF8(field)   × 9
```

The nine fields are `magic`, `version` as decimal ASCII, `signing_key_id`,
`encryption_key_id`, `algorithm.encryption`, `algorithm.signature`, then `iv`,
`ciphertext` and `authentication_tag` **as their base64url text**, not as
decoded bytes. Hashing the text is deliberate: both sides hash exactly the
strings they read out of the file, with no re-encoding step where two correct
implementations could disagree.

The payload is AES-256-GCM, 12-byte IV, 128-bit tag, no AAD, with ciphertext and
tag stored separately (Java's `Cipher` wants them concatenated).

`InteropVectorTest` asserts the rebuilt signing input matches the portal's own
generated vector byte for byte. That test is the contract with the issuer; if it
fails, licenses are about to stop working in the field.

### Verification order, and why it is fixed

Signature **before** decryption. The ciphertext is attacker-supplied until the
signature says otherwise, so no unauthenticated byte reaches the AES
implementation or the JSON parser. This is not a comment — `ContentKeySource`
exists so a test can prove the content key is not so much as *requested* when a
signature fails.

---

## Honest limitations

Read this section before deciding what this library is for.

**The Ed25519 signature is the security boundary.** The AES content key ships
inside the mod and must be assumed extractable by anyone holding the jar. It
makes license contents opaque to casual inspection and nothing more. Extracting
it lets someone *read* a license; it does not let them *make* one, because
forging requires the Ed25519 private key, which only the licensing server holds.

**Java bytecode can be patched.** A determined person can remove these checks in
an afternoon. This is a licensing control for honest users, not DRM.

Deliberately absent, and staying absent: obfuscation, anti-debug tricks,
integrity self-checks, string encryption. They cost real reliability — they
break on JVM versions nobody tested, they make stack traces useless during an
incident, and they turn support tickets into forensics — and buy almost nothing
against anyone who actually wanted to bypass the check.

**Offline licenses cannot be remotely revoked.** Expiry plus the grace period is
the only thing limiting a downloaded file. A license issued for 30 days is good
for 30 days plus grace, whatever happens to the subscription behind it.

**No revocation-list fetching, on purpose.** A signed revocation list format
exists for the future. It is not implemented here, because a mod that hard-fails
when a server is unreachable is worse than one that occasionally honours a
revoked license. If it is ever added it must be advisory and cached, never
blocking.

**`discord_user` bindings are not enforced.** A mod has no way to learn the
operator's Discord id, so enforcing that binding would reject every legitimate
license of that kind. The portal enforces it at issue time; this library treats
those licenses as unbound.

---

## Tests

```bash
gradlew :mystic-license-core:test
```

Every license used in the suite is genuinely signed and encrypted with a
throwaway Ed25519 keypair, so a passing `VALID` means the real crypto path ran
and a passing `INVALID_SIGNATURE` exercised the same code an attacker would.

The interoperability fixtures in `src/test/resources/fixtures` come from the
portal and carry **published development keys** — see the README beside them.
They are test resources, so they are not on the runtime classpath and are not
packaged into any shipped jar.
