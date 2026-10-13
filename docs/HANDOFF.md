# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-13, at commit ea4d83c, by main session (session 7b335a00)
Resume: this file, then `docs/reference/machines.md` (the owner's account `Dorje` coordinates again:
checkout `src\dystopo`, key and `devtool.local.json` present) and the `wave` skill; rewrite with the
`handoff` skill at the end of every session and before any stop.

## Now

- Wave 13 launched 2026-10-13 ~21:00 UTC in one batch (`DYSTOPO_GATE_OVERRIDE=1`, owner's word:
  weekly budget ignored): T88 to T94, a chore (live viewer on 8765, PID in `out/coordinator-pids.txt`),
  a critic. Merged and logged: T94 (PR #90; Hatchery PR #1 awaits the owner), T93 (PR #91). The
  critic reviews are in; T95 (pinned tiles vs. version bumps) waits for T90 and T91.
- **Weekly limit hit ~22:30 UTC** (429; T91 and T92 resumed by message after the owner enabled
  extra usage; the gate's deny line at 100 then refused new launches). **Owner's word 22:45 UTC:
  hold at 100, no new agents; the running ones stop gracefully.** PRs #92 (T89), #93 (T92), #94
  (T90) open and unverified; T88 and T91 asked to push WIP as draft PRs with a How to continue
  section. `out/usage-gate-no-deny-line.patch` (`deny: null`) stays unapplied, an option only.

## Next

1. After the reset (Oct 16 00:00 New York): a Sonnet verifier per open PR (`brief` skill), then
   the merge sequence and the log; T89 before T91 (`trips/` drive-time lines; T89's synced route
   `path` v2 for timed drive legs is accepted). Draft PRs (T88, T91): a fresh Opus implementer
   continues from the PR's How to continue section. T94's Hatchery PR #1 is the owner's to merge;
   the Hatchery critic reviews sit uncommitted in its clone.
2. After the last merge: the sweep, then `v0.1.6-alpha`.
3. Wave 14 from the ideas folder (the recentre tap rule, turn directions, scale bar and miles) and
   the doctrine ADRs (driving, alpha interface freedom, no other product names).
4. The owner lifts HOLD with `~/.claude/usage-gate-override.json` (ADR 0021, Usage gate); no env var.

## Waiting on user

- Merging T94's Hatchery PR; real-phone checks of 0.1.5 over USB (`android-firstrun.py`, `android-ui.py features`), the in-app
  update from 0.1.4, a real walk with GPS on and the screen off; Google developer registration.

## Open findings

- `docs/findings/2026-10-10-emulator-share-direct-connect.md` (T90 updates it this wave).
