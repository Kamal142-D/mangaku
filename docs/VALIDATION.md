# Validation: Mangaku 1.6.0

Verified on 2026-09-17 with the final source and integration hashes matching the
built APK. Nothing was published and no real AniList account was used.

## Artifact

- Downloadable workspace copy: `builds/release/mangaku-v1.6.0.apk` (10,971,498 bytes).
- Build: `builds/rebuild/535e773eca9a478d921a38625e4fb955/`.
- Package `com.mangaku.app`; version 1.6.0, code 11; minimum Android API 26.
- SHA-256: `1F97C81DF97BCDFA6D3099243033340936B00CAD71A82059515B052BDBFAC213`.
- Certificate SHA-256: `0b49464c6a19a50bcfca4dba1d4aa398a44078028e53ffba281b5dfcc3eb9fcb`.
- APK v2/v3 signatures, alignment, and package/version checks passed.
- Receipt: `builds/release/build-receipt.json`; pinned dependency hashes in the
  build's `runtime/toolchain.json`. Kotlin compiler 1.9.24; full JDK 25.0.2;
  Apktool 3.0.3; Android Build Tools 34.0.0; compile platform 34.

## Automated Android checks

`./scripts/test_runtime.ps1 -Integration` passed **15 scenarios**, plus the
installed-release integration check. Evidence:

- `builds/runtime-tests/009762d00cb4442fbc4fe58ab67acddf/results.txt`
- `builds/runtime-tests/009762d00cb4442fbc4fe58ab67acddf/integration.txt`

These use real Android SQLite, AtomicFile and Keystore. The integration check
runs against the installed signed release and exercises the recovered repository,
serializer, state flow publication, preservation of notes/volume, valid page ZIP
restore, rejection of ZIP traversal, and deletion. Remote sync uses a fake
provider to test conflicts, retries, missing entries and changes during a send.

Failures found and fixed during validation included SQL parameter count, numeric
JSON equivalence after round trips, the retained resolution of import-only
conflicts, and stale mapping acknowledgments. A path alias assertion and the
instrumentation startup timing were test-harness issues and were also corrected.

## UI checks

On `mangaku_test`, Android 14 / API 34, 1080x2400:

1. Launch and navigate Settings > Data & sync using the original Compose UI.
2. Restore a synthetic V2 backup through the Android document picker; confirm
   two manga and one reading event are present.
3. Enable airplane mode; edit chapter 10.5 to 12.5, save, force-stop and restart.
4. Export through the document picker after restart. Parse the exported JSON and
   verify chapter 12.5, volume 2, page 4, original notes and the history event.
5. Inspect native controls in light mode and dark mode with font scale 1.3.
   Labels and buttons remained readable, and the screen supports scrolling.

Evidence is under `builds/ui-validation/`: `exported-backup.json`,
`data-light.png`, `data-dark-large.png` and `final-integration.txt`.
All test records are synthetic. The AVD ran with `-read-only -no-snapshot`, so
package replacement and data changes occurred in a disposable emulator session.
An archive of the emulator's prior app files was retained before replacement.
No physical phone or user's main library was modified.

## Limits

- Authenticated live AniList import/push is unverified; connecting requires a
  real user-supplied token. Browser OAuth registration is not configured.
- No new cross-device backend, chapter-provider rewrite or notification redesign
  is included. See DATA_FLOW.md for the exact compatibility boundaries.
- Reader network sources, live Google Drive OAuth/restore and physical-device
  behavior were not revalidated by these checks.
- Installation over the original 1.5.3 APK needs a user-managed backup and reinstall
  because its signing certificate differs. Same-key upgrades were exercised on
  the emulator and preserved the app's local data.
