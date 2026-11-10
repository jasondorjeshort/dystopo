# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-09, by main session (wave 31 open: sixteen of seventeen merged; the mid-wave sweep running; T256 and T257 wait for the release)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` › Wave 31, `out/wave31-research/` [no-check: gitignored].

## Now

- **Wave 31 is open** (plan at e184b663; T261 added): seventeen tasks, two lanes plus three alongside. Merged: all
  but T256 and T257 (T259, T245, T244, T248, T246, T247, T249, T261, T250, T251, T252, T253, T254, T260, T255, T258).
  Running: the mid-wave sweep on 63325aea (`tools/sweep.ps1`, consoles 5640 and 5642; no `-Prev`: the last
  release's sweep folder is on the second PC); both lanes idle for it. The lane order and the release point are the
  wave paragraph's; within a lane: implementer, verifier (ports 5574/8698 for A, 5576/8700 for B), merge, next.
- Rules learned: no force push works here (merge `main` in, never rebase a pushed branch); a push on a line of its
  own; `git worktree remove` fails with "Filename too long", so cleanup chores get the `\\?\` delete.
- **Gate:** the 5-hour meter hit 50 at 17:04; the owner chose no grant; the lanes resumed at the 19:40 reset (7-day
  32) and again at the 00:40 reset after a second hold (7-day 49 at 02:30). Two Gradle agents at once cost one
  Kotlin compiler OOM per `check` (green on retry) and tour screenshot timeouts.

## Next

1. Sweep green: the `release` skill for v0.1.25-alpha on the swept commit (CHANGELOG cut, tag, draft, an agent verifies
   and installs from the draft, notes, publish, record); red: a fix task or one rerun of the part. Then T256 in A and
   T257 in B (Opus), verifiers, merges, chores; the close (post-wave sweep, a second release, `docs/tasks/`, wave 32).
2. At T253 and T255 merged: the `sweep` skill, then the `release` skill (ADR 0024). At the wave's end: close by the
   `wave` skill (the meter against 53; the figure revised if five points off), then plan wave 32 from the wave
   paragraph's Not-taken list and the build orders in ADR 0060 and ADR 0061.

## Waiting on user

- ADR 0060: the refresh default (automatic, your "just work", against ADR 0030's explicit refresh); the helper's
  keep-warm list as a new data flow; the daily limit's default, and country downloads past OpenFreeMap's limit.
- ADR 0061: the first-run card while the pack list loads (F1 wait, F2 unknown until known, F3, F4); F1 built first.
- The Menu choice (Layout C, wave 32); T220; proposals 0054 and 0055; on v0.1.24-alpha whether the start stays
  quick over the large download, a Startup freeze report if not.

## Open findings

- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; the snapshot loop is wave 32's.
- `docs/findings/2026-11-10-range-cache-keeps-blocks-across-a-republish.md`: 56 stale cells deleted; a validator is wave 32's.
