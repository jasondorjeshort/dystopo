# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-25, at commit e294a394, by main session
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` › Later; `handoff` skill.

## Now

- Wave 19 is closed: T136 to T139 merged and logged, the sweep green (24 parts, `-BothUnits`),
  v0.1.12-alpha published 2026-10-24 (tag on 63a7d7dc, versionCode 514; a local signed build in the
  main checkout, the log entry says why). No task worktree remains; the two carry-back worktrees and
  the owner's locked `dystopo-wt/T122` folder stay. `origin/task/T138` is merged and still on origin
  (the cleanup chore's delete was refused by the auto-mode classifier; the owner deletes it).
- No pull requests (ADR 0043). An agent cannot copy the signing files out of the main checkout (a
  deny rule), so the release and the sweep's signing parts run in the main checkout itself.
- Carry-backs `carry-back/test-hooks-cb1-skip` and `carry-back/handoff-no-process-ids` are on origin
  for Hatchery to land. The live viewer and the standalone server are wanted from the main checkout.

## Next

1. Plan wave 20 by the `wave` skill from `docs/TASKS.md` › Later (the wave 19 follow-ups bullet, the
   heap diagnostics idea's Plan block, plus whatever the owner's answers unlock); critic pass first.
2. A Hatchery-rooted session: land the two carry-back branches (`carry-back` skill, Land), file
   `docs/ideas/2026-10-23-hatchery-ownership-check-in-project-trees.md` there and delete it here, give
   its proposal 0003 ADR 0043's lessons; then ADR 0021's follow-ups and the second base update.
3. The build tasks of ADR 0040 and 0041 after the owner's positions; ADR 0042's after its confirmations.

## Waiting on user

- `git push origin --delete task/T138` and `git branch -D carry-back/ownership-project-list`; the
  `RELEASE_*` secrets (four, `docs/reference/android-release.md`); the wave 19 strings (ADR 0031,
  T137's Status line) and the wave 18 strings; ADR 0042's confirmations, ADR 0040's and 0041's
  positions; a real-phone run of `tools/android-battery.py`, real-phone heap numbers on the large
  heap and the real-phone checks of v0.1.12-alpha; whether session links may stay in commit trailers.
- Carried: the S Pen question; the T122 folder, `triage-saved` and `refs/original/*`; the two older
  Claude-authored commits; T122's and the wave 14 to 17 strings; Play registration; T100, T104.

## Open findings

none
