# 0033. Data format changes in alpha: built without prior sign-off, user data still migrates

Date: 2026-10-16
Status: accepted (the owner's decision)

Record: the owner, 2026-10-16, before planning wave 15 ("Doctrine for alpha version: we can do data
format changes"). Supersedes ADR 0031 in part: its Decision 3's words "and every data format". The rest
of ADR 0031 stands.

## Context

ADR 0031 freed interface changes in alpha but kept every data format on the list of things flagged to
the owner before building, because a format change is not undone by a later task. Wave 14 left one
such item waiting: T102's follow-up to store turn directions with a trip (`docs/TASKS.md`, T102 Status:
"a data format change, flagged"). The owner has now said format changes are fine in alpha, as the
interface is. The alpha milestone is ADR 0024's: alphas on GitHub Releases now, beta through Google
Play later.

## Decision

1. **Built without asking.** While the app is in alpha (until the first beta, ADR 0024), a change to
   a data format is a technical decision like any other (`CLAUDE.md` › Decisions): the track database,
   trips, settings and area manifests on the phone; packs, caches and graph tiles; the server's files
   and HTTP API; the sync and peer protocols. A task entry or an idea that needs one builds it; the
   PR lists each format it changes and what data written by the previous release does after the
   update (migrated, rebuilt, or refused with a message).
2. **User data still survives an update.** What a user made (tracks, waypoints, trips, settings,
   pinned areas) migrates: `.claude/rules/trackdb.md` for the track database, and an upgrade test with
   a fixture written in the old format for anything else. Derived data (tile caches, DEM and contour
   caches, routing graphs and overlays) may be invalidated and rebuilt instead, by a version in its
   cache key, never by being misread.
3. **Mismatched versions fail plainly.** Between two versions in alpha, a phone, a peer or the server
   may stop understanding the other. It then refuses with a reason the user can read ("this phone
   runs an older Dystopo; update both") and a counter or route that shows the refusal; it never
   imports a misread record or drops one silently. A test covers the refusal.
4. **Unchanged.** A major format choice (a new format other tools or phones read, `CLAUDE.md` ›
   Decisions) still goes through a proposal, a critic and an ADR; that is technical review, not the
   owner's sign-off. Still flagged before building: a removed flow no idea describes, the app's
   identity, and anything that sends data somewhere new (ADR 0031, Decision 3).
5. **Expiry.** At the first beta this ADR and ADR 0031 are superseded by one that says how format
   changes are rolled out to testers who cannot all update at once.

## Consequences

- Waves plan format changes without a question round; T102's directions stored with a trip can be a
  wave 15 task. `CLAUDE.md` › Decisions changes in the same commit.
- Each change carries a migration or a cache-key bump and its upgrade test, and protocol changes a
  refusal path and its test. Testers whose phones run different alphas may lose sync or peer download
  until both update.
- Rejected: keeping the flag (the owner lifted it); dropping migrations of user data as well (an
  alpha tester's tracks are records of real trips that a rebuild cannot recreate, and the migration
  test already makes keeping them cheap).
