# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-23, at commit 6720d21f, by main session
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` › Wave 18 (T135 open); `handoff` skill.

## Now

- Wave 18 on the owner's account: eight of nine merged and logged (T126 to T132, T134); PR #15 (T133,
  ADR 0041) verified, rebased and mergeable, the owner runs the merge (this account's mode refuses
  the coordinator's `gh pr merge`); its log entry comes from the PR body. `dystopo-wt/T122` stays locked.
- The sweep of `main` at dd0383f1 (`out/sweep-20261023-2206`): 23 parts green, `device-tour` red on
  T127's direct step with a server (finding below); T135, the fix, implementing on `task/T135`, no PR.
- Carry-backs `carry-back/test-hooks-cb1-skip` and `carry-back/handoff-no-process-ids` are on origin
  for Hatchery to land; the ownership-map one is an idea for Hatchery's queue (Next 3).
- The live viewer and the standalone server are wanted from the main checkout (`machines.md`).

## Next

1. T135's PR: a Sonnet verifier (`brief` skill), the `wave` skill's merge sequence, then a Haiku
   chore reruns `tools\sweep.ps1 -Only device-boot,device-tour` on `main`.
2. Green: v0.1.11-alpha (`release` skill, ADR 0024) and the README status paragraph; rewrite this file.
3. A Hatchery-rooted session: land the two carry-back branches (`carry-back` skill, Land), file
   `docs/ideas/2026-10-23-hatchery-ownership-check-in-project-trees.md` there and delete it here; then
   ADR 0021's follow-ups and the second base update.
4. The next wave from `docs/TASKS.md` › Later and the answers below; critic pass before launch.

## Waiting on user

- The #15 merge line; ADR 0042's confirmations (account, per-upload sheet, 500 m trim, trackable
  default, exception strings); ADR 0040's and 0041's positions; the wave 18 strings (T126's row and
  help text, T130's corridor line, actions and sheet row; ADR 0031); a real-phone run of
  `tools/android-battery.py`; whether session links may stay in PR bodies (not tracked files).
- Carried: the S Pen question; the `RELEASE_*` secrets; the T122 folder, `triage-saved` and
  `refs/original/*`; the two older Claude-authored commits; T122's and the wave 14 to 17 strings;
  real-phone checks; Google developer registration; T100's compass; T104's palette; the 5 s grace.

## Open findings

- `docs/findings/2026-10-23-direct-step-live-heap-unread-with-a-server.md` (T135, in flight)
