---
paths:
  - "shared/src/commonMain/sqldelight/**"
  - "**/*.sq"
  - "**/*.sqm"
---

# Track database migrations

`Track.sq` is always the current schema; `TrackDb.Schema.version` is the highest `<n>.sqm` plus one.
To change it:

1. Run `./gradlew :shared:jvmTest --tests "*TrackDbMigrationTest*"` and copy
   `shared/build/trackdb/<version>.sql` to `shared/src/commonMain/sqldelight/databases/` (the frozen old
   schema; never edit those afterwards).
2. Write `<version>.sqm` beside `Track.sq` with the upgrade (new columns go at the end of a table, in
   the same order as in `Track.sq`).
3. Change `Track.sq`.

`TrackDbMigrationTest` (in `check`) builds every frozen version, applies the migrations, compares
tables, columns and indexes with a fresh `Track.sq` database, and upgrades a version-1 file with rows
in it; the Android driver runs the same generated `migrate` on first open after an update.
SQLDelight's own `.db`-based verification cannot run here: it loads sqlite-jdbc in a Gradle worker that
Windows starts with an empty environment (details in `shared/build.gradle.kts`; finding
`docs/findings/2026-10-10-sqldelight-verify-empty-environment.md`).
