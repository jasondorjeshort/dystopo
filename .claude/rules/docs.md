---
paths:
  - "docs/**"
  - "CLAUDE.md"
  - ".claude/agreement/**"
  - ".claude/project/**"
---

# Conventions for docs/ and CLAUDE.md

These load when a file under `docs/`, `.claude/agreement/` or `.claude/project/`, or `CLAUDE.md`, is
read or edited. The map of where each topic is documented is the last section of
`docs/ARCHITECTURE.md`; the reasoning is ADR 0020, and ADR 0037 for the base's agreement files and
their one-line replacements. From the Hatchery base, with this project's exemptions.

## Where a piece of text goes

1. A rule every turn: `CLAUDE.md` (under 130 lines; rules and pointers, no long facts; this
   project's own text, ADR 0037: the base's agreement sections are replaced by one-line pointers in
   `.claude/project/`). Only when touching certain paths: `.claude/rules/<name>.md` with `paths:`
   frontmatter.
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
  0020) and `docs/design/proposals/`; the list is `edit.capExempt` in `.claude/hooks/policy.json`,
  which the post-edit hook reads.
- `docs/HANDOFF.md`: at most 40 lines. Detail goes to a finding or the board; the handoff links.
- `CLAUDE.md`: under 130 lines (ADR 0020). The base's 40-line and 190-loaded caps are lifted here by
  `edit.capExempt` naming `CLAUDE.md`; `node .claude/hooks/policy.js --claude-check` still fails when
  an agreement section is neither imported nor replaced (ADR 0037). A new paragraph of facts goes to
  `docs/reference/` with a pointer.
- ADRs and findings: under 150 lines; a longer specification goes in a sibling file.

## Indexes

- A directory under `docs/` (except `images/`) is either indexed (its `README.md` has one line per
  file; adding, renaming, resolving or deleting a file updates that index in the same edit; the
  post-edit hook warns about an unlisted file) or listed on demand (its README carries the marker
  line `Listed on demand: node .claude/hooks/queue-list.js <dir>`, the format and never a file
  name). The marker is the hook's switch: with it, the hook checks that an edited file lists without
  `?` instead of checking an index. Only `docs/ideas/` and `docs/findings/` may carry it (Hatchery's
  ADR 0017; ADR 0036 takes it for the ideas folder).
- `docs/design/decisions/README.md` lists every ADR; `docs/findings/README.md` shows status per file.
- A new directory under `docs/` or a new kind of document gets a line in the last section of
  `docs/ARCHITECTURE.md`.
- An index is the one list a doc may carry. Prose never inventories files, fields, routes or counts
  ("the twelve commands", "a 400-line file"): every ordinary commit moves the figure, and a later
  session then "corrects" it. Write the rule that generates the list instead.

## Frontmatter

- `docs/findings/YYYY-MM-DD-<slug>.md`: required `date`, `status: open | resolved | superseded`,
  `area`; optional `supersedes: <file>`, `promoted_to: <path>`. The status is read by its first word
  (`resolved (ADR 0030)` reads `resolved`). Sections in order: Question, What was tried, Result,
  Next (Next may be omitted when resolved). Use the `finding` skill.
- `docs/ideas/YYYY-MM-DD-<slug>.md`: required `date`, `kind: bug | feature | question | chore`;
  optional `related: [<file>, ...]`; at most one closing block (`Plan:`, `Question:` with a blank
  `Answer:`, or `Waits:`), no `status` and no `pipeline` (ADR 0036; the files written before it are
  converted by T118). Search for an existing idea before adding one (its README, "Before adding").
- `docs/reference/<topic>.md`: required `verified: YYYY-MM-DD`, `source: <url or "observed">`. When a
  fact changes, fix it in place and bump `verified:`; do not append history.
- `docs/design/decisions/NNNN-*.md` and `docs/design/proposals/NNNN-*.md`: no frontmatter; the `Date:`
  and `Status:` lines under the title are the record.

## Personal data (ADR 0018)

- Tracked text (docs, commit messages, tests, fixtures, hook examples) never carries: a home path
  with a user segment in any spelling (drive, root, cygdrive, mnt, or a project-folder slug), an
  email or a person's name other than a listed identity (`privacy.identities`; write the role
  instead: the owner, the coordinator, a downstream project's owner), a host name, a session link,
  a recorded conversation, or a grant file's content.
- Write paths as `~`, `<repo>`, `../<dir>` or `<scratchpad>`. Briefs are not committed and keep
  absolute paths; a tracked file an agent writes from one follows this section.
- A refused push is fixed in the text and folded into the unpushed commit, never lifted; a false
  positive is the owner's (`git.md` of the agreement).

## Content rules

- `HANDOFF.md` has exactly the sections Now, Next, Waiting on user, Open findings, and the first line
  after the title says it is coordinator state. It never contains procedure or rationale; it links.
- An `@` import line in `CLAUDE.md` or `docs/HANDOFF.md` resolves from the importing file's
  directory; a target that does not exist loads nothing and says nothing. The post-edit hook and
  `policy.js --claude-check` resolve every such line.
- Reference files state facts and working commands. Findings state evidence and status. ADRs state
  decisions. Do not mix: a provisional result in a design doc reads as truth later.
- Living docs (skills, hooks, rules, READMEs, reference, the handoff) state the practice, not which
  sibling project it came from; provenance belongs in closed records and commit messages. Hatchery
  itself may be named as the base.
- Public places only: names from `areas.json` and well-known public places; never a personal area,
  its coordinates, nearby place names, lidar project or DEM cell names (`CLAUDE.md`, Rules).
- The code wins. A doc explains intent; it does not define behaviour. Where a doc contradicts the
  code, the doc is stale: fix it in the same change that found the conflict, and bump `verified:`.
- A change that alters behaviour, an invariant or an entry point a doc describes updates that doc in
  the same commit (`CLAUDE.md`, Documentation is part of done).
- Write files that contain apostrophes or backticks with the Write or Edit tool, not a Bash heredoc
  (`docs/reference/windows-and-shells.md`, Shells). The `git-guard` hook reads quoted text and heredoc
  bodies as data, so text that only mentions a git command is not denied unless the command line
  hands it to a shell.
- A path written in a doc names a file that exists; the post-edit hook warns about one that does
  not. A paragraph or list item that must name a path that is gone or not yet there carries
  `[no-check: <reason>]`; closed records (proposals, decisions, findings) are exempt by policy. A
  moved paragraph leaves a pointer behind.
