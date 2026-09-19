# Mangaku

Recovered Mangaku 1.5.3 with a buildable Kotlin local-data module and a repeatable
APK build: SQLite migration, safer saves, tracking backups and optional AniList sync.
Version 1.7.1 adds **Other sources** beside **Add source** on manga profiles.
Choose a built-in source, search with the current manga's title, and link the
selection directly to that manga while preserving reading progress.

[Download the latest APK](https://github.com/Kamal142-D/mangaku/releases/latest).
The catalog includes 55 built-in Arabic source adapters. The latest complete
live run passed 20 sources; see [Arabic sources](docs/ARABIC_SOURCES.md) for
verification and remaining provider failures. Cross-source search and ranking
by chapter count are not included in this release.

Build locally with `./scripts/build_release.ps1` in PowerShell. Each run creates
a separate verified APK under `builds/rebuild/`; it uses the existing signing key.

- [Build instructions and preserved patches](docs/BUILD.md)
- [Current storage, backup, and AniList data flow](docs/DATA_FLOW.md)
- [Device checks and final APK details](docs/VALIDATION.md)
- [Original recovery notes](docs/RECOVERY_NOTES.md)

## Project map

The runtime, build scripts, patches, tests and provider references are included
in this repository. APKs are distributed through GitHub Releases. The recovered
project, recovery outputs, build tools and signing material listed below are
local workspace files and are not uploaded to this repository.

| Path | Contents |
| --- | --- |
| `android-project/` | Readable recovered Android/Gradle project |
| `builds/original/` | Unmodified published APK |
| `builds/test/` | Installable debug-signed test APK |
| `builds/intermediates/` | Unsigned and aligned APK build files |
| `docs/` | Recovery and rebuild notes |
| `patches/` | Reviewable 1.5.4 changes applied to the original APK |
| `runtime/` | Buildable Kotlin storage, sync, built-in sources and native settings; Android runtime tests |
| `vendor/` | Pinned upstream source reference and dependency licenses |
| `scripts/` | Clean rebuild and APK signing commands |
| `tests/` | Static regression checks for the preserved fixes |
| `builds/rebuild/` | Isolated build outputs and checksum receipts |
| `recovery/config/` | Configuration reconstructed from APK constants |
| `recovery/decompiled/` | Complete fallback source and name mapping |
| `recovery/raw/` | Original expanded APK contents |
| `recovery/work/` | Apktool and JADX working files |
| `tools/` | Verified recovery/rebuild tools |

The IMHentai filter test APK is located at:

`builds/test/mangaku-v1.5.3-imhentai-filter-test.apk`

The recovered Gradle project is a decompiler scaffold rather than the original
Kotlin source tree. Read `docs/RECOVERY_NOTES.md` before rebuilding from source.
