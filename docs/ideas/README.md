# Ideas

One file per idea: something that would be better than what is here now, with a concrete anchor.
Not a task — nothing here is scheduled or decided. Not a finding — an investigation with a result
goes to `../findings/`. A bug that nobody has looked into yet is an idea of `kind: bug`; once it
has a root cause it becomes a finding and this file is deleted in the same edit. An idea with no
anchor (a file, a route, a command, a screen) does not earn a file: "the UI could be faster" is a
mood, and a directory of moods is worth nothing in six months.

## Shape

Files are named `YYYY-MM-DD-<slug>.md`, the date being when the idea was recorded, so a plain
listing shows how long each has sat. The slug states the claim in a few words, as a findings slug
does. Frontmatter:

```
---
date: 2026-10-13
kind: bug | feature | question | chore
related: [<file>, ...]        # optional: overlapping ideas that stay separate
---
```

Body: a title, then what you saw or want and why, in a few lines, with the anchor named in path
form; steps to reproduce for a bug; gaps under `## Open`. Short — brainstorming that settles into
a real choice becomes a proposal, not a longer idea.

Public places only (names from `areas.json`); never a personal area, its coordinates or nearby
names (`CLAUDE.md`, Rules).

The file ends in at most one block, which is how a reader tells one that is ready from one that is
waiting or has not been read yet:

- `Plan:` — the work is settled and fits one pass; the block is the brief it will be built from.
- `Question:` with a blank `Answer:` — a choice only the owner can make (whether they want it, which
  of two shapes, whether it is big enough to become a design doc). The owner fills in `Answer:`
  whenever they get to it; nothing waits on them.
- `Waits:` — the owner's own deferral to a trigger it names (a release, a platform change). No pass
  takes it before then, and no agent writes one on its own judgement.

A file with no block is untriaged and nothing builds from it. The `triage` skill reads each
untriaged file against its anchor and writes one block. The owner may write a block at filing time.

## Before adding: look for overlap

Duplicates are expected, so a new idea starts with a search, not a file: `grep -ril` the keywords
across this directory, `../findings/` and `../HANDOFF.md`. Same idea as an existing file: add the
new detail to that file as a dated line. Overlapping but distinct: create the file and link both
ways with `related:`, saying in one line how they differ. A duplicate that slipped in is folded into
the older file and deleted; git has it.

**Record an idea:** is how the owner files one from conversation. Write the entry from their words
rather than transcribing them — find the anchor, say what it would take — and ask only when the
anchor is genuinely unclear.

## What drains it, and on what terms

A pass builds only a `Plan:` or an answered `Question:`, oldest first, and deletes the file in the
commit that lands the work. A file is deleted for one other reason only: the code already has what
it asks for, which is a fact about the tree rather than a verdict on the idea.

No pass deletes an idea because it judged the idea not worth building. This directory is where the
owner decides what the project does not do, and a loop that pruned by its own taste would make that
decision by attrition. "Not worth building" is a `Question:` with a recommendation attached, and the
owner's no is the deletion. An idea that sits here for months is at rest, not stalled. When the
listing outgrows a screen, the owner reads the whole of it in one sitting, answers what is asked and
deletes what they do not want; no agent runs that sitting.

## Listing

No file here is listed by hand: kind and block are read from the file itself. `--untriaged` shows
the files with no block; a file that lists as `?` names the field to fix on stderr.

Listed on demand: node .claude/hooks/queue-list.js ideas
