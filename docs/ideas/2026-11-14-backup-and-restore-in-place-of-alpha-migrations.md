---
date: 2026-11-14
kind: feature
---

# Backup and restore in place of migrations from one alpha to the next

The owner, 2026-11-14, after v0.1.29-alpha: "I also don't care about alpha backwards compatibility". ADR 0033 still
says user data migrates in alpha, and wave 32 paid for it: T267's move of the kept tiles, T279's upgrade rule for
empty tiles, the `upgrade` parts of the sweep and of the draft check, and the migration steps of
`.claude/rules/trackdb.md` for the track database (`shared/src/commonMain/sqldelight/.../store/db/Track.sq`). One
export the app can read back would stand in for them: tracks, routes and kept trips, waypoints, the settings, and
the list of kept areas (`shared/.../areas/AreaManifest.kt`; the tiles are fetched again, not carried). A breaking
alpha then says "export first, restore after", and the same file moves a user to a new phone. The owner, on the
idea: "Yes".

Recommendation (the coordinator): build it before the ADR that amends 0033 lets any task skip a migration, so that
"not compatible" never means "your tracks are gone". The file is a format other tools and phones read, so it goes
through a proposal and an ADR (`CLAUDE.md`, Decisions): open formats inside (GPX for tracks and routes, as Export
GPX in `androidApp/.../app/SaveFile.kt` writes them; JSON for the rest), versioned, one archive; not a copy of the
database, which would carry the schema problem along.

## Open

- What GPX cannot hold of a track or a kept trip (the activity, Walk or Hike, hidden, the card's lines) and where
  it goes.
- Restore over existing data: merge by id, or only into an empty install.
- Whether the settings travel (some are seeded by region, ADR 0022).

Plan: one no-Gradle pass, the `proposal` skill (a file other tools and phones read, so a major choice): a proposal
under the next free number on the backup file, written before the ADR that amends 0033 lets any task skip a
migration. Options at equal depth with their exit costs: one archive of open formats (GPX for tracks and routes,
JSON for the rest) against one versioned JSON of everything against a copy of the database; what GPX cannot hold
and where it goes; restore as a merge by id or only into an empty install; which settings travel (ADR 0022's
seeded ones); the kept areas as a list whose tiles are fetched again; how an older app reads a newer file. Prior
art (ADR 0039): how the established apps export and restore. Owns the proposal, its critic review and the
proposals README line. Done when: the critic filed and every objection answered, the reversal named, the build
order given as tasks. Out of scope: any code.
