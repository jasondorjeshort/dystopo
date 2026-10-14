# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-14, at commit 5313780, by main session (second account, Fable 5.1 from 15:40 UTC)
Resume: this file, `docs/reference/machines.md` › The other account (`second`: checkout
`~\Documents\src\Dystopo`, release key in its `tools\`, no `devtool.local.json`), the
`wave` skill, and the wave 14 paragraph in `docs/TASKS.md`; rewrite with the `handoff` skill.

## Now

- v0.1.6-alpha is published (2026-10-14 22:42 UTC, versionCode 332): wave 13 plus wave 14's T98 and
  T99, after a green sweep (`out\sweep-20261014-1730`, `check` rerun green in `-1818`). Nothing is
  running. `main` holds no unreleased work.
- Live viewer 8765 and standalone server 8800 run on the released build (`out/coordinator-pids.txt`).
- Owner: HOLD lifted to 100% for this session (override file until Oct 15 12:30 UTC; delete it when
  the session ends). Never `cd` into a subfolder in this session's shell (its hooks predate T99).
- Leftovers: `task/T87-ci` (local branch, already in `main`), the empty locked folder
  `dystopo-wt\T85`, an orphan `tail -F` (PID 69696) on a dead sweep folder; all harmless.

## Next

1. Plan the rest of wave 14 with the owner (`wave` skill): the ideas folder (recentre tap rule, turn
   directions, scale bar, the demo page's trips box) and the follow-ups in the T88 to T99 Status
   lines, first the shade tiles' z6/z7 hole over a pack's edge with a dead server, the sweep setting
   the app's server explicitly, the stale debug test compile (the open finding), the update part's
   eleven steps in the docs, the release part keeping its own APK copy.
2. Launch wave 14's implementers (5 to 8, Opus; `brief` skill) with disjoint Owns lines.
3. Rewrite this file after the plan.

## Waiting on user

- Real-phone checks of 0.1.6 (install from the release, `android-firstrun.py` and `features` on the
  phone); Hatchery PR #1 (and T99's exec-form hooks to carry over); Google developer registration;
  whether to delete `task/T87-ci`.

## Open findings

- `docs/findings/2026-10-14-debug-test-compile-loses-internal-after-cache-hit.md` (wave 14)
