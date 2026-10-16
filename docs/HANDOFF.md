# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-16, after v0.1.8-alpha, by main session (second account, Fable 5.1)
Resume: this file, `docs/reference/machines.md` › The other account (`second`: checkout
`~\Documents\src\Dystopo`, release key in its `tools\`, no `devtool.local.json`), the
`wave` skill, and `docs/TASKS.md` (wave 15 closed: T108 to T113 merged; no open wave); rewrite with the `handoff` skill.

## Now

- Wave 15 is closed and v0.1.8-alpha is published (2026-10-16 08:33 UTC, tag on 9b2f723, versionCode 396,
  verified from the draft on an emulator; no phone was connected). Six tasks merged and logged: T108 (PR
  #110), T109 (#114), T110 (#112), T111 (#115), T112 (#111, ADR 0034), T113 (#113, mid-wave: the git guard
  read a `2>&1` suffix as a push refspec). Sweep `out\sweep-20261016-0340` green on the first run.
- No agent is implementing. Worktrees and task AVDs are removed (only the locked empty `dystopo-wt\T85`).
  Coordinator's servers: live viewer 8765 and standalone server 8800 from the main checkout, restarted on
  the new `main` after the sweep (a chore; PIDs in `out/coordinator-pids.txt`; confirm with a curl).
- Harmless leftovers: local branches `T104-verify`, `t107-slope-scratch`, `task/T87-ci`, `backup/*`.

## Next

1. Plan wave 16 (`wave` skill, critic pass before launch). Candidates: the wave 15 paragraph's deferred list
   (T98's all-NaN coast tile, T100's course tracking, T101's tilt, T103's rows, the browser-side directions
   op, a phone request log for cache replays); the Status-line follow-ups of T108 to T112 (`/help/close`
   dismissing what it opened, `HelpHost()` in `MainActivity`, Storage help icons and subtitle, `DirectSources`'
   trim under the budget, `pinnedCacheMb` cap, roundabouts and "then" cues, the nav bar's help icons); the
   open finding below; bushwhack (proposal first); route-to, auto-download, download by region, battery.
2. Carry to Hatchery with PR #1: the critic-pass step of the `wave` skill, T113's guard fix, T99's hooks.

## Waiting on user

- Real-phone checks of 0.1.8 (install from the release, `android-firstrun.py` and `features` on the
  phone); Hatchery PR #1; Google developer registration; delete `task/T87-ci`? MapLibre's own compass
  (T100)? T104's steepness palette and legend captions; the wording of the wave 14 and 15 strings (the 24
  help texts, the cache budget and install-during-navigation cards; listed in each PR); the 5 s request
  timeout grace (slow headers now cut at 65 s, T111) if a reverse proxy needs 60.

## Open findings

- `docs/findings/2026-10-16-cold-view-gap-on-a-quiet-device.md` (wave 16 Android sweep)
