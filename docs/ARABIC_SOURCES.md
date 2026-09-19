# Built-in Arabic sources — 1.7.2

Version 1.7.1 adds **Other sources** beside the existing **Add source** action
on a manga profile. The browser carries the current manga ID, prefills its title
when searching a provider, and links the selected result to that library record.
Version 1.7.2 opens a combined search from this action, with progressive results,
chapter counts, latest numeric chapter and descending chapter-count ranking.
It searches the first page of each allowed source and keeps up to five matches
per source. Chapter details load in batches of 12; unknown counts stay last.
Single-source browsing remains available. There is no separate latest-chapter
sort selector in this version.

Release: https://github.com/Kamal142-D/mangaku/releases/tag/v1.7.2

The 1.7.2 APK matches its build receipt and runtime inputs; its version and
signature were verified before publication. New aggregate-ranking checks are
included in the source, but no new runtime or UI test run was performed during
publication. The UI and live-source evidence below describes the earlier 1.7.0 run.

All 55 Arabic source entries from pinned Keiyoushi revision
`eb9062cd3d81645a87a8bc80364db75931ffffca` are compiled into the runtime.
Users do not download or install extension APKs. This is a catalog and
implementation count, **not a claim that all 55 sites currently work**.
39 entries are visible by default; 16 upstream-classified adult entries require
the screen's explicit toggle.

## Implementation

- `SourceCatalog.kt`: source names, IDs, domains and upstream classifications.
- `Sources.kt`: native HTTP, cookies, stable source and chapter IDs, library
  mapping and the bridge into the recovered application.
- `HtmlSources.kt`, `ApiSources.kt`, `DilarCrypto.kt`: source protocols adapted
  from pinned upstream code. No dynamic plugin loading or downloaded code.
- `SourceImages.kt`: bounded, process-local image server supplies the existing
  reader/downloader with images requiring source headers or transformations.
- `SourcesActivity.kt`: source list, favorites, search, pagination, manga and
  chapter details, add/link to library and source website verification.
- `LocalStore.kt` and `Bridge.kt`: persist provider mappings and preserve them
  when the old application serializer saves a title. Linking to an existing
  title is explicit and retains its tracking data.
- `scripts/integrate_runtime.ps1`: manifest entries, chapter/page hooks,
  Sources entry in the Add Manga dialog, version and bundled license notices.

The old reader and downloader both call the shared chapter-page hook. The
source browser opens through Add Manga > Arabic sources and through
Settings > Data & sync > Arabic sources.

## Evidence available

The latest complete live run attempted all 55 sources. **20 passed** catalog, a nonempty
search, chapter listing, page URL parsing and decoding one downloaded image.
**35 failed** at one of those stages. See `SOURCE_VALIDATION.md` for every
source and its latest error. This is a sample from each site, not every
title, language, chapter or image, and not a reader/download UI test.

The 15 existing storage/sync scenarios passed after the source changes in
`builds/runtime-tests/6b3180d299a14dbaa8d07eb126efe009/results.txt`.
The same run passed offline source checks and installed-release integration,
including source mappings and fractional progress surviving the old serializer.
The live matrix is in `builds/runtime-tests/7c375ef54248472e9a6ed664794c0a6e/`.
UI checks opened the new Add Manga source button, browsed AriaToon, added
جذور, displayed a page, and downloaded all five pages of its first chapter.
With airplane mode enabled, Wi-Fi and mobile data disabled, the app was
force-stopped and restarted. The saved chapter still showed Downloaded and
its first page rendered as `Ch 1 · 1/5`. Evidence: `builds/source-offline-reader.png`,
`source-offline-reader.xml`, `source-offline-detail.xml` and
`source-offline-chapters.xml`. This verifies one complete offline chapter flow,
not offline coverage of every source or every page.
Light mode, Arabic RTL and dark mode with font scale 1.3 were inspected.

The earlier signed 1.7.0 preview APK was built at
`builds/rebuild/d8131ceb309e4e1da12add46ba16a181/app-release.apk`.
The checked distributable copy is
`builds/preview/mangaku-v1.7.0-built-in-sources.apk` (11,205,042 bytes).
SHA-256: `16A3FC9FA2EB1BF88AB4B7C3AE6ACCD21A5FF5F7CEFF83D947BF15CA925912DD`.
Its runtime input hashes and integration script hash match the workspace.
This is a preview: full support across all 55 sites has not been achieved.
The existing `builds/release/mangaku-v1.6.0.apk` has not been replaced.

## Corrections included and retested

- Madara sources: align catalog/search requests with upstream AJAX behavior.
- MangaDar: exclude navigation links and recognize the site's explicit empty
  chapter state. All five sampled titles were empty in the latest run.
- RocksManga: extract images nested in the reader's `.img` containers.
- MangaTales: use the API's actual filter parameter names.
- Tests: try up to five titles if the initial title has no chapters, select an
  unlocked chapter, and record URLs for reproducible diagnosis.
- Duskoryvile: report the observed login page as requiring login instead of an
  empty catalog. A normal catalog with a Login navigation link remains valid.
- Empty search results produce a partial result, never a full pass.
- Bundle jsoup's MIT license alongside the upstream attribution and license.

Saved response inspection also found MangaTuk had changed to a different site
implementation, Murim returned a lander redirect, and Onma returned a chat site.
Those observations do not establish permanent unavailability. They require
fresh inspection before changing domains or claiming renewed support.
Several other failures were DNS, timeout, verification or connection errors.
Dilar now responds with encryption protocol v12, while the pinned upstream
implementation supports v1-v9. Its reader needs an updated adapter. OrcaManga
returned no readable pages, and MangaLink/NeverScans returned no catalog entries.
These are unresolved failures, not successful integrations.

## Repeatable checks

Use a disposable emulator and the existing SDK/JDK setup in `BUILD.md`.

```powershell
# Compile current code; run storage and offline source checks.
./scripts/test_runtime.ps1 -Serial emulator-5580

# Also exercise every source against the live sites and save the matrix.
./scripts/test_runtime.ps1 -Serial emulator-5580 -LiveSources

# A focused live rerun after inspecting a failure.
./scripts/test_runtime.ps1 -Serial emulator-5580 -LiveSources -SourceIds 'dilar,eshadow,mangadar,mangatales,rocksmanga'

# Build the current application, install its printed output on a test profile,
# then validate the installed release integration.
./scripts/build_release.ps1
./scripts/test_runtime.ps1 -Serial emulator-5580 -Integration
```

Each test run saves evidence separately in `builds/runtime-tests/<run-id>/`.
Live failures remain visible in the matrix and raise a script warning; they do
not erase earlier evidence. Unknown source IDs cause the instrumentation to fail.
Live tests include the catalog's adult-classified sites unless filtered.
They request public catalog/chapter/image data and do not mutate user accounts.

## Remaining coverage and limitations

The failed sources need provider-specific updates or restored site availability.
Successful login/challenge completion and retry in the source WebView have not
been verified. Real-device performance and sustained 60 fps are not certified.
Navigation recordings were captured and sampled; this does not establish
frame-by-frame motion quality across all devices. The earlier automatic-review
usage-limit failure was temporary; later approved build and test runs completed.
A second review quota failure prevented the additional page-turn check and
restoring the disposable emulator's network. On the next continuation the
restore command was approved, but that read-only emulator session was already
gone. No physical device networking was changed.

Known limits: only the first configured mirror is selected automatically;
other hosts are recognized but there is no automatic mirror failover. Image
capabilities are process-local and temporary; persistent offline storage is
provided by the existing downloader.
