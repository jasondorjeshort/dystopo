# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-15, at commit c37c743, by main session (second account, Fable 5.1 from Oct 14 15:40 UTC)
Resume: this file, `docs/reference/machines.md` › The other account (`second`: checkout
`~\Documents\src\Dystopo`, release key in its `tools\`, no `devtool.local.json`), the
`wave` skill, and the two wave 14 paragraphs in `docs/TASKS.md`; rewrite with the `handoff` skill.

## Now

- Wave 14 second half (launched 2026-10-15 00:00 UTC): merged and logged T101 (#102), T104 (#103),
  T102 (#106). Open PRs, each with a Sonnet verifier rebasing onto `main` and re-running: #104 T100
  (fix-up for following through a turn at speed, re-check in progress), #105 T107, #107 T103, #108
  T105. T106 still implements (two emulators, consoles 5654 and 5656). Worktrees
  `~\Documents\src\dystopo-wt\T1nn` (and `T1nn-verify`), AVDs `dystopo35-T1nn` under
  `E:\dystopo-avd`; a Haiku cleanup chore per merged task (long paths need the `\\?\` delete; one
  git command per call). Log entries for every PR are drafted in the session scratchpad.
- T107's 12-hour soak runs on port 8784 from `~\Documents\src\dystopo-soak-T107` (script
  PID 106656, server PID 80640) until about 12:27 UTC; read it with `server-soak.ps1 -ChartOnly`
  there; never stop it early. Live viewer 8765 and standalone server 8800 run on the released build.
- Owner: HOLD lifted to 100% for this session (override file until Oct 15 12:30 UTC; delete it at
  the end). Never `cd` into a subfolder in this session's shell (old hooks). Harmless leftovers: local
  branches `T104-verify`, `t107-slope-scratch`, `task/T87-ci` (merged content); the locked `dystopo-wt\T85`.

## Next

1. Merge #104, #105, #107, #108 on their MERGE (T103 and T104 conflicted only in two docs files; keep
   both sides), then T106 when its PR arrives; log each with `merge-log.py`; a cleanup chore per task.
2. After the last merge: README status, the post-wave sweep from the main checkout (`sweep` skill;
   its update part needs the key there), then v0.1.7-alpha (`release` skill). Ideas to `done`.

## Waiting on user

- Real-phone checks of 0.1.6; Hatchery PR #1 (T99 hooks too); Google developer registration; delete
  `task/T87-ci`? The z12 hillshade overview (T88); MapLibre's own compass (T100)? Store directions
  with a trip (T102)? T104's steepness palette.

## Open findings

- none once T105's PR #108 merges (it resolves the debug test compile finding)
