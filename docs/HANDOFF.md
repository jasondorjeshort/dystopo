# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-18 (midday), after commit d08add1, by main session (second account)
Resume: this file, `docs/reference/machines.md` › The other account, the `wave`, `triage` and `release` skills, `docs/TASKS.md` › Wave 16 (all Status lines in, T119 included); rewrite with the `handoff` skill.

## Now

- Wave 16 is closed and published: T118 to T114 and T115 (PRs #1 to #5), T119 (PR #6, the sweep's
  red `routes` line: a race older than the wave, fixed); v0.1.9-alpha published 2026-10-18 13:01 UTC
  (tag on d08add1, versionCode 427), built locally by the `release` skill's fallback and verified from
  the draft; the log entries are in `docs/PLANNING.md`, the README names it.
- GitHub Actions starts no job on the repository since it went private with the rewrite (billing
  message), so CI shows red on every push, the release workflow did not run and the sweep's `update`
  real-API step is red. All of it clears when the repository is public again.
- Hatchery draft PR #1 is open for the owner. No task branches remain; the release worktree stays
  registered until its folder is deleted (below). The viewer on 8765 and the server on 8800 run.

## Next

1. Run the `triage` skill over `docs/ideas/` (nine files, eight untriaged after T118).
2. Plan wave 17 from the T114 to T119 Status lines: the hooks task first (the guard does not inspect
   `git rebase --exec`); the nav bar's help icons and the pop-ups over their icon at scale 2.0; the
   route-to follow-ups (the "Go" rule, the fresh-fix replan, stops, reroute, route to here from
   search); the OpenFreeMap stall guard; `android-ui.py routes` recording the 404 body and a
   `TripRoutes.kt` audit (T119); the deferred sweep items. Critic pass before launch.
3. Launch the wave when the 5-hour meter is below 50 (the session waits by scheduled wake-ups).

## Waiting on user

- The repository is private: no phone's updater can see v0.1.8-alpha or v0.1.9-alpha, and Actions
  (CI, nightly, release) does not run, until it is public again.
- Two folders the permission classifier refuses agents to delete: `../dystopo-wt/T114` and
  `../dystopo-wt/rel-0.1.9` (then `git worktree prune`).
- T114's strings and card (PR #4, 13 strings; ADR 0031) and T115's help texts (PR #5); Hatchery draft
  PR #1; real-phone checks of v0.1.9-alpha; Google developer registration; MapLibre's compass (T100); T104's palette and captions; the wave 14 and 15 strings; the 5 s timeout grace (T111).

## Open findings

- none
