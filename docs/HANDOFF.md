# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-14, at commit 91bd3cd, by main session (second account, Fable 5.1 from 15:40 UTC)
Resume: this file, `docs/reference/machines.md` › The other account (`second`: checkout
`~\Documents\src\Dystopo`, release key in its `tools\`, no `devtool.local.json`), the
`wave`, `sweep` and `release` skills, and the wave 14 paragraph in `docs/TASKS.md`; rewrite with the
`handoff` skill.

## Now

- Wave 13 is merged and logged (T88 to T92, T95 to T97; ADRs 0029, 0030). The post-wave sweep on
  91bd3cd failed 2 of 22 parts (`out\sweep-20261014-1427`): device tour 9/10 twice (`region-z8`, no
  contour lines) and `update` at `lower-quiet`; release certificate confirmed. No release yet.
- Running: T98 (the z8 contour miss) and T99 (the sweep's update part, sheet, `steps.tsv`, hook
  paths), Opus implementers in `dystopo-wt\T98` (AVD `dystopo35-T98`, console 5600, ports 8720 up)
  and `dystopo-wt\T99` (AVD `dystopo35-T99`, console 5620, ports 8730 up).
- Live viewer 8765 and standalone server 8800 run on the T88 build (`out/coordinator-pids.txt`).
- Owner: HOLD lifted to 100% for this session (override file until Oct 15 12:30 UTC; delete it when
  the session ends). Never `cd` into a subfolder in the coordinator's shell (hooks break; T99).

## Next

1. T98 and T99: Sonnet verifiers, the merge sequence, the log; Haiku chores for the worktrees.
2. The full sweep again (`sweep` skill): stop 8765 and 8800 by PID first, restart them after.
3. Green sweep: `v0.1.6-alpha` (`release` skill); the owner signed off the new strings on 2026-10-14,
   publish without asking again (ADR 0024).
4. Delete `~/.claude/usage-gate-override.json`; a chore removes the orphan folder `dystopo-wt\T85`.
5. The rest of wave 14 with the owner: the ideas folder (recentre tap rule, turn directions, scale
   bar, the demo page's trips box) and the T88 to T97 follow-ups.

## Waiting on user

- Hatchery PR #1; real-phone checks of 0.1.5; Google developer registration; whether to delete
  `task/T87-ci` (T87's commits, already in `main` through its squash merge).

## Open findings

- `docs/findings/2026-10-14-z8-contour-lines-missing-in-sweep-tour.md` (T98)
- `docs/findings/2026-10-14-sweep-update-part-blocked-by-previous-check.md` (T99)
