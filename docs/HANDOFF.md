# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-14, at commit ae69b14, by main session (second account, Fable 5.1 from 15:40 UTC)
Resume: this file, `docs/reference/machines.md` › The other account (`second`: checkout
`~\Documents\src\Dystopo`, release key in its `tools\`, no `devtool.local.json`), the
`wave`, `sweep` and `release` skills, and the wave 14 paragraph in `docs/TASKS.md`; rewrite with the
`handoff` skill.

## Now

- Wave 13 is merged and logged (T88 to T92, T95 to T97; ADRs 0029, 0030). Its sweep on 91bd3cd
  failed 2 of 22 parts (`out\sweep-20261014-1427`): the z8 tour view with no contour lines, and the
  `update` part. T99 (PR #100) fixed the update part, `steps.tsv`, the hook paths; merged and logged.
- Running: T98 (the z8 contour miss), Opus implementer in `dystopo-wt\T98` (AVD `dystopo35-T98`,
  console 5600, ports 8720 up). A Haiku chore removes T99's worktree and branch.
- Live viewer 8765 and standalone server 8800 run on the T88 build (`out/coordinator-pids.txt`).
- Owner: HOLD lifted to 100% for this session (override file until Oct 15 12:30 UTC; delete it when
  the session ends). Never `cd` into a subfolder in this session's shell (its hooks predate T99).

## Next

1. T98's PR: a Sonnet verifier (ports 8740 up, AVD `dystopo35-T98v`, console 5624), the merge
   sequence, the log, a Haiku chore for the worktree.
2. The full sweep again (`sweep` skill): stop 8765 and 8800 by PID first, restart them after; it
   now runs the update part with the release key against the published v0.1.5-alpha.
3. Green sweep: `v0.1.6-alpha` (`release` skill); the owner signed off the new strings on 2026-10-14,
   publish without asking again (ADR 0024).
4. Delete `~/.claude/usage-gate-override.json`; rewrite this file.
5. The rest of wave 14 with the owner: the ideas folder (recentre tap rule, turn directions, scale
   bar, the demo page's trips box) and the T88 to T99 follow-ups.

## Waiting on user

- Hatchery PR #1 (and T99's exec-form hooks to carry over); real-phone checks of 0.1.5; Google
  developer registration; whether to delete `task/T87-ci` (already in `main` through its merge).

## Open findings

- `docs/findings/2026-10-14-z8-contour-lines-missing-in-sweep-tour.md` (T98)
