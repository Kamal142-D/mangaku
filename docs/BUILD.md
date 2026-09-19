# Building Mangaku 1.7.2

The supported build combines the original 1.5.3 APK, the preserved 1.5.4 fixes,
and the Kotlin source under `runtime/src/`. The recovered `android-project/`
is a readable reference, not a compilable Gradle project.

```powershell
./scripts/build_release.ps1
```

Each run creates `builds/rebuild/<run-id>/app-release.apk` and a checksum receipt.
It preserves earlier builds and the recovered working tree. It does not publish
or install an APK. The recipe is repeatable; ZIP timestamps can change its hash.

## Toolchain

- Git on PATH; original `builds/original/mangaku-v1.5.3.apk`.
- `tools/apktool_3.0.3.jar` and the bundled JADX Java runtime for Apktool/signing.
- A full JDK, defaulting to Android Studio's `jbr` directory. Override with
  `-JavaHome`. The stripped JADX runtime cannot run the Kotlin compiler.
- Android SDK platform 34 and Build Tools 34.0.0. Override `-BuildTools` if needed.
- Cached Maven jars in the user's Gradle cache: Kotlin compiler, stdlib,
  script-runtime and daemon 1.9.24; reflect 1.6.10; trove4j 1.0.20200330;
  JetBrains annotations 13.0; jsoup 1.17.2. No build-time downloads are performed.
- Existing `signing/mangaku-release.jks` and `signing/key.properties`. Never commit
  these. Missing signing files stop the main build rather than generating a new key.

`build_runtime.ps1` compiles Kotlin against Android, then D8 emits a separate DEX
for API 26+. The release contains no instrumentation tests. `toolchain.json`
records dependency hashes; the build receipt also records source, integration,
DEX, original APK, Apktool, final APK and Java versions.

## Integration

The original APK and Apktool hashes are pinned. The build decodes into a fresh
folder, finds classes by exact DEX descriptor (Windows names can collide),
applies `patches/1.5.4.patch`, and checks the preserved fixes. It then compiles
Kotlin and applies `scripts/integrate_runtime.ps1` before rebuilding/signing.
The result must pass alignment, v2/v3 signature, package and version checks.

| Recovered class | Runtime integration |
| --- | --- |
| `PalDexApplication` | Open SQLite and migrate before the old library loader |
| `X2/l.P`, `X2/l.R` | Route library/history through SQLite; atomic file writes elsewhere |
| `q2/B0` | Register snapshots, save through Bridge, restore by local ID, validate ZIP paths |
| `q2/Q` | Commit library deletion before downloaded-file cleanup |
| `H2/t`, `H2/r` | Open native Data & sync from Settings |
| `q2/v` | Release notes in the three existing languages |
| `q2/V0`, `q2/W0` | Built-in source chapters and image pages for the reader/downloader |
| `C2/v1`, `OpenSourcesAction` | Open the source browser from the Add Manga dialog |
| `v2/s`, `ProfileSourceButtons` | Existing Add source dialog and current-manga Other sources action |

The old filtered-search and imported-source chapter-update fixes remain included.
`update.json` announces the published release. Advance it only after the matching
APK has been uploaded and published. Building does not publish an update.

## Runnable checks

Start a disposable Android emulator, install the built APK, then:

```powershell
./scripts/test_runtime.ps1 -Serial emulator-5580 -Integration
```

Without `-Integration`, the tests run in their own package and isolated folders.
With it, an additional test runs against the installed, equally signed Mangaku
APK with sync off. It adds a uniquely named record, exercises the recovered
repository/serializer, and deletes that record on success. Use a test profile.
Results and the test APK are retained under `builds/runtime-tests/<run-id>/`.
Offline source checks run by default. Add `-LiveSources` for the live source
matrix, optionally `-SourceIds 'ariatoon,rocksmanga'` for a focused run. See
`ARABIC_SOURCES.md` and `SOURCE_VALIDATION.md` for coverage and current failures.

The 15 Android scenarios cover migration/rerun, all legacy fields, corrupt data,
transaction rollback, read-only storage, duplicates, export/restore rollback,
stale snapshots, forward progress versus resets, sync disabled, first-sync
conflicts, retry/revision acknowledgment, import-only, missing remote entries,
relinking, AtomicFile rollback, ZIP containment and Keystore token storage.
AniList uses a fake provider in tests; no real account mutation is part of them.

The separate installed-release test verifies the actual obfuscated constructor,
serializer, SQLite persistence, UI state publication, preservation of new fields
through the old serializer, and explicit deletion. UI smoke checks and the final
artifact details are recorded in `docs/VALIDATION.md`.

## Installation identity

The workspace release key differs from the original published 1.5.3 key. Android
cannot install one over the other. Export existing user data before any manual
uninstall; this build does not migrate across an uninstall. Same-key future
updates preserve app data normally. Google OAuth settings tied to the original
certificate are not repaired by rebuilding.

