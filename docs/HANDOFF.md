# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-14, at commit 59dbb7e, by main session (second account, Fable 5.1 from 15:40 UTC)
Resume: this file, `docs/reference/machines.md` › The other account (`second`: checkout
`~\Documents\src\Dystopo`, release key in its `tools\`, no `devtool.local.json`), the
`wave` skill, and the wave 13 paragraph in `docs/TASKS.md` (second-half ports); rewrite with the
`handoff` skill.

## Now

- Wave 13 second half from `second`; owner: HOLD lifted to 100% for this session (override file until
  Oct 15 12:30 UTC; delete it when the session ends).
- Merged and logged today: T89 (#92), T90 (#94), T92 (#93), T91 (#95), T97 (#97), T96 (#98),
  T95 (#99); ADRs 0029 and 0030. One task left: T88.
- Running: T88 continuation (Opus, draft PR #96; `dystopo-wt\T88`, emulator console 5560, ports
  8690/8691); a Haiku chore removing the merged worktrees and local branches.
- Live viewer 8765 and standalone server 8800 run on this account (`out/coordinator-pids.txt`) on a
  pre-T89 build; they hold the install jars open, so the sweep's build must stop and restart them.

## Next

1. T88's PR: a Sonnet verifier (ports 8710/8711, console 5580), the merge sequence, the log; then
   `docs/reference/android-online.md` gets T95's lines from PR #99's body (coordinator or a chore).
2. After T88 merges: README status paragraph; the full sweep from the main checkout (`sweep` skill;
   stop 8765 and 8800 by PID first and restart them on the new build after; confirm the
   `47:31:38:10` certificate on the sweep's release APK); its findings become a wave 14 task.
3. Green sweep: `v0.1.6-alpha` (`release` skill); the owner signed off the new strings 2026-10-14.
4. Wave 14 from the ideas folder and the follow-ups in the T89 to T97 Status lines.

## Waiting on user

- Hatchery PR #1; real-phone checks of 0.1.5; Google developer registration.

## Open findings

- none
