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

Optional frontmatter: `related: [<file>, ...]` for overlapping ideas that stay separate, and
`merged_into: <file>` on a folded duplicate (with `status: dropped`).

Body: a title, then what you saw or want, and why, in a few lines. Add steps to reproduce for a bug.
Keep it short; a long investigation becomes a finding (`docs/findings/`), a major choice a proposal.
A vague idea is fine: write what is known and put the gaps under `## Open` for implementation time.
Brainstorming goes under `## Brainstorm` in the same file (options, trade-offs, questions), dated
and short; when it settles into a choice, it becomes a proposal or a task, not a longer idea.

## Before adding: look for overlap

Duplicates are expected, so every new idea starts with a search, not a new file:

1. `grep -ril` the keywords across `docs/ideas/`, `docs/TASKS.md` (including its Follow-ups lines),
   `docs/findings/` and `docs/HANDOFF.md`.
2. Same idea as an existing file: add the new wording or detail to that file (a dated line) and do
   not create another.
3. Overlapping but distinct (shared cause, shared code, one is a subset): create the file and link
   both ways with `related:`; say in one line how they differ.
4. Already covered by a task or finding: still record it if it adds something, with `pipeline:` set;
   otherwise tell the owner it is covered and add nothing.
5. A later duplicate that slipped in: fold it into the older file, set `merged_into:` and
   `status: dropped` on it (or delete it if nothing else links to it), and fix the index.

Ideas that touch the same files are also a signal for the coordinator to put them in one task.

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
