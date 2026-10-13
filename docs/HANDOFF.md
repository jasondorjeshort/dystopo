# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-13, at commit ca01726, by main session (session 7b335a00)
Resume: this file, then `docs/reference/machines.md` (the owner's account `Dorje` coordinates again:
checkout `src\dystopo`, key and `devtool.local.json` present) and the `wave` skill; rewrite with the
`handoff` skill at the end of every session and before any stop.

## Now

- Wave 13 launched 2026-10-13 ~21:00 UTC in one batch (`DYSTOPO_GATE_OVERRIDE=1`, owner's word:
  weekly budget ignored): T88 to T94, a chore (live viewer on 8765, PID in `out/coordinator-pids.txt`),
  a critic. Merged and logged: T94 (PR #90; Hatchery PR #1 awaits the owner), T93 (PR #91). The
  critic reviews are in; T95 (pinned tiles vs. version bumps) waits for T90 and T91.
- **Weekly limit hit ~22:30 UTC (429, resets Oct 16 00:00 New York):** T91 and T92 died mid-task
  (6 and 9 commits on their branches in `dystopo-wt\`, no PR); T88, T89, T90 fail at their next call
  unless extra usage is enabled. Resume a dead agent by message, or relaunch on its branch.

## Next

1. As PRs arrive: a Sonnet verifier each (`brief` skill), then the merge sequence and the log; T93
   first (T91 rebases over its blocks), T89 before T91 (the `trips/` drive-time lines). Read the
   critic's four reviews; a serious objection becomes a task, the rest notes; commit the Dystopo
   reviews, leave Hatchery's for its runner. T94's Hatchery PR is the owner's to merge.
2. After the last merge: the sweep, then `v0.1.6-alpha`.
3. Wave 14 from the ideas folder, the owner's decided items first (recenter button with the tap rule,
   turn directions, scale bar and miles), and the doctrine ADRs (driving, alpha interface freedom,
   no other product names in user-visible text).
4. The next session needs no env var: the owner writes `~/.claude/usage-gate-override.json`
   (`until`, `reason`, optional `meters`; ADR 0021, Usage gate). This session still runs the old
   status line, so the gate sees stale data and allows with a note: watch the 5-hour figure by hand.

## Waiting on user

- Merging T94's Hatchery PR; real-phone checks of 0.1.5 over USB (`android-firstrun.py`, `android-ui.py features`), the in-app
  update from 0.1.4, a real walk with GPS on and the screen off; Google developer registration.

## Open findings

- `docs/findings/2026-10-10-emulator-share-direct-connect.md` (T90 updates it this wave).
