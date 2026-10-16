# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-16, at commit 962e8d1, by main session (second account, Fable 5.1)
Resume: this file, `docs/reference/machines.md` › The other account (`second`: checkout
`~\Documents\src\Dystopo`, release key in its `tools\`, no `devtool.local.json`), the
`wave` skill, and the wave 15 section of `docs/TASKS.md` (open: T109 to T111); rewrite with the `handoff` skill.

## Now

- Wave 15 launched 2026-10-16 about 05:40 UTC after a critic pass (now standard in the `wave` skill): five Opus
  implementers, worktrees `~\Documents\src\dystopo-wt\T1nn`, AVDs `dystopo35-T1nn` under `E:\dystopo-avd`.
  Still running: T111 desktop and tooling sweep 8 (5580; 8708, 8709, soaks 8790, sweeps 8900 to 8907). T108
  (PR #110) and T112 (PR #111, ADR 0034) merged, logged, cleaned up. Sonnet verifiers running: T110 PR #112
  (`dystopo-wt\T110-verify`, AVD `dystopo35-T110-verify` on 5586, ports 8724, 8725); T109 PR #114
  (`T109-verify`, AVD `dystopo35-T109` on 5574, ports 8726, 8727). T113 (git guard, added mid-wave: a `2>&1`
  suffix was read as a push refspec) merged as PR #113 and logged; its cleanup chore is running.
- Coordinator's servers run from the main checkout (started this session): live viewer 8765 and
  standalone server 8800, PIDs in `out/coordinator-pids.txt`. The T107 soak is archived in `out/soak-T107`
  and its folder removed; its one oddity is the open finding below (T111's item 1). Harmless leftovers:
  local branches `T104-verify`, `t107-slope-scratch`, `task/T87-ci`; the locked empty `dystopo-wt\T85`.

## Next

1. Per PR (T109, T110, T111): a Sonnet verifier (`brief` skill, `verifier.md`), the merge sequence (`wave` skill), a Haiku cleanup chore.
2. After the last merge: README status paragraph, the post-wave sweep (`sweep` skill, with `-Matrix` if
   time allows), then v0.1.8-alpha (`release` skill) without asking again (ADR 0024).
3. Wave 16 candidates: the deferred list in the wave 15 paragraph, the Status-line follow-ups of T108 to
   T112, bushwhack (proposal first), route-to, auto-download, download by region, battery.

## Waiting on user

- Real-phone checks of 0.1.7 (install from the release, `android-firstrun.py` and `features` on the
  phone); Hatchery PR #1 (add the critic-pass step and T99's hooks); Google developer registration; delete
  `task/T87-ci`? MapLibre's own compass (T100)? T104's steepness palette and legend captions; the wording
  of the wave 14 and 15 strings (listed in each PR).

## Open findings

- `docs/findings/2026-10-16-soak-route-requests-without-status.md` (T111)
