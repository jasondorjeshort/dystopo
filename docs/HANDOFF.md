# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-14, at commit cce5060, by main session (second account, Fable 5.1 from 15:40 UTC)
Resume: this file, `docs/reference/machines.md` › The other account (`second`: checkout
`~\Documents\src\Dystopo`, release key in its `tools\`, no `devtool.local.json`), the
`wave`, `sweep` and `release` skills, and the wave 13 paragraph in `docs/TASKS.md`; rewrite with the
`handoff` skill.

## Now

- Wave 13 is merged and logged: T88 to T92 and T95 to T97 (PRs #92 to #99), ADRs 0029 and 0030,
  README status updated. Owner: HOLD lifted to 100% for this session (override file until Oct 15
  12:30 UTC; delete it when the session ends).
- Running: the post-wave sweep (Sonnet, main checkout, `tools\sweep.ps1`, ports 8980 up, emulator
  console 5640, AVD `dystopo35-sweep-<date>` under `E:\dystopo-avd`). It stopped the live viewer
  8765 and the standalone server 8800 by PID and restarts both on the new build, rewriting
  `out/coordinator-pids.txt`. A Haiku chore removes the T88 and T95 worktrees, branches and AVDs.

## Next

1. Read the sweep report: the per-part table, the `47:31:38:10` certificate on its release APK, the
   sheet. A failed part becomes a wave 14 sweep task (`finding` skill for each).
2. Green sweep: `v0.1.6-alpha` (`release` skill: CHANGELOG section, tag on `main`, the Actions draft,
   an agent installs it over 0.1.5 by taps, notes through PrivacyCheck, publish, `gh release view`,
   log and README). The owner signed off the new strings on 2026-10-14; publish without asking again
   (ADR 0024).
3. Delete `~/.claude/usage-gate-override.json`; rewrite this file.
4. Wave 14 from the ideas folder (recentre tap rule, turn directions, scale bar, the demo page's
   trips box) and the follow-ups in the T88 to T97 Status lines.

## Waiting on user

- Hatchery PR #1; real-phone checks of 0.1.5 (then 0.1.6); Google developer registration; whether to
  delete `task/T87-ci` (T87's commits, already in `main` through its squash merge).

## Open findings

- none
