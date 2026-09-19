# Data flow in 1.6.0

The new implementation lives in four buildable Kotlin files under
`runtime/src/com/mangaku/local/`. The old reader and Compose UI remain recovered
bytecode. SQLite is authoritative; JSON is an adapter for those existing paths.

## Ownership and migration

`files/local-v2/library.db` contains:

| Table | Role |
| --- | --- |
| `manga` | Stable internal ID and provider-independent metadata |
| `user_manga` | Status, chapter, volume, page, rating, notes, read time, device and revision |
| `manga_provider_mapping` | Unique AniList, verified-format MangaDex URL, or unresolved source URL links |
| `chapters` | Provider chapter ID, original label/number and available edition metadata |
| `reading_history` | Deduplicated read events retained beyond the old 500-row snapshot |
| `sync_queue` | Pending revisions, retry time, attempts and last error |
| `sync_baseline`, `conflicts` | Last acknowledged state and explicit local/remote conflicts |
| `meta` | Migration marker, installation ID, sync mode/account, deletion markers |

Existing UUIDs and all 31 serialized Manga fields survive migration. `source`
means ZIP or SEARCH, not a provider identity. Titles never establish identity.
Unqualified `sourceId` values are preserved without pretending they are MangaDex
IDs. Source URLs and AniList IDs must have one owner; ambiguous legacy duplicates
stop migration rather than silently merging libraries.

On first startup LocalStore validates `manga_index.json` and `reading_history.json`,
preserves pre-migration files under `migration-v1/`, imports them in a transaction,
and sets the marker only after success. Invalid input leaves the originals intact
and presents a storage error; repair/export recovery is manual in that case.
Rerunning does not duplicate rows. A newer DB schema is refused, never downgraded.

Native SQLite transactions protect saves; Android AtomicFile protects JSON mirrors
and auxiliary writes. Startup regenerates mirrors from SQLite. The old library
loader cannot turn a malformed input into an authoritative empty replacement.
Save failures are shown and the UI is restored from committed state when possible.
No test claims to reproduce physical flash or battery failure; tests verify rollback
and actual Android read-only write failures.

Bridge tracks each recovered repository's snapshot. Writes compare changed fields
with the current record, preserve unrelated changes, and merge concurrent numeric
forward progress by maximum. Competing resets or other divergent edits fail for
review. Missing rows in a snapshot are not deletion commands. Explicit deletion
commits first; deletion markers stop stale snapshots resurrecting removed IDs.

The compatibility layer serializes whole libraries and uses a process-wide store
lock. It is appropriate to the current single-process recovered app, not a claim
of arbitrary multiprocess replication. Replace the snapshot adapter with row edits
when original UI sources are available. SQLite revision and device fields prepare
safe local acknowledgment; they are not a separate cross-device backend.

## Progress and chapters

New fields are editable in Settings > Data & sync > Manage progress & provider
links. ON_HOLD, DROPPED and REREADING are retained in SQLite/export/sync; the old
three-status library UI projects these as Reading. The new editor retains exact
statuses. Existing reader page positions remain in `chapterPages`; the additional
`currentPage` field is an explicit tracking field, not a replacement for those keys.

Fractional/special chapter labels remain strings. Provider chapter IDs identify
rows; multiple editions can have different IDs for the same number. Existing
ambiguous `n:<number>` page aliases are preserved, not guessed into editions.
Legacy cached chapter metadata has no new expiry enforcement, and chapter-provider
fetching still uses the recovered providers. A complete reader/provider rewrite,
notification redesign and a new device-sync backend are outside this integration.
No Discord integration or bot token was found in this recovered application.

## Portable backups

Data & sync exports schemaVersion 2 JSON containing metadata, links, tracking,
notes and history. It excludes tokens and sync baselines. Restore validates input,
saves `before-restore-<time>.json`, and replaces library/history transactionally.
Invalid history or mapping collisions roll back the entire restore. IDs and paths
are validated, and input is limited to 32 MB. Downloaded image files are kept;
portable page archives continue to use the existing image export.

Legacy Google Drive backup is a separate compatibility path. It does not include
all V2 notes/history/status information. Use the new JSON export for a complete
tracking backup. Its OAuth configuration and live Drive restore were not changed
or validated. The archive adapter now rejects paths escaping the manga directory.

## Optional AniList sync

Default mode is No sync. Connecting validates a manually supplied OAuth access
token and keeps sync off until the user selects Import only or Two-way sync.
The recovered app has no OAuth client ID, so this build does not invent a working
browser authorization registration. Tokens use Android Keystore AES-GCM and are
absent from portable exports. The original disabled sender stays disabled.

SyncEngine uses the new AniList client. It checks Viewer/account identity, imports
paginated entries, preserves UUID mappings, stores baselines, and queues local
updates. Android JobScheduler retries while connected (periodic minimum 15 minutes);
Sync now runs the same worker. HTTP errors and Retry-After are surfaced/persisted.
Sending an older revision cannot acknowledge a newer local edit or a new mapping.

First-sync differences require review. Later changes merge by field against the
baseline; simultaneous forward chapter updates select the larger value. Conflicting
notes/status/resets remain reviewable. Import only never writes to AniList, and
Keep local suppresses that same remote conflict until it changes again. Missing
remote entries require review: unlink preserves local progress; Keep local permits
creation during two-way sync. Switching accounts discards old baselines/conflicts.

AniList supports integer progress: 10.5 remains local and sends 10. Non-numeric
special chapters are retained locally and reported for review. Scores use
`scoreRaw` (0–100); long local notes are retained, with oversized AniList writes
blocked. No automatic remote deletion is performed. A manga still present on
AniList can be imported again in an enabled import/two-way mode.

The API contract was checked against official AniList authentication, MediaList
and rate-limit documentation. Tests use a deterministic provider; authenticated
live AniList behavior still requires a real account/token and is not claimed as
verified by the emulator checks.
