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
