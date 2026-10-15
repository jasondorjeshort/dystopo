# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-15, at commit 6d7b5fb, by main session (second account, Fable 5.1 from Oct 14 15:40 UTC)
Resume: this file, `docs/reference/machines.md` › The other account (`second`: checkout
`~\Documents\src\Dystopo`, release key in its `tools\`, no `devtool.local.json`), the
`wave` skill, and the two wave 14 paragraphs in `docs/TASKS.md`; rewrite with the `handoff` skill.

## Now

- Wave 14 is fully merged and logged (T98 to T107, PRs #100 to #109; the last at 03:18 UTC); the
  README status is updated. A Haiku chore removes the last worktrees (T106, T107) and AVDs and stops
  the Gradle daemons; then the post-wave sweep runs from the main checkout (Sonnet agent, `sweep`
  skill, default ports 8980 to 8990, console 5640); green means v0.1.7-alpha (`release` skill).
- T107's 12-hour soak runs on 8784 from `~\Documents\src\dystopo-soak-T107` (PIDs 106656,
  80640) until about 12:27 UTC; `server-soak.ps1 -ChartOnly` there reads it; never stop it early.
  Live viewer 8765 and standalone server 8800 run on the v0.1.6 build.
- Owner: HOLD lifted to 100% for this session (override file until Oct 15 12:30 UTC; delete it at
  the end). Never `cd` into a subfolder in this session's shell (old hooks). Leftover local branches
  `T104-verify`, `t107-slope-scratch`, `task/T87-ci` (merged content); the locked `dystopo-wt\T85`. The
  next session checks that T105's exec-form status line still updates `~/.claude/usage-state.json`.

## Next

1. Read the sweep's `summary.json` and sheet; a red part becomes a finding and a wave 15 task.
2. Green: v0.1.7-alpha by the `release` skill (CHANGELOG section, tag, draft verified from its
   assets on a fresh emulator, publish, README link, log entry).
3. Plan wave 15 from the Status lines of T100 to T107 (their follow-ups), the ideas still `new`
   (route-to, cache budgets, help pop-ups, auto-download, download by region, crow and bushwhack,
   battery, integrations, S Pen) and the owner's items below.

## Waiting on user

- Real-phone checks of 0.1.6, then 0.1.7; Hatchery PR #1 (T99 hooks too); Google developer
  registration; delete `task/T87-ci`? The z12 hillshade overview (T88); MapLibre's own compass
  (T100)? Store directions with a trip (T102)? T104's steepness palette and legend captions.

## Open findings

- none
