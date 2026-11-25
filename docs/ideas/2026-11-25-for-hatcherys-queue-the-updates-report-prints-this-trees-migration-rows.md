---
date: 2026-11-25
kind: chore
related: [2026-11-25-for-hatcherys-queue-an-update-never-locks-out-the-guard.md]
---

# For Hatchery's queue: the update's report prints this tree's migration rows

Source: coordinator (claude-fable-5-2), a spark during the update from the base of 2026-11-25, in the offer the owner asked for; offered and accepted by the owner; not sanity-checked.

The base's ADRs now carry a Migration table for an incompatible change (Tree, Arrives by update, The project edits,
When, Check; the `adr` skill, step 3). The update of 2026-11-25 brought one, in
`docs/hatchery/decisions/0026-brainstorm-contract.md` section 5, whose row for this tree listed four edits due "at its
next update". Nothing in the update pointed at it: `node .claude/hooks/ownership.js --report` prints unclassified,
excluded, replaced sections, unimported and collisions, and the row was found by reading the sibling file for
another reason.

Wanted, in the base: the report gains a section `migrations`: for each decision file added or changed between the
base line and the new commit, the rows of any Migration table whose Tree cell names this repository (by the name of
its origin remote) or "any other tree with a base line", printed with the file's path. The skill's step 6 then lists
it among the sections that need an action.

Lever: a migration is done in the update that brings it, not discovered later. It outranks
`queue-list.js ideas --stats`, a convenience.

## Open

- How a tree is named in a row when its remote differs from the name the base uses.

Where it is built: in Hatchery, by a session rooted there, not in this tree (the owner, 2026-11-25, by multiple choice: "Split by owner"); building it here would add a departure row.
