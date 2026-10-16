# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-16, at commit 0b25247, by main session (second account, Fable 5.1)
Resume: this file, `docs/reference/machines.md` › The other account (`second`: checkout
`~\Documents\src\Dystopo`, release key in its `tools\`, no `devtool.local.json`), the
`wave` skill, and the wave 15 section of `docs/TASKS.md` (all merged; sweep and release pending); rewrite with the `handoff` skill.

## Now

- Wave 15 launched 2026-10-16 about 05:40 UTC after a critic pass (now standard in the `wave` skill): five Opus
  implementers, worktrees `~\Documents\src\dystopo-wt\T1nn`, AVDs `dystopo35-T1nn` under `E:\dystopo-avd`.
  All six merged and logged: T108 (PR #110), T109 (#114), T110 (#112; its verifier's 62 s cold view is the
  finding below), T111 (#115), T112 (#111, ADR 0034), T113 (#113, added mid-wave: the git guard read a `2>&1`
  suffix as a push refspec). Cleanup chores for T109 and T111 were the last agents; after them no worktree
  but the locked empty `T85` and no task AVD should remain. Not yet done: README status, sweep, release.
- Coordinator's servers run from the main checkout (started this session): live viewer 8765 and
  standalone server 8800, PIDs in `out/coordinator-pids.txt`. The T107 soak is archived in `out/soak-T107`
  and its folder removed; its one oddity is the open finding below (T111's item 1). Harmless leftovers:
  local branches `T104-verify`, `t107-slope-scratch`, `task/T87-ci`; the locked empty `dystopo-wt\T85`.

## Next

1. Post-wave sweep by an agent from the main checkout (`sweep` skill; the release key is there; `-Matrix` if
   time allows; view `sheet.png`; the tour's first view now waits 60 s). The 8765 and 8800 servers rebuild
   their graph files (version 6) once when restarted on the new `main`.
2. If green: v0.1.8-alpha (`release` skill) without asking again (ADR 0024); then the README status paragraph
   with the release link, and the `release` skill step 3 wording (world tour sheet only) and `sweep` skill `sheetSize`.
3. Wave 16 candidates: the wave 15 paragraph's deferred list, the Status-line follow-ups, the open finding below, bushwhack (proposal first), route-to, auto-download, download by region, battery.

## Waiting on user

- Real-phone checks of 0.1.7 (install from the release, `android-firstrun.py` and `features` on the
  phone); Hatchery PR #1 (add the critic-pass step and T99's hooks); Google developer registration; delete
  `task/T87-ci`? MapLibre's own compass (T100)? T104's steepness palette and legend captions; the wording
  of the wave 14 and 15 strings (listed in each PR).

## Open findings

- `docs/findings/2026-10-16-cold-view-gap-on-a-quiet-device.md` (wave 16 Android sweep)
