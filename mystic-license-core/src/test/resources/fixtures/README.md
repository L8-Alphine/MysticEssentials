# Interoperability fixtures — DEVELOPMENT KEYS ONLY

These two files are copied verbatim from the licensing portal repository
(MysticGate), where they are produced by `npm run vectors:generate`:

| File | Source |
| --- | --- |
| `interop-vectors.json` | `tests/fixtures/interop-vectors.json` |
| `license.mclicense` | `tests/fixtures/license.mclicense` |

They exist so this library can prove, on every build, that it rebuilds the
signed byte sequence exactly as the TypeScript issuer does. That agreement is
the contract with the portal: if it drifts, licenses stop verifying in the
field, and the failure would otherwise only show up on a paying customer's
server.

## The keys in here are published and worthless

`interop-vectors.json` contains an **Ed25519 private key** and an **AES-256
content key**. Both are development-only, both are committed to a repository,
and neither has ever signed or encrypted a real license. The signing key id is
`mystic-signing-test-0001` and the content key id is
`mystic-license-content-test` — deliberately different from the production ids
in `EmbeddedKeys`, so a test key can never be mistaken for a live one.

Production keys live in the portal's `secrets/` directory and are never copied
here. The only production material this repository holds is the Ed25519
**public** key, which is public by definition.

## They must never reach a shipped jar

These are test resources (`src/test/resources`), so Gradle does not put them on
the runtime classpath and they are not packaged into `mystic-license-core.jar`
or the mod's shadow jar. If you ever move them, keep them out of `src/main`.

## Regenerating

If the portal changes the vectors, re-copy both files and run
`gradlew :mystic-license-core:test`. A failure in `InteropVectorTest` means the
wire format moved and this library needs updating before the next release —
that is exactly what the test is for.
