# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-13, at commit 792bd2b, by main session (session 7b335a00)
Resume: this file, then `docs/reference/machines.md` (the owner's account `Dorje` coordinates again:
checkout `src\dystopo`, key and `devtool.local.json` present) and the `wave` skill; rewrite with the
`handoff` skill at the end of every session and before any stop.

## Now

- Wave 13 launched 2026-10-13 ~21:00 UTC (`DYSTOPO_GATE_OVERRIDE=1`): T88 to T94, a chore (live
  viewer on 8765, PID in `out/coordinator-pids.txt`), a critic. Merged and logged: T94 (PR #90;
  Hatchery PR #1 awaits the owner), T93 (PR #91). T95 (pinned tiles vs. version bumps) waits.
- **Weekly limit hit ~22:30 UTC** (429; T91 and T92 resumed by message after the owner enabled
  extra usage; the gate's deny line at 100 then refused new launches). **Owner's word 22:45 UTC:
  hold at 100, no new agents; the running ones stop gracefully.** Open, unverified: PRs #92 (T89),
  #93 (T92, agent still finishing its report), #94 (T90); drafts with a How to continue section:
  #95 (T91: all items done but the sweep's upgrade part, which needs the release key the worktree
  lacks) and #96 (T88: Rainier cold view 30 s to 7 to 16 s; `check`, docs and screenshots left).
  No release: `main` since 0.1.5 holds only T93 and T94, with no sweep on it and no agent to verify
  a draft (ADR 0024); 0.1.6-alpha follows the merges and the sweep after the reset.

## Next

1. After the reset (Oct 16 00:00 New York): a Sonnet verifier per open PR (`brief` skill), the
   merge sequence and the log, T89 before T91 (`trips/` lines; T89's synced route `path` v2 for
   timed drive legs is accepted); a fresh Opus implementer per draft PR, from its How to continue
   section (T91's upgrade part runs from the main checkout, where the release key is).
2. After the last merge: the sweep, then `v0.1.6-alpha`. Hatchery: PR #1 and the critic reviews.
3. Wave 14 from the ideas folder (the recentre tap rule, turn directions, scale bar and miles) and
   the doctrine ADRs (driving, alpha interface freedom, no other product names).
4. The owner lifts HOLD with `~/.claude/usage-gate-override.json` (ADR 0021, Usage gate); no env var.

## Waiting on user

- Hatchery PR #1; real-phone checks of 0.1.5 (`android-firstrun.py`, `android-ui.py features`, the in-app update from 0.1.4, a walk with GPS on and the screen off); Google developer registration.

## Open findings

- `docs/findings/2026-10-10-emulator-share-direct-connect.md` (T90 updates it this wave).
