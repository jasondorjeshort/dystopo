# 0009. ADR numbers when both tests pass

Date: 2026-10-15
Status: accepted, superseded in part by 0016
Superseded in part by 0016 (`0016-base-owned-and-project-owned-files.md`), 2026-10-17: the
numbering tie-break is moot (no range, no `0020` start; every tree numbers its own directory), and
the `git show hatchery/main:` read of a record a project does not carry is replaced (every project
carries `docs/hatchery/`). The line winning over `merge-base`, and the join as a project's own
departure, stand.

Record: no proposal; a downstream project met the case and settled it for itself; this carries the
rule into the base. Amends ADR 0008, Numbers, for the case below; the rest of 0008 stands.
Amended by [0010](0010-update-mode-when-both-tests-pass.md): the update procedure, put out of
scope below, now takes the same tie-break.

## Context

ADR 0008 and the `adr` skill tell three trees apart: Hatchery itself (no `hatchery` remote), a
decanted project (`git merge-base HEAD hatchery/main` succeeds) and an adopted one (a
line matching `^Hatchery base: [0-9a-f]{7,40}` exists under `docs/design/decisions/`, the same
line `update-from-hatchery` step 3 and ADR 0004 look for; the phrase in prose does not count,
since a decant keeps ADRs that quote it). A decanted project starts at `0020`; an adopted one
keeps its own numbering.

A project that adopted Hatchery and then joined its history to the base passes both tests. That
join is not an ADR 0004 procedure (ADR 0004 rejected joining histories; `adopt-hatchery` forbids
it and patch mode never does it); the case arises only when a project joined them against that
rule. `merge-base` succeeds, which says "decanted", and the base line exists, which says
"adopted". The skill gave no answer. Taking "decanted" would renumber nothing but would start a
tree that already holds ADRs in `0001` to `0019` at `0020`; taking "adopted" keeps the numbers it
has.

## Decision

- When both tests pass, the anchored `Hatchery base: <sha>` line wins: the project numbers as an
  adopted project. Its next ADR is the next free number in its own `decisions/`, proposals included,
  and nothing is renumbered. The "starts at `0020`" rule does not apply to it.
- Why the line and not the merge: the line records how the project took the practice (files copied
  once, patches after); the join merge only keeps `merge-base` current for the next diff and says
  nothing about which numbers the project has used.
- The `adr` skill, step 1, states the tie-break. A decanted project, which has no such line, is
  unchanged.
- Base ADR numbers cited inside `.claude/` mean Hatchery's record, whichever numbering the project
  uses; a project that does not carry the file reads it with
  `git show hatchery/main:docs/design/decisions/<file>`. Out of scope: any change to the update
  procedure itself.

## Consequences

- An adopted project may hold numbers in `0001` to `0019` that are its own, so a base ADR number
  cited in `.claude/` can name a different file in the project's tree. Citing by title or path
  along with the number avoids the confusion.
- The `Hatchery base:` line becomes load-bearing for numbering as well as for updates; a project
  that deletes it falls back to the `merge-base` test.
- Supersede this record if Hatchery widens its range (ADR 0008, Consequences) or changes how
  projects take updates.
