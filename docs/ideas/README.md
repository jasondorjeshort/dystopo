# Ideas

Raw ideas, bugs and missing features recorded for later review, one file per idea. An idea is not a
task: nothing here is scheduled or decided. The coordinator triages the folder when planning a wave
(see Triage) and moves each idea to a task, a finding, a proposal or the bin.

Files are named `YYYY-MM-DD-<slug>.md` and carry frontmatter:

```
---
date: 2026-10-13
kind: bug | feature | question | chore
status: new | planned | done | dropped
pipeline: T## or "none"     # the TASKS.md entry that covers it, if any
---
```

Body: a title, then what you saw or want, and why, in a few lines. Add steps to reproduce for a bug.
Keep it short; a long investigation becomes a finding (`docs/findings/`), a major choice a proposal.

## Triage

At wave planning, for each `new` idea: set `pipeline:` if a task already covers it (`planned`), or
write a `docs/TASKS.md` entry and link it (`planned`), or set `dropped` with a one-line reason under
the body so it does not come back. When the covering task is merged, set `done`. Update the table in
the same edit.

Public places only (names from `areas.json`); never a personal area, its coordinates or nearby
names (`CLAUDE.md`, Rules).

## Index

| File | Kind | Status | One line |
|---|---|---|---|
