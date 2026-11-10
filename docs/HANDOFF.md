# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-10, by main session (wave 31: every task merged; the post-wave sweep running; the close and v0.1.26-alpha next)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` › Wave 31, `out/wave31-research/` [no-check: gitignored].

## Now

- **Wave 31 is merged** (plan at e184b663; T261 added): every task in, the last two today (T256 at e08e9c2b, T257 at
  7ed8f5c3 after one fix round). **v0.1.25-alpha published** 2026-11-10 09:48 UTC (tag on 947d1e71, versionCode
  1421, sixteen tasks). The README status paragraph reads the close.
- Running since 06:50: the post-wave sweep (Sonnet; `tools/sweep.ps1 -NoDaemon -Prev out/sweep-20261110-0254
  -First route-card,track,map,readout,settings`, consoles 5640 and 5642; one to two hours) and T257's cleanup chore.
  Notes for v0.1.26-alpha drafted in the session scratchpad (`notes-v0.1.26-alpha.md`; the changes line and the
  checksums to fill); the sweep's `changes.png` goes on the draft as the release skill says (step 4).
- Rules learned: no force push works here (merge `main` in, never rebase a pushed branch); a push on a line of its
  own; `git worktree remove` fails with "Filename too long", so cleanup chores get the `\\?\` delete; a
  deterministic LZW or Deflate error in a cached DEM cell is a stale range cache: delete that cell's directory. An
  empty `../dystopo-wt/T256-verify` is held open by a finished agent's shell; delete it when it frees.
- **Gate:** the 5-hour meter hit 50 twice on 2026-11-09/10; the owner chose no grant; the lanes resumed at the
  resets. 5-hour 3, 7-day 52 at 06:57 (the allowance said about 53 at the close; one release to go).

## Next

1. Sweep green: the `release` skill for v0.1.26-alpha (CHANGELOG cut, tag on main, draft, an agent verifies and
   installs from the draft with `upgrade` from v0.1.25-alpha, `changes.png` attached, notes, publish, record); red:
   a fix task or one rerun of the part. Then the close by the `wave` skill: the meter at the close into the wave
   paragraph's allowance sentence (revise the per-task figure only if five points off), the handoff, a Haiku chore
   moving wave 31 to `docs/tasks/wave-31.md` [no-check: not yet written] by the recipe in `docs/tasks/README.md`.
2. Plan wave 32 (written and criticised, launched on the owner's word) from the wave 31 paragraph's Not-taken list,
   ADR 0060's build order (the move, then cells with S1 and the refresh), ADR 0061's (the stage list first), this
   wave's Later follow-ups (the range cache's validator, the clipped Record-as picker and the hidden bar in
   landscape at 2.0, the privacy scanner's timestamp false positives, the Extended row's chips, the camera fit
   across 180°), and `docs/ideas/` (ideas of merged tasks were not all deleted on their branches: triage first).

## Waiting on user

- ADR 0060: the refresh default (automatic, your "just work", against ADR 0030's explicit refresh); the helper's
  keep-warm list as a new data flow; the daily limit's default, and country downloads past OpenFreeMap's limit.
- ADR 0061: the first-run card while the pack list loads (F1 wait, F2 unknown until known, F3, F4); F1 built first.
- The Menu choice (Layout C, wave 32); T220; proposals 0054 and 0055; v0.1.25-alpha's start over the large
  download; the owner's-eye items of the seventeen merges (ADR 0031, the board's Status lines).

## Open findings

- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; the snapshot loop is wave 32's.
- `docs/findings/2026-11-10-range-cache-keeps-blocks-across-a-republish.md`: 56 stale cells deleted; a validator is wave 32's.
