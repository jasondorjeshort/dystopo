# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-15, at commit 1502593, by main session (second account, Fable 5.1 from Oct 14 15:40 UTC)
Resume: this file, `docs/reference/machines.md` › The other account (`second`: checkout
`~\Documents\src\Dystopo`, release key in its `tools\`, no `devtool.local.json`), the
`wave` skill, and the wave 14 section of `docs/TASKS.md` (closed); rewrite with the `handoff` skill.

## Now

- v0.1.7-alpha is published (2026-10-15 04:25 UTC, versionCode 364): wave 14 (T98 to T107, PRs #100
  to #109) after a green sweep (`out\sweep-20261014-2323`, 23 of 23 parts, no retry). Nothing is
  running from this session; no task worktree remains; `E:\dystopo-avd` is empty. `main` holds no
  unreleased work.
- T107's 12-hour soak runs on 8784 from `~\Documents\src\dystopo-soak-T107` (PIDs 106656,
  80640) until about 12:27 UTC; `server-soak.ps1 -ChartOnly` there reads it; never stop it early; the
  folder can go afterwards. Live viewer 8765 and standalone server 8800 run on the v0.1.6 build.
- Harmless leftovers: local branches `T104-verify`, `t107-slope-scratch`, `task/T87-ci` (merged
  content), the locked empty `dystopo-wt\T85`. The usage-gate override file was deleted at the end of
  the session. Next session: check that T105's exec-form status line still updates `usage-state.json`.

## Next

1. Read the soak's chart after 12:27 UTC (a finding if it is not flat); then remove the soak folder.
2. Plan wave 15 (`wave` skill) from the Status lines of T100 to T107 (their follow-ups), the sweep
   follow-ups (the sheet taller than the 16,384 px capture; the no-server cold z8 view at 43 s on a
   fresh AVD; whether `release.yml` renders the device tour sheet or copies a static one), the ideas
   still `new` (route-to, cache budgets, help pop-ups, auto-download, download by region, crow and
   bushwhack, battery, integrations, S Pen), and the owner's items below.

## Waiting on user

- Real-phone checks of 0.1.7 (install from the release, `android-firstrun.py` and `features` on the
  phone); Hatchery PR #1 (T99 hooks too); Google developer registration; delete `task/T87-ci`? The z12
  hillshade overview (T88); MapLibre's own compass (T100)? Store directions with a trip (T102)? T104's
  steepness palette and legend captions; the T106 and T107 wording (new strings listed in their PRs).

## Open findings

- none
