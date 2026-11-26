---
date: 2026-11-25
kind: chore
related: [2026-10-23-hatchery-ownership-check-in-project-trees.md]
---

# For the base: project policy keys without forking the loader

Source: coordinator (claude-fable-5-2), a spark during the update from the base of 2026-11-25, in the offer the owner asked for; offered and accepted by the owner; not sanity-checked.

This project's `emulators` block in `.claude/hooks/policy.json` (the emulator cap, T190) needs lines in the base's
loader, because `.claude/hooks/policy.js` throws on an unknown key. Since the update of 2026-11-25 the base's schema
check (harness group PS in `.claude/hooks/test-hooks.js`) also fails until the block's keys are recorded in
`.claude/hooks/policy.schema.json` and given in the check's fixture. One project setting now costs three base-owned
files, each a departure row and a conflict at every update that touches them. The request has stood in the Carry-back
cell of ADR 0038's `emulators` row and was never filed.

Wanted, in the base: a section the loader passes through without a schema edit (for example a `project` key whose
members a project's own scripts check), left out of `--schema` and of group PS. The emulator hooks then read
`project.emulators`, and the three rows close.

Lever: closes three departure rows here and keeps any project's own keys out of the base's files. It outranks "a
durable place for texts meant for another repository", which this file itself would use.

## Open

- Whether the loader type-checks the section at all, or a project registers a checker beside its hooks.
