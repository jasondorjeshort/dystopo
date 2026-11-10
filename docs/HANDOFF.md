# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-09, by main session (wave 31 open: eight of seventeen merged; T250 and T251 in the lanes)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` › Wave 31, `out/wave31-research/` [no-check: gitignored].

## Now

- **Wave 31 is open** (plan at e184b663; T261 added): seventeen tasks, two lanes plus three alongside. Merged:
  T259, T245 (ADR 0061), T244 (ADR 0060), T248, T246 (the verifier script, used since), T247, T249, T261 (the
  cards step, which guards the release). Built and being verified: T250 (lane A), T251 (lane B).
  The lane order and the release point are the wave paragraph's; within a lane: implementer, verifier (ports
  5574/8698 for A, 5576/8700 for B), merge, then the next implementer.
- Rules learned: no force push works here (merge `main` in, never rebase a pushed branch); a push on a line of its
  own; `git worktree remove` fails with "Filename too long", so cleanup chores get the `\\?\` delete.
- **Gate:** the 5-hour meter hit 50 at 17:04; the owner chose no grant, and at the 19:40 reset (5-hour 0, 7-day 32)
  both verifiers launched: T250 (e16d8772, console 5574) and T251 (5a151b4a, console 5576). The two-branch crossing
  (a flying recording in `GET /readout` and `GET /tracks` together) goes to T252's verifier. Live server up on 8765.

## Next

1. As each verifier reports MERGE: merge by the `wave` skill's sequence (bodies drafted and scanned in the
   scratchpad as `merge-T250.txt` and `merge-T251.txt`), a Sonnet cleanup chore, then the lane's next implementer
   (T252 in A, whose brief lists T251's hunk in `app/TrackRoutes.kt` and gives its verifier the crossing; T254 in B,
   `MapController.kt` out of its Owns); `docs/reference/verifying-a-task.md` and the push on its own line in every brief.
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
