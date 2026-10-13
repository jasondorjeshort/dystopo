---
date: 2026-10-10
status: resolved
area: build / SQLDelight on Windows
promoted_to: .claude/rules/trackdb.md
---

# SQLDelight's own migration verification cannot run on Windows here

## Question

T40 added the first track database migration. Why not use SQLDelight's built-in `.db`-file
verification (`verifyMigrations`)?

## What was tried

The verification task loads sqlite-jdbc in a Gradle worker; Windows starts that worker with an empty
environment, so the native library cannot load (details in `shared/build.gradle.kts`).

## Result

Replaced by our own `TrackDbMigrationTest` (in `check`): it builds every frozen schema version under
`shared/src/commonMain/sqldelight/databases/`, applies the migrations, compares tables, columns and
indexes with a fresh `Track.sq` database, and upgrades a version-1 file with rows in it. The procedure
for a schema change is `.claude/rules/trackdb.md`.
