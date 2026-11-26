---
date: 2026-11-25
kind: chore
related: [2026-11-22-recurrence-across-projects-bubbles-to-hatchery.md, 2026-11-25-for-hatcherys-queue-an-update-never-locks-out-the-guard.md]
---

# Hatchery dry-runs an update in every sibling before it pushes (moonshot)

Source: coordinator (claude-fable-5-2), a spark during the update from the base of 2026-11-25, in the offer the owner asked for; offered and accepted by the owner; not sanity-checked. Moonshot.

The update of 2026-11-25 met three faults that the base could have seen where the change was written: conflict
markers in scripts the guard loads, the schema check (harness group PS) failing in any tree with a policy key of its
own, and a migration row nobody was pointed at. Each was found here, weeks after the base's commits, by the session
running the update.

The idea: before the base pushes a change to a base-owned path, a step in Hatchery runs the update's command
(`node .claude/hooks/ownership.js --command`) in a scratch worktree of every project checked out beside it, at that
project's `main`, then runs `node .claude/hooks/test-hooks.js` there, and reports per project: paths that conflict,
harness cases that fail, migration rows due. Nothing is committed in the projects; the report rides in the base's
review.

Why carry it: every base change is otherwise tested first in the projects, by whoever updates next.

## Open

- Cost: the hook suite takes minutes per project; which groups a dry run needs.
- Projects not checked out beside Hatchery are not seen (as the carry-back queue's read already accepts).

Question: this is a step in Hatchery's own workflow; retitle it `For the base: ` so the base's triage lists it, or keep
it here?
Options: (1) retitle to `For the base: Hatchery dry-runs an update in every sibling before it pushes` and add no block
(recommended: nothing in it runs in this tree, and the base's triage already reads the siblings it would dry-run);
(2) keep it as this project's own, at rest, as a reminder for the next update's offer; (3) drop it.

Answer:
