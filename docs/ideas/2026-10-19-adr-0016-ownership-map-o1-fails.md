---
date: 2026-10-19
kind: chore
---

# ADR 0016: the ownership fitness check O1 fails on main

`node .claude/hooks/test-hooks.js` on `main` at v0.1.9-alpha: 693 passed, 1 failed. O1 checks every
tracked path against `.claude/hooks/ownership.json` (schema: ADR 0016) and lists as unclassified the
project's own code and tooling: `.github/workflows/`, `CHANGELOG.md`, `CONTRIBUTING.md`,
`NOTICES.md`, `androidApp/`, `tools/` and others. The map's `projectOwned` list names only docs and
root files, so an update from Hatchery (ADR 0037) cannot tell what it must leave alone.

Plan: one pass by a Sonnet-class agent, owning `.claude/hooks/ownership.json` only. Run the hook
tests and take the full O1 list; add each unclassified top-level path (the modules, `tools/`,
`gradle/`, `.github/`, the Gradle build files, `areas.json`, `CHANGELOG.md`, `CONTRIBUTING.md`,
`NOTICES.md` and whatever else O1 names) to `projectOwned`, as directories where a whole directory
is the project's; nothing moves into `baseOwned` or `mixed`, and no other entry changes. Verify:
`node .claude/hooks/test-hooks.js` reports 0 failed, and `node .claude/hooks/policy.js
--claude-check` stays at 0 FAIL. Out of scope: the ownership of anything under `.claude/`, and the
`update-from-hatchery` skill. Deliverable: the map fixed, ADR 0016 unchanged (its schema already
allows this).
