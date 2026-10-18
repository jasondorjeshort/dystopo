# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-17 (late evening), after commit 46f7b3f, by main session (second account)
Resume: this file, `docs/reference/machines.md` › The other account, the `wave` skill, `docs/TASKS.md` › Wave 16 (ports assigned, no critic pass yet); rewrite with the `handoff` skill.

## Now

- The owner's word this evening: plan and execute wave 16, "notably including the route-to changes"
  (T114) "but can be minimal otherwise"; the board's wave 16 paragraph records it with ports and AVDs.
- Nothing launched: the session's 5-hour meter was past the 50% hold (it resets about 02:40 EDT on
  2026-10-18); the session waits for the reset by scheduled wake-ups, then launches. The update from
  Hatchery (ADR 0037) is pushed; `main` equals `origin/main`. No agent runs; no worktrees; no open PRs.
- The live viewer on 8765 and the standalone server on 8800 are down (the PID file is stale) and are
  restarted at launch by a chore.
- Briefs for the critic, the two chores and the implementers' common block are drafted in the session scratchpad only.

## Next

1. At the reset, in parallel: chores (Haiku) for the live viewer and the 8800 server plus the hook
   harness, and for the cited hashes in `docs/`, `CHANGELOG.md` and `README.md` re-recorded through
   the rewrite kit's two `*.commit-map` files (working tree only; the coordinator reviews and commits);
   the critic pass on the wave 16 section (most capable model, read-only, review to the scratchpad).
2. Answer the critic by editing the board, commit, push (`push-verified.sh`); launch T114 to T118
   (five implementers, Opus-class, own worktrees, the ports in the wave paragraph).
3. Verify each PR (Sonnet-class), merge by the `wave` skill's sequence, post-wave sweep, v0.1.9-alpha
   (`release` skill).
4. Carry-backs for Hatchery ride in T117's pull request (the harness group, the integrate entry, the
   elided paths, the handoff rule, a lift for an unimported agreement section).

## Waiting on user

- Real-phone checks of 0.1.8 (`android-firstrun.py`, `features`); Google developer registration;
  MapLibre's own compass (T100); T104's steepness palette and captions; the wave 14 and 15 strings
  (in each PR); the 5 s timeout grace (T111); T114's strings and card when its PR arrives.

## Open findings

- `docs/findings/2026-10-16-cold-view-gap-on-a-quiet-device.md` (T115)
- `docs/findings/2026-10-17-route-to-flows-in-other-apps.md` (T114 resolves it)
