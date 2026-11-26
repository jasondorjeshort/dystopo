# 0022. A session-meter HOLD is waited out

Date: 2026-10-29
Status: accepted, superseded in part by 0025
Superseded in part by [0025](0025-wave-lead.md), 2026-11-23: the session-meter HOLD row gains a sentence. A
`wave-lead` in flight goes on to its meter's finish line where the policy sets one; the handoff is still
committed first, by pathspec, and while a led change is open the wrap-up commit takes none of that change's
paths. Everything else stands.
Withdrawn by [0027](0027-wave-lead-withdrawn.md), 2026-11-24: the HOLD row as before.
Supersedes in part ADR 0006 (the HOLD behaviour of the gating rule) and ADR 0011 (its HOLD row's
report-and-hand-over).

## Context

The owner decided on 2026-10-29 that a HOLD on the five-hour session meter alone is waited out,
not stopped at. The weekly meter's HOLD and the STOP line keep their behaviour.

- The session window resets within five hours; the weekly one is days away.
- The gate already reads a window whose `resets_at` has passed as reset, so after the reset the
  hook allows launches again with no change.
- The wait costs one scheduled wake-up. The handoff and commit come first, so a session that dies
  while waiting loses nothing.
- After a wait longer than `usage.staleSeconds` the gate's stale branch runs before the reset
  check. The base (`whenStale: allow`) then allows with a note; a project with `whenStale: deny`
  waits for the status line to write fresh data after the first response.

## Decision

In `.claude/agreement/usage-gating.md` the HOLD row splits in two. On HOLD of the session meter,
with the weekly meter below HOLD or its HOLD lifted by an override: no new subagents or tasks; let
in-flight agents finish, run the `handoff` skill, commit from the main session; report to the owner
first, quoting the gate's deny line with the pool advice (ADR 0011), so the owner can switch
accounts instead of waiting; schedule one one-shot `CronCreate` two minutes after
`five_hour.resets_at` (a wake-up on the exact minute can fire early and be refused); do nothing
until it fires; on waking run `/usage` and resume from the handoff. A session without a scheduler
stops as the weekly row says. The override grant (ADR 0006) lifts the HOLD without waiting. The
weekly-meter HOLD row keeps the old text.

## Consequences

- The hook is unchanged; its reset check already covers the case.
- The mechanism is untested until the first real session HOLD.
- A session that waits holds its context and may hit the stale branch above on waking.
