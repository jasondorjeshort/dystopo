# 0008. ADR numbers, and what a decant keeps

Date: 2026-10-14
Status: superseded by 0016
Superseded by [0016](0016-base-owned-and-project-owned-files.md), 2026-10-17: Hatchery's records
ship in the base-owned subtree `docs/hatchery/`, Hatchery numbers there from `0016` up with no
ceiling and a project numbers its own `docs/design/decisions/` from `0001`; merging ends, so the
three merge-mode exceptions below are moot. The Consequences' trigger (Hatchery's next number would
be `0020`) had not fired; the supersession came first, with three numbers left.
Amended by [0009](0009-adr-numbers-when-both-tests-pass.md) for a tree that passes both numbering tests.

Record: no proposal; owner decision of 2026-10-14 against a real case, as ADR 0004 was. The case:
the base was being readied to decant a new project, and the decant as written (ADR 0002,
Consequences; the `decant` skill) kept ADRs 0001 and 0002 only, while `CLAUDE.md`, the hooks' deny
texts, `.claude/hooks/policy.json` and the `update-from-hatchery` skill cite ADRs 0004, 0006 and 0007
by number and path. The project would have started with a working agreement that cites records it
does not hold. This record resolves the deferral in ADR 0004 (ADR numbering collision) and the idea
file `2026-10-13-adr-number-collision-reserved-range.md`, deleted with it.

## Context

- A decanted project shares history with Hatchery, and `update-from-hatchery` merge mode brings
  every file Hatchery adds, ADRs included: Hatchery's next ADR lands in the project whether or not
  the project kept the earlier ones. If the project has already used that number, the merge is an
  add/add conflict on a record, and two decisions then carry one number in one tree. An adopted
  project (ADR 0004) is different: adoption copies 0001, 0002 and the hook specs once, and after
  that nothing arrives as an ADR, since patch mode applies `.claude/` and `CLAUDE.md` only.
- The ADRs are the record of the practice the project inherits. The living docs cite them (0002 is
  the layout, 0006 the hook policy, 0007 the queues), the hook deny messages point at them, and the
  no-project-names rule in `.claude/rules/docs.md` relies on the closed record carrying the
  provenance. A project that deleted them would be left to re-derive each rule from the text that
  cites it.
- `docs/reference/claude-code-on-windows.md` describes the machine and the tool (what Claude Code,
  Git and the shells do on Windows), not Hatchery, and `.claude/rules/docs.md` points into it.

The candidate fix named in ADR 0004 (a reserved range) was chosen over a project prefix in the file
name: the file name is what every link and every `NNNN-` sort uses, and a range needs no
coordination between Hatchery and any number of projects.

## Decision

### Numbers

Hatchery's ADRs and proposals use `0001` to `0019`. A decanted project's first ADR is `0020`, or
the next free number above it; a proposal takes the number of the ADR it will become, so the same
range covers both. An adopted project keeps its own numbering: its adoption ADR takes the next free
number in its tree (ADR 0004), and patch mode brings no ADR. The rule lives in the `adr` skill,
which is where every ADR is numbered and which says how to tell the three cases apart.

### What a decant keeps

- `docs/design/decisions/`: every file, including `decisions/README.md`, and the "Decisions so far"
  list in `docs/design/README.md` as it stands.
- `docs/design/proposals/`: `proposals/README.md` only. A proposal is the alternatives argued for
  Hatchery; its result is the ADR.
- `docs/reference/claude-code-on-windows.md` with its README row. Every other reference file, and
  every finding, idea and operator entry, is deleted: they are Hatchery's own investigations and
  queues. The test for a reference file is whether it states facts about the environment a project
  will run in rather than about Hatchery; today one file passes it, and the `decant` skill names it.

In the decanted project the kept ADRs are Hatchery's records, and on a merge-mode update the base
wins for `0001` to `0019` with three exceptions that are the project's own lines, merged by hand:
a `Status: superseded by NNNN` line and its pointer, written when the project supersedes one of
them from `0020` up (a different license supersedes 0001, it does not edit it); rows the project
added to a table the ADR declares living (ADR 0001's components table); and the "Decisions so far"
list in `docs/design/README.md`, where both sides append at the same spot and the merge takes both
sides' lines.

Amends ADR 0002 where its Consequences say a decant keeps ADRs 0001 and 0002. ADR 0004's skeleton
table (adoption copies 0001, 0002 and the hook specs only) is not changed here.

Deferred: whether adoption should also copy every Hatchery ADR, since an adopted `CLAUDE.md` cites
0006 and 0007 the same way; trigger: the next adoption, which decides it against that project.
Deferred: a post-edit warning when a project writes an ADR below `0020`; trigger: the first number
written in the reserved range by a project.

## Consequences

- A decanted project reads Hatchery's decisions as the record of what it inherited, and its own
  start at `0020` and sort after them. Merge-mode updates bring new Hatchery ADRs without conflict.
- The range is finite. When Hatchery's next number would be `0020`, supersede this record with a
  wider range for the base and a higher start for projects, and say which existing project numbers
  it collides with.
- The Windows reference goes out with every decant: a living doc under the no-project-names rule.
- A decant keeps more than it did: a new project's first reader meets Hatchery's records first.
