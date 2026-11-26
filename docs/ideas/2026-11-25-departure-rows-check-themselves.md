---
date: 2026-11-25
kind: chore
related: [2026-10-23-hatchery-ownership-check-in-project-trees.md]
---

# Departure rows check themselves

Source: coordinator (claude-fable-5-2), a spark during the update from the base of 2026-11-25, in the offer the owner asked for; offered and accepted by the owner; not sanity-checked.

ADR 0038's departure table (`docs/design/decisions/0038-adopted-from-hatchery-after-t121.md`) has one row per
base-owned path this project edits. During the update of 2026-11-25 each conflicted path was compared with the base by
hand (`git diff <base line> -- <path>`) to learn whether its row still held: two rows had been settled by the base
weeks before, and three base commits taken by hand had left paths that matched the base with no record.

The idea: a command (a mode of `.claude/hooks/ownership.js`, or a project script beside it) reads the table and the
ownership map and prints two lists: a row whose path no longer differs from the base line's commit (the row can go),
and a base-owned path that differs with no row (a departure nobody recorded). Run by the update skill's checks and by
the triage scan.

Lever: the table stays true between updates, and an update's conflicts are known before it runs. It extends the
ownership-check idea beside it (fitness check O1 in project trees) and outranks it: O1 says a path is unowned, this
says which recorded departures are stale.

## Open

- Rows that name a part of a file (a harness case, a policy block): the check can only say the file differs.
- Whether this is the base's (`ownership.js`) or stays a project script.

Plan: one pass, a project script, so no base-owned file changes and no new departure row: the table is this
project's (ADR 0038), and the base can take the script later through its queue. `node tools/departure-check.js` reads
the `Hatchery base:` line and the `## Departures` table of `docs/design/decisions/0038-adopted-from-hatchery-after-t121.md`
and `.claude/hooks/ownership.json`, then prints one JSON object: `stale` (a rowed path whose working-tree file equals
`git show <base line>:<path>`, so the row can go), `unrecorded` (a base-owned path, by the map, that differs from the
base line's and has no row; `git diff --name-only <base line> -- <base-owned paths>` minus the rowed paths), and
`partial` (rows naming part of a file, whose file differs: reported, never judged). It needs the `hatchery` remote
fetched and says so when the base line's commit is missing. Tests in a `tools/test_departure_check.py` beside the
others (a scratch repository with a fake table), run by `tools/run-tool-tests.py`. The `wave` skill's Close step 7
runs it before and after the update and the report carries both lists. Verify: run on `main`; each `stale` or
`unrecorded` line checked by hand against `git diff`. Out of scope: editing the table (the update's), the base's
`ownership.js` and the `update-from-hatchery` and `triage` skills (base-owned).
[no-check: the script and its test file are what the pass adds]
