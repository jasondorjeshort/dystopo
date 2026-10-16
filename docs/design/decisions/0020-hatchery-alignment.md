# 0020. Hatchery alignment: what this project takes from the base and what stays its own

Date: 2026-10-12
Status: accepted
Hatchery base: c50b0da1b0747ccc65147b2862ec596d818bc2c4 (living line, bumped by update-from-hatchery; last 2026-10-16, ADR 0035)

Record: task T84 (`docs/TASKS.md`); the 2026-10-11 log entry "process base and what differs
(Hatchery)" in `docs/PLANNING.md`; Hatchery read at commit `0ce6670`
(`~\Documents\src\hatchery`, the owner's base repository,
https://github.com/jasondorjeshort/hatchery).

## Context

Hatchery is a base repository distilled from the way Dystopo has been built (waves of owned tasks,
agents doing all the coding, a debug interface for every feature, usage gating). Dystopo was not
decanted from it and has its own history, so the practice comes in as files, the way Hatchery's
ADR 0004 describes for adopted projects, but by hand in one task rather than through the
`adopt-hatchery` skill, because Dystopo is in the build phase with eighty-odd merged tasks and a
`CLAUDE.md` that had grown to 175 lines of very long paragraphs (about 60,000 characters).

A comparison on 2026-10-12 found these Hatchery items missing here: the proposal, critic and ADR
loop; the `git-guard` hook; the brief template; `.claude/rules/` with doc caps and indexes; a
handoff wrap-up procedure; user sign-off on human-facing decisions. Dystopo already had
`docs/HANDOFF.md` imported into `CLAUDE.md` and its own usage gate (75%, from the base's script).

## Decision

### Adopted

| From Hatchery | Here | Notes |
|---|---|---|
| `CLAUDE.md` as rules plus pointers | `CLAUDE.md`, under 130 lines | Facts moved to `docs/reference/`, procedures to skills, path-scoped conventions to `.claude/rules/`; the PR of T84 lists every moved paragraph and its new home |
| `docs/reference/`, one topic per file with `verified:` | `docs/reference/` | Emulator and device setup, Windows and shell quirks, the live server, this account's paths, the debug routes |
| `docs/findings/`, dated and status-tagged | `docs/findings/` | Investigation results that were sentences in `CLAUDE.md` and open findings that were prose in the handoff |
| `docs/design/decisions/` and the `adr` skill | this directory | The decisions already made in prose get ADRs 0022 to 0028 |
| Proposal, critic, ADR loop (`proposal` skill, `brief-critic.md`) | `.claude/skills/proposal/` | Proposals go in `docs/design/proposals/`, created with the first one |
| `git-guard` and `post-edit` hooks, `test-hooks.js` | `.claude/hooks/` | With the exceptions in ADR 0021 |
| `.claude/rules/docs.md` | `.claude/rules/docs.md` | Caps and indexes, with the exemptions below |
| A rule file per language once code exists | `.claude/rules/kotlin.md`, `trackdb.md` | The code conventions that were in `CLAUDE.md` |
| `brief` skill | `.claude/skills/brief/` | Rewritten as the template for task briefs (implementer, verifier, chore) |
| `handoff` skill and the wrap-up rule | `.claude/skills/handoff/`, `CLAUDE.md` | The handoff is rewritten at the end of every coordinator session and before any stop |
| `finding` skill | `.claude/skills/finding/` | |
| User sign-off on what humans see and touch | `CLAUDE.md` | Technical decisions are the coordinator's after the critic; front end, flows, wording and visuals wait for the owner |

New skills that hold Dystopo procedures which were paragraphs of `CLAUDE.md`: `wave` (launch,
verify, merge sequence, resume), `release` (publishing), `sweep` (post-wave sweep). Hatchery's
proposal 0003 recommends the first; its backlog lists the other two.

### Kept as they are, and why

- **`docs/TASKS.md`** is the board: every task's Goal, **Owns:** line and Done-when, plus its
  **Status:** line after the merge. Owns lines are what keep five to eight parallel implementers out
  of each other's files; Hatchery has no board (its proposal 0003 adds one). `tools/merge-log.py`
  writes the Status line.
- **`docs/PLANNING.md`** is the design record and the dated development log ("Phase 0 log") that every
  merge appends to with its measurements and follow-ups. Splitting it would break
  `tools/merge-log.py`, the links into it from code, `CLAUDE.md`, `docs/SERVER.md` and PR bodies, and
  the habit of reading the log tail to resume. Its design sections now point at the ADRs that record
  their decisions; new design that is a decision goes through an ADR.
- **`CHANGELOG.md`** exists because releases are published: release notes are written from its
  section (ADR 0024), and `merge-log.py` adds one line per merged task.
- **`docs/SERVER.md`** is the server's manual, linked from the KDoc of the route files and from the
  README.

These four are exempt from the 300-line cap (`.claude/rules/docs.md`, `post-edit.js`
`CAP_EXEMPT`); every other file under `docs/` is capped.

### Not adopted, with the reason (sent back to Hatchery where it is a base question)

- **`.claude/agents/` with a pinned model per role** (architect, critic, coder, tester, runner,
  pusher). Here the model is set on every launch by kind of work (`CLAUDE.md`, Coordinating a wave),
  and the roles are implementer, verifier and chore; Hatchery's proposal 0003 adds a `verify` agent.
- **Only the runner commits, only the pusher pushes.** Implementers commit and push their own
  `task/*` branch (ADR 0021; Hatchery proposal 0003, item 2).
- **Usage gate at 50% and 80%.** Here 75%, with in-flight verification and merges allowed past it
  (owner, 2026-10-10; `.claude/hooks/usage-gate.js`).
- **`docs/backlog.md`.** Later work is the "Later" list and the follow-ups on the board.
- **`docs/README.md` as the map.** `docs/ARCHITECTURE.md` is the map here (its last section indexes
  every place a topic is documented); a second map would drift.
- **`docs/design/00-06` design documents.** The design lives in `docs/PLANNING.md` sections and, from
  now on, in ADRs.
- **SPDX headers on every source file** and Hatchery's licence text. Dystopo's licence rules are its
  own (`docs/PLANNING.md` › Licensing); the hook scripts copied from Hatchery keep their SPDX line.
- **The status line script.** This machine's status line is per user (`~/.claude/`), and writes
  `~/.claude/usage.json`, which the gate reads.

### ADR numbering

This project's ADRs start at 0020 and leave 0001 to 0019 free, so Hatchery's own ADRs (0001
licensing, 0002 layout, 0004 adoption) can be copied here later without renumbering (the candidate
fix in Hatchery's backlog for colliding numbers). Proposals take the number of the ADR they become.

### Code from the base

The hook scripts are copied from Hatchery and changed here. Both repositories have the same owner,
so the rule against copying code from other projects (`CLAUDE.md`, Rules) does not apply; they are
build-side tooling, never shipped.

## Consequences

- `CLAUDE.md` loads every turn at a fraction of its old size; the facts an agent needs are one
  pointer away, and a reference file says when it was last verified.
- Procedures (publishing, sweeps, the merge sequence) are loaded only when a skill is invoked, so a
  coordinator must load the skill rather than rely on memory; the skill names are in `CLAUDE.md`.
- Two places can now hold a design decision (PLANNING and an ADR). The rule: a decision gets an
  ADR; PLANNING keeps the measurements and the log and points at the ADR.
- Supersede this ADR when Dystopo adopts the base through `adopt-hatchery` or Hatchery's proposal
  0003 is decided in a way that changes the board or the git rules.
