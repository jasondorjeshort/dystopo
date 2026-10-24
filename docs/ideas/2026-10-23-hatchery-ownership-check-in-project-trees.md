---
date: 2026-10-23
kind: chore
---

# For Hatchery's queue: the ownership check runs in project trees

A request for the Hatchery base, with no diff to carry back, so it waits here for a Hatchery-rooted
session to file it in Hatchery's `docs/ideas/` (the `carry-back` skill: a request without a diff is
the base coordinator's idea, not a branch). It is ADR 0038's pending carry-back for
`.claude/hooks/ownership.json`; the send agent found on 2026-10-23 that a project-side list would
change a promised surface (Hatchery ADR 0016 makes the map's four lists the whole schema and reads
only the base's map at the base sha; its companion says `--check` is for Hatchery's tree only), so it
needs a proposal there, not a carry-back.

The request: fitness check O1 (`ownership.js --check`) and A1 skip only where a `hatchery` remote
exists. A fresh project clone has no such remote, so O1 runs on the project's tree and fails on every
project path the base's map does not list; the project then edits the base-owned `ownership.json`
(`projectOwned`) to pass, which conflicts on every update. Either (a) O1 and A1 skip by the `adr`
skill's two-part test (a `hatchery` remote, or a `Hatchery base:` line under
`docs/design/decisions/`), as CB1 now does on `carry-back/test-hooks-cb1-skip`: code only, matching
ADR 0016's "Hatchery's tree only", and the project's added `projectOwned` entries become unnecessary;
or (b) a project-side list, a project-owned file that `--check` merges in and the update never
patches, which changes the map's schema and adds a hand-written surface, so it needs a proposal and an
ADR 0013 row. If Hatchery wants (a), it can go as its own carry-back branch the way CB1 did.

Plan: a Hatchery-rooted session files this as an idea in Hatchery's queue and deletes this file in
the same sitting; on (a), a send agent here cuts `carry-back/ownership-check-skip` from
`hatchery/main` by the `carry-back` skill.
