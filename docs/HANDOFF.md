# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-15, at commit aa1326b, by main session (second account, Fable 5.1 from Oct 14 15:40 UTC)
Resume: this file, `docs/reference/machines.md` › The other account (`second`: checkout
`~\Documents\src\Dystopo`, release key in its `tools\`, no `devtool.local.json`), the
`wave` skill, and the two wave 14 paragraphs in `docs/TASKS.md`; rewrite with the `handoff` skill.

## Now

- Wave 14 second half, launched 2026-10-15 00:00 UTC: T101 merged (PR #102, 00:51 UTC, logged);
  seven Opus implementers still run, one each for T100 and T102 to T107, in worktrees
  `~\Documents\src\dystopo-wt\T1nn` on `task/T1nn`, AVDs `dystopo35-T1nn` under
  `E:\dystopo-avd`, ports as the second wave 14 paragraph lists. Verifiers: 8790 to 8799, consoles
  5660 to 5670 (T101's used 8790/5660; a Haiku chore removes T101's worktrees and AVDs).
- v0.1.6-alpha is published (2026-10-14 22:42 UTC, versionCode 332). Live viewer 8765 and
  standalone server 8800 run on the released build (`out/coordinator-pids.txt`).
- Owner: HOLD lifted to 100% for this session; the override file (until Oct 15 12:30 UTC) went
  missing once and was re-created at 00:55 UTC; delete it when the session ends. ADR 0031:
  interface changes from the ideas folder are built without sign-off. Never `cd` into a subfolder
  in this session's shell (its hooks predate T99).
## Next

1. As each PR arrives: a Sonnet verifier (`brief` skill, `verifier.md`), then the merge sequence
   (`wave` skill) and `merge-log.py`; relay T105's answers on `catalogPacks 0` and the features
   part's route line to T106; T101's follow-ups already went to T102, T106 and T107.
2. After the last merge: README status paragraph, the post-wave sweep from the main checkout (the key
   is there; `sweep` skill), then v0.1.7-alpha (`release` skill).
3. Set the planned ideas to `done` as their tasks merge (`docs/ideas/README.md` index too).

## Waiting on user

- Real-phone checks of 0.1.6; Hatchery PR #1 (and T99's exec-form hooks to carry over); Google
  developer registration; whether to delete `task/T87-ci`; T88's question whether the z12 hillshade
  may read one coarser 3DEP overview (a quarter of a cold view's bytes, a softer hillshade).

## Open findings

- `docs/findings/2026-10-14-debug-test-compile-loses-internal-after-cache-hit.md` (T105 has the cause)
