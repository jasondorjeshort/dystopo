---
paths:
  - "docs/**"
  - "CLAUDE.md"
---

# Conventions for docs/ and CLAUDE.md

These load when a file under `docs/` or `CLAUDE.md` is read or edited. The map of where each topic is
documented is the last section of `docs/ARCHITECTURE.md`; the reasoning is ADR 0020. From the
Hatchery base, with this project's exemptions.

## Where a piece of text goes

1. A rule every turn: `CLAUDE.md` (under 130 lines; rules and pointers, no long facts). Only when
   touching certain paths: `.claude/rules/<name>.md` with `paths:` frontmatter.
2. A procedure (steps to do X): a skill under `.claude/skills/`.
3. A decision about what we build or why: an ADR in `docs/design/decisions/` (`adr` skill); a major
   choice goes through a proposal and a critic first (`proposal` skill). Design detail and
   measurements: the matching section of `docs/PLANNING.md`, which points at the ADR.
4. A fact about a tool, command, route, platform or this machine that stays true: `docs/reference/`.
5. The result of an investigation (a root cause, a measurement, a failed approach): `docs/findings/`
   (`finding` skill). When resolved, promote the durable part to `docs/reference/` or an ADR and set
   `promoted_to`.
   A raw idea, bug or missing feature not yet triaged: `docs/ideas/` (one file each, see its README).
6. What is happening now or next: `docs/HANDOFF.md` (`handoff` skill). Tasks: `docs/TASKS.md`.
   What a merged task did: the dated log in `docs/PLANNING.md` (the coordinator, `tools/merge-log.py`).

## Size caps (the post-edit hook warns)

- Any `docs/**/*.md`: at most 300 lines. Split by topic, never by date, never by "part 2". Exempt:
  `docs/PLANNING.md`, `docs/TASKS.md`, `docs/SERVER.md` (dated records and the server manual, ADR
  0020) and `docs/design/proposals/`.
- `docs/HANDOFF.md`: at most 40 lines. Detail goes to a finding or the board; the handoff links.
- `CLAUDE.md`: under 130 lines. A new paragraph of facts goes to `docs/reference/` with a pointer.
- ADRs and findings: under 150 lines; a longer specification goes in a sibling file.

## Indexes

- Every directory under `docs/` except `images/` has a `README.md` with one line per file. Adding,
  renaming, resolving or deleting a file updates that index in the same edit. The post-edit hook warns
  about an unlisted file.
- `docs/design/decisions/README.md` lists every ADR; `docs/findings/README.md` shows status per file.
- A new directory under `docs/` or a new kind of document gets a line in the last section of
  `docs/ARCHITECTURE.md`.

## Frontmatter

- `docs/findings/YYYY-MM-DD-<slug>.md`: required `date`, `status: open | resolved | superseded`,
  `area`; optional `supersedes: <file>`, `promoted_to: <path>`. Sections in order: Question, What was
  tried, Result, Next (Next may be omitted when resolved).
- `docs/ideas/YYYY-MM-DD-<slug>.md`: required `date`, `kind: bug | feature | question | chore`,
  `status: new | planned | done | dropped`, `pipeline` (a `T##` or `none`); optional `related`,
  `merged_into`. Search for an existing idea before adding one (its README, "Before adding").
- `docs/reference/<topic>.md`: required `verified: YYYY-MM-DD`, `source: <url or "observed">`. When a
  fact changes, fix it in place and bump `verified:`; do not append history.
- `docs/design/decisions/NNNN-*.md` and `docs/design/proposals/NNNN-*.md`: no frontmatter; the `Date:`
  and `Status:` lines under the title are the record.

## Content rules

- `HANDOFF.md` has exactly the sections Now, Next, Waiting on user, Open findings, and the first line
  after the title says it is coordinator state. It never contains procedure or rationale; it links.
- Reference files state facts and working commands. Findings state evidence and status. ADRs state
  decisions. Do not mix: a provisional result in a design doc reads as truth later.
- Public places only: names from `areas.json` and well-known public places; never a personal area,
  its coordinates, nearby place names, lidar project or DEM cell names (`CLAUDE.md`, Rules).
- Write files that contain apostrophes or backticks with the Write or Edit tool, not a Bash heredoc.
  The same goes for any text that mentions a destructive git command: the `git-guard` hook matches the
  command text, so a heredoc containing a force push is denied even though nothing runs it.
- A path written in a doc names a file that exists; a moved paragraph leaves a pointer behind.
