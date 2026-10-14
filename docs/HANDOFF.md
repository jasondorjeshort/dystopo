# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-14, at commit 41437ae, by main session (second account, Fable 5.1 from 15:40 UTC)
Resume: this file, `docs/reference/machines.md` › The other account (`second`: checkout
`~\Documents\src\Dystopo`, release key in its `tools\`, no `devtool.local.json`), the
`wave`, `sweep` and `release` skills, and the wave 14 paragraph in `docs/TASKS.md`; rewrite with the
`handoff` skill.

## Now

- Wave 13 is merged and logged (T88 to T92, T95 to T97; ADRs 0029, 0030). Its sweep on 91bd3cd
  failed the z8 tour view and the `update` part; wave 14's T98 (PR #101) and T99 (PR #100) fixed
  both and are merged and logged. `main` holds nothing unreleased but these and wave 13.
- Running: the full sweep again from the main checkout (Sonnet; `tools\sweep.ps1`, ports 8980 up,
  emulator console 5640, AVD under `E:\dystopo-avd`); it stops the live viewer 8765 and the standalone
  server 8800 by PID and restarts both on the new build, rewriting `out/coordinator-pids.txt`.
  A Haiku chore removes T98's worktree and branch.
- Owner: HOLD lifted to 100% for this session (override file until Oct 15 12:30 UTC; delete it when
  the session ends). Never `cd` into a subfolder in this session's shell (its hooks predate T99).

## Next

1. Read the sweep report (the per-part table, the `47:31:38:10` certificate on its release APK, the
   sheet, the update part's ten steps). A failed part becomes a wave 14 task (`finding` skill).
2. Green sweep: `v0.1.6-alpha` (`release` skill); the owner signed off the new strings on 2026-10-14,
   publish without asking again (ADR 0024).
3. Delete `~/.claude/usage-gate-override.json`; rewrite this file.
4. The rest of wave 14 with the owner: the ideas folder (recentre tap rule, turn directions, scale
   bar, the demo page's trips box) and the follow-ups in the T88 to T99 Status lines (first the
   shade tiles' z6/z7 hole over a pack's edge and the sweep setting the app's server).

## Waiting on user

- Hatchery PR #1 (and T99's exec-form hooks to carry over); real-phone checks of 0.1.5; Google
  developer registration; whether to delete `task/T87-ci` (already in `main` through its merge).

## Open findings

- none
