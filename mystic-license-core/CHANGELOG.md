# Changelog

All notable changes to `mystic-license` are recorded here.

## [1.0.0] — 2026-07-28

First release.

### Supported format

| | |
| --- | --- |
| Envelope | `MCL1`, version **1** |
| Payload | `mystic-license`, `format_version` **1** |
| Signature | Ed25519 over `mystic-license-envelope-v1`, 9 length-prefixed fields |
| Encryption | AES-256-GCM, 12-byte IV, 128-bit tag, no AAD |
| Signing key | `mystic-signing-2026-01` |
| Content key | `mystic-license-content-v1` |

Verified against the portal's generated vectors by `InteropVectorTest`, which
asserts the rebuilt signing input matches byte for byte.

### Added

- `MysticLicenseService` — the interface mod feature code depends on. No
  accessor throws in any state.
- `LicenseGate` — verifies once at startup, caches an immutable snapshot,
  answers `hasFeature` from memory. `reload()` for an admin command.
- `McLicenseVerifier` — the format implementation. Signature is verified before
  the content key is resolved, and `ContentKeySource` makes that ordering
  testable rather than merely documented.
- `NoopMysticLicenseService` — grants nothing, reports `MISSING`; for tests and
  unlicensed builds.
- `ServerIdentity` — generates and persists `server-id.txt`, and writes
  `license-request.json` for the portal. Never silently repairs a corrupt
  identity file, because regenerating would orphan the operator's license.
- `EmbeddedKeys` — production keys, with regeneration and rotation instructions.
  Several signing key ids can be trusted at once.
- `Products` — product and feature id constants mirroring the portal's
  `entitlements.json`, including `mysticessentials` / `module.customcontent`.
- `LicenseCheckResult` — status, payload and a short detail string for the
  startup log line.
- `LicenseLog` — the two-method logging seam, with a `System.Logger` default and
  a guard so a throwing host logger cannot escape.

### Deliberately not included

- Revocation-list fetching. A signed list format exists for the future; a mod
  that hard-fails when a server is unreachable is worse than one that
  occasionally honours a revoked license.
- Obfuscation, anti-debug, integrity self-checks, string encryption. See the
  README's honest-limitations section.
- Any network access whatsoever.

[1.0.0]: #
