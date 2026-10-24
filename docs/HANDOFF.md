# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-24, at commit d5702213, by main session
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` › Later; `handoff` skill.

## Now

- Wave 18 is closed on the owner's account: ten tasks merged and logged (T126 to T135), the sweep of
  `main` green after T135's fix, v0.1.11-alpha published 2026-10-24 (tag ec44b866, versionCode 485)
  from a local signed build (the workflow has no `RELEASE_*` secrets; the log entry says how). No
  task branches or worktrees remain; `dystopo-wt/T122` is the owner's locked folder.
- Carry-backs `carry-back/test-hooks-cb1-skip` and `carry-back/handoff-no-process-ids` are on origin
  for Hatchery to land; the ownership-map one is an idea for Hatchery's queue (Next 2).
- The live viewer and the standalone server are wanted from the main checkout (`machines.md`).

## Next

1. Wave 19: plan from `docs/TASKS.md` › Later (the sweep's T130 and T128 observations, T127's
   `largeHeap` and `clear()` follow-ups, the `?track=1` replay option, the carried follow-ups of the
   wave 18 Status lines) and the owner's answers below; critic pass before launch (`wave` skill).
2. A Hatchery-rooted session: land the two carry-back branches (`carry-back` skill, Land), file
   `docs/ideas/2026-10-23-hatchery-ownership-check-in-project-trees.md` there and delete it here; then
   ADR 0021's follow-ups and the second base update.
3. The build tasks of ADR 0040 and 0041 once the owner has looked at their positions; ADR 0042's
   after its confirmations.

## Waiting on user

- The `RELEASE_*` secrets (four, `docs/reference/android-release.md`), so the workflow signs releases
  again; ADR 0042's confirmations (account, per-upload sheet, 500 m trim, trackable default, exception
  strings); ADR 0040's and 0041's positions; the wave 18 strings (T126's row and help text, T130's
  corridor line, actions and sheet row; ADR 0031); a real-phone run of `tools/android-battery.py`
  and the real-phone checks of v0.1.11-alpha; whether session links may stay in PR bodies.
- Carried: the S Pen question; the T122 folder, `triage-saved` and `refs/original/*`; the two older
  Claude-authored commits; T122's and the wave 14 to 17 strings; Play registration; T100, T104.

## Open findings

none
