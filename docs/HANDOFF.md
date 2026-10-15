# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-15, at commit 8e96734, by main session (second account, Fable 5.1 from Oct 14 15:40 UTC)
Resume: this file, `docs/reference/machines.md` › The other account (`second`: checkout
`~\Documents\src\Dystopo`, release key in its `tools\`, no `devtool.local.json`), the
`wave` skill, and the two wave 14 paragraphs in `docs/TASKS.md`; rewrite with the `handoff` skill.

## Now

- Wave 14 second half (launched 2026-10-15 00:00 UTC): merged and logged T101 (#102), T104 (#103),
  T102 (#106), T103 (#107), T107 (#105). Open PRs, each with a Sonnet verifier rebasing onto `main`
  and re-running: #104 T100 (fix-up for following through a turn at speed), #108 T105, #109 T106.
  Worktrees `~\Documents\src\dystopo-wt\T1nn` (and `T1nn-verify`), AVDs `dystopo35-T1nn`
  under `E:\dystopo-avd`; a Haiku cleanup chore per merged task (long paths need the `\\?\` delete;
  one git command per call; T103's and T107's running). Log entries drafted in the session scratchpad.
- T107's 12-hour soak runs on port 8784 from `~\Documents\src\dystopo-soak-T107` (script
  PID 106656, server PID 80640) until about 12:27 UTC; read it with `server-soak.ps1 -ChartOnly`
  there; never stop it early. Live viewer 8765 and standalone server 8800 run on the released build.
- Owner: HOLD lifted to 100% for this session (override file until Oct 15 12:30 UTC; delete it at
  the end). Never `cd` into a subfolder in this session's shell (old hooks). Harmless leftovers: local
  branches `T104-verify`, `t107-slope-scratch`, `task/T87-ci` (merged content); the locked `dystopo-wt\T85`.

## Next

1. Merge #104, #108, #109 on their MERGE; log each with `merge-log.py`; a cleanup chore per task
   (T100's emulator `dystopo35-T100` and `T100v`; T105's `T105`, `T105-rebase`, `T105-verify`
   worktrees; T106's `T106va`, `T106vb`).
2. After the last merge: README status, the post-wave sweep from the main checkout (`sweep` skill;
   its update part needs the key there), then v0.1.7-alpha (`release` skill). Ideas to `done`.

## Waiting on user

- Real-phone checks of 0.1.6; Hatchery PR #1 (T99 hooks too); Google developer registration; delete
  `task/T87-ci`? The z12 hillshade overview (T88); MapLibre's own compass (T100)? Store directions
  with a trip (T102)? T104's steepness palette.

## Open findings

- none once T105's PR #108 merges (it resolves the debug test compile finding)
