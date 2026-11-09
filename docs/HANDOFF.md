# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-09, by main session (wave 31 open: eight of seventeen merged; T250 and T251 in the lanes)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` › Wave 31, `out/wave31-research/` [no-check: gitignored].

## Now

- **Wave 31 is open** (plan at e184b663; T261 added): seventeen tasks, two lanes plus three alongside. Merged:
  T259, T245 (ADR 0061), T244 (ADR 0060), T248, T246 (the verifier script, used since), T247, T249, T261 (the
  cards step, which guards the release). Running: T250 (lane A, console 5570), T251 (lane B, console 5572).
  The lane order and the release point are the wave paragraph's; within a lane: implementer, verifier (ports
  5574/8698 for A, 5576/8700 for B), merge, then the next implementer.
- Rules learned: no force push works here, so nobody rebases a pushed branch (merge `main` in); a push runs on
  a line of its own; `git worktree remove` fails with "Filename too long", so cleanup chores get the `\\?\` delete.
- The live server on 8765 is up (PID in `out/coordinator-pids.txt` [no-check: gitignored]). Free RAM 86 to 91 GB.

## Next

1. As T246 or T248 reports: a Sonnet-class verifier (`brief` skill, `verifier.md`; once T246 has merged, its
   script and its reference page go in every verifier brief [no-check: T246 writes the page]); merge by the `wave`
   skill's sequence; then the lane's next implementer (T247 in A, T249 in B), whose brief lists what merged. Every
   brief says: the push on a line of its own (the guard denies a push chained after another git command; two
   agents read that as a refused force-push and merged instead).
2. At T253 and T255 merged: the `sweep` skill, then the `release` skill (ADR 0024). At the wave's end: close by the
   `wave` skill (the meter against 53; the figure revised if five points off), then plan wave 32 from the wave
   paragraph's Not-taken list and the build orders in ADR 0060 and ADR 0061.

## Waiting on user

- ADR 0060: the refresh default (automatic, your "just work", against ADR 0030's explicit refresh); the helper's
  keep-warm list as a new data flow; the daily limit's default, and country downloads past OpenFreeMap's limit.
- ADR 0061: the first-run card while the pack list loads (F1 wait, F2 unknown until known, F3, F4); F1 built first.
- `git branch -D task/T245` (the board's git line); the Menu choice (Layout C, wave 32); T220; proposals 0054 and
  0055; on v0.1.24-alpha whether the start stays quick over the large download, a Startup freeze report if not.

## Open findings

- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; the snapshot loop is wave 32's.
- `docs/findings/2026-11-07-update-check-shows-githubs-raw-504.md`: T247 takes it this wave.
