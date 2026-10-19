---
date: 2026-10-19
kind: chore
---

# The ownership map leaves the project's own files unclassified (hook test O1 fails)

`node .claude/hooks/test-hooks.js` on `main` at v0.1.9-alpha: 693 passed, 1 failed. O1 checks every
tracked path against `.claude/hooks/ownership.json` (the base's ownership map, Hatchery's ADR 0016,
adopted here by ADR 0037) and lists as unclassified the project's own code, tooling and records:
the modules (`shared/`, `devtool/`, `serverlib/`, `server/`, `tilebuild/`, `androidApp/`),
`buildSrc/`, `gradle/`, `tools/`, `.github/workflows/`, the Gradle build files, `areas.json`,
`CHANGELOG.md`, `CONTRIBUTING.md`, `NOTICES.md`, and in `docs/` the files outside the listed
folders (`TASKS.md`, `PLANNING.md`, `SERVER.md`, `ARCHITECTURE.md`, `images/`). The map's
`projectOwned` list names only some root files and docs folders, so an update from Hatchery cannot
tell what it must leave alone. Its `$comment` points at `docs/design/decisions/0016-ownership-map.md` [no-check: a Hatchery path, absent here],
which exists only in Hatchery.

Plan: one pass by a Sonnet-class agent, owning `.claude/hooks/ownership.json` only. Run the hook
tests and take the full O1 list; add each unclassified path to `projectOwned`, as a directory
where the whole directory is the project's (`docs/TASKS.md` and the other docs files by name, or
`docs` as a whole only if no `baseOwned` entry lies under it);
nothing moves into `baseOwned` or `mixed`, and no other entry changes. Point the `$comment` at
ADR 0037 (which names Hatchery's ADR 0016) instead of the absent file. Verify:
`node .claude/hooks/test-hooks.js` reports 0 failed, and `node .claude/hooks/policy.js
--claude-check` stays at 0 FAIL. Out of scope: the ownership of anything under `.claude/`, and the
`update-from-hatchery` skill.
