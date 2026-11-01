# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-01, at commit 2b9baeb3 plus this edit, by main session (wave 24 closed, v0.1.17-alpha published)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` (no open wave;
the Later list carries wave 24's follow-ups and the sweep's findings; the record `docs/tasks/wave-24.md`).

## Now

- **Wave 24 is closed and v0.1.17-alpha is published** (tag 5504a729, published 2026-11-01, versionCode 972; the
  close entry of 2026-11-01 in `docs/PLANNING.md`'s log). Nine tasks T190 to T198 merged by verifier verdict; the
  post-wave sweep green (the device parts on its third run, see the finding below); the draft verified on an
  emulator (upgrade from 0.1.16 with data kept, features, strings). Nothing is running; no emulator, no lock, no
  worktree. The live viewer on 8765 is still up (PID in `out/coordinator-pids.txt`).
- **The emulator lock is live** (T190, `~/.dystopo/emulators/`, cap 2 in `policy.json`, guests at 3 GB by
  measurement): the owner's condition for the wave held throughout (two slips of under three minutes before the
  lock merged). An emulator costs about 4 GB of host; CPU saturation, not RAM, stalls a boot.
- Meter: 7-day 60 at launch, 81 at the close (21 points for nine tasks against 32 assumed); resets 2026-11-06.

## Next

1. The next wave is unplanned. Its sweep task takes the board's "Wave 24 sweep findings" Later line first:
   `emulator-stop.js` must leave an emulator alone when the hook input's `background_tasks` is non-empty (the
   sweep lost its emulator twice to the stop hook when its agent yielded). Plan by the `wave` skill: Later's
   lifted follow-ups (T190 to T198), the ideas with a `Plan:` block, the owner's theme if any.
2. A Hatchery-rooted session: the pending carry-backs plus T190's two (a project-extension section in the policy
   schema; the `SubagentStop` input fields for its hook spec) and the wave skill's sentinel rule.
3. ADR 0040's, 0042's and 0049's C after the owner's answers; the terrain-fix live source after the hike logs.

## Waiting on user

- **The paging file** (`docs/TASKS.md` › Owner actions pending): 12.8 GB on C: and not growing (commit limit 60 GB,
  44 GB in use mid-wave); set it to 32 GB or more, or turn automatic management on.
- The rest of Owner actions pending: install v0.1.17-alpha and the hike checks; the `Question:` blocks; ADR 0051's
  two answers; the git housekeeping row (now with `task/T192` and `task/T197`, the pre-rebase copies).

## Open findings

- `docs/findings/2026-11-01-stop-hook-kills-the-emulator-of-an-agent-that-yields-its-turn.md` (the next sweep task).
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: T189 ships the graph; the phone next.
