# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-14, at commit 049d39d, by main session (second account, Fable 5.1 from 15:40 UTC)
Resume: this file, `docs/reference/machines.md` › The other account (`second`: checkout
`~\Documents\src\Dystopo`, release key in its `tools\`, no `devtool.local.json`), the
`wave` skill, and the two wave 14 paragraphs in `docs/TASKS.md`; rewrite with the `handoff` skill.

## Now

- Wave 14 second half launched 2026-10-14 about 00:00 UTC (Oct 15): eight Opus implementers, one
  per task T100 to T107, worktrees `~\Documents\src\dystopo-wt\T1nn`, branches
  `task/T1nn`, AVDs `dystopo35-T1nn` under `E:\dystopo-avd`, ports and consoles as the second wave 14
  paragraph lists (T100 8750/5630 ... T106 8770, 8771/5654, 5656; T107 8780 to 8787, no emulator).
  No PR yet. Verifiers take 8790 to 8799 and consoles 5660 to 5670.
- v0.1.6-alpha is published (2026-10-14 22:42 UTC, versionCode 332). Live viewer 8765 and
  standalone server 8800 run on the released build (`out/coordinator-pids.txt`).
- Owner: HOLD lifted to 100% for this session (override file until Oct 15 12:30 UTC; delete it when
  the session ends). ADR 0031: interface changes from the ideas folder are built without sign-off.
  Never `cd` into a subfolder in this session's shell (its hooks predate T99).
- Leftovers: `task/T87-ci` (local branch, already in `main`), the empty locked folder
  `dystopo-wt\T85`, an orphan `tail -F` (PID 69696) on a dead sweep folder; all harmless.

## Next

1. As each PR arrives: a Sonnet verifier (`brief` skill, `verifier.md`), then the merge sequence
   (`wave` skill) and `merge-log.py`; T105's report answers two app questions for T106 (`catalogPacks
   0`, the features part's route line): relay them.
2. After the last merge: README status paragraph, the post-wave sweep from the main checkout (the key
   is there; `sweep` skill), then v0.1.7-alpha (`release` skill).
3. Set the planned ideas to `done` as their tasks merge (`docs/ideas/README.md` index too).
4. Rewrite this file after the first merges.

## Waiting on user

- Real-phone checks of 0.1.6 (install from the release, `android-firstrun.py` and `features` on the
  phone); Hatchery PR #1 (and T99's exec-form hooks to carry over); Google developer registration;
  whether to delete `task/T87-ci`; T88's question whether the z12 hillshade may read one coarser
  3DEP overview (about a quarter of a cold view's bytes, a softer hillshade).

## Open findings

- `docs/findings/2026-10-14-debug-test-compile-loses-internal-after-cache-hit.md` (T105)
