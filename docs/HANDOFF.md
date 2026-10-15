# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-15, at commit 48b0519, by main session (second account, Fable 5.1 from Oct 14 15:40 UTC)
Resume: this file, `docs/reference/machines.md` › The other account (`second`: checkout
`~\Documents\src\Dystopo`, release key in its `tools\`, no `devtool.local.json`), the
`wave` skill, and the two wave 14 paragraphs in `docs/TASKS.md`; rewrite with the `handoff` skill.

## Now

- Wave 14 second half (launched 2026-10-15 00:00 UTC): merged and logged T101 (PR #102) and T104
  (PR #103). Open PRs: #104 T100 (verified MERGE WITH NOTES; a fix-up for following through a turn at
  speed is being pushed, then the verifier re-checks), #106 T102 and #107 T103 (verifiers running),
  #105 T107 (implementer's report pending; no verifier yet). T105 and T106 still implement.
  Worktrees `~\Documents\src\dystopo-wt\T1nn` (and `T1nn-verify`), AVDs `dystopo35-T1nn`
  under `E:\dystopo-avd`, ports as the second wave 14 paragraph lists; verifiers 8790 to 8799,
  consoles 5660 to 5670. Cleanup: T101 done, T104's chore running (long paths need the `\\?\` delete).
- v0.1.6-alpha is published (2026-10-14 22:42 UTC, versionCode 332). Live viewer 8765 and
  standalone server 8800 run on the released build (`out/coordinator-pids.txt`).
- Owner: HOLD lifted to 100% for this session; the override file (until Oct 15 12:30 UTC) was
  re-created at 00:55 UTC after going missing; delete it when the session ends. ADR 0031 governs
  interface changes. Never `cd` into a subfolder in this session's shell (its hooks predate T99).

## Next

1. Merge #104 after the verifier's delta check, then #106 and #107 on their MERGE (T103 and T104
   conflict only in two docs files: keep both sides); verify #105 once T107 reports; log each with
   `merge-log.py` (entries drafted in the session scratchpad); a Haiku cleanup chore per task.
2. T105's report answers two app questions for T106 (`catalogPacks 0`, the features part's route line).
3. After the last merge: README status paragraph, the post-wave sweep from the main checkout (the key
   is there; `sweep` skill), then v0.1.7-alpha (`release` skill). Set merged ideas to `done`.

## Waiting on user

- Real-phone checks of 0.1.6; Hatchery PR #1 (T99's exec-form hooks too); Google developer
  registration; delete `task/T87-ci`? The z12 hillshade's coarser overview (T88); keep MapLibre's own
  compass under the status bar (T100)? Store directions with a trip (T102)? T104's steepness palette.

## Open findings

- `docs/findings/2026-10-14-debug-test-compile-loses-internal-after-cache-hit.md` (T105 has the cause)
