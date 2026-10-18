# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-17, after the update from Hatchery (ADR 0037; committed on main, not yet pushed), by main session (second account)
Resume: this file, `docs/reference/machines.md` › The other account, the `wave` skill, `docs/TASKS.md` › Wave 16 (no critic pass yet); rewrite with the `handoff` skill.

## Now

- Wave 16 is planned, nothing launched. The owner's lead issue on 0.1.8 is T114 (route to here from the
  phone's position); its design input is the finding `docs/findings/2026-10-17-route-to-flows-in-other-apps.md`.
  T115 and T116 are the leftover sweeps; T117 and T118 apply ADR 0036 and start from the files the update brought.
- The update from Hatchery (`987265c` to `749377e`) is in: ADR 0037 is the provenance record (base line,
  departure table, excludes); `CLAUDE.md` imports ten one-line pointers in `.claude/project/` instead of the
  base's agreement; the push scan runs in `git-guard`; `queue-list.js`, `ownership.js` and `triage` arrived.
  Push it with `bash tools/push-verified.sh` when the owner has read the Waiting items, or on their word.
- Histories rewritten 2026-10-17: hashes cited in `docs/` before then are dead until the re-record chore runs. No agent runs.

## Next

1. Chores (Haiku): the cited hashes in `docs/`, `CHANGELOG.md` and `README.md` re-recorded through the
   rewrite kit's two `*.commit-map` files only (rule in the wave 16 paragraph); the live viewer at launch (`wave` skill).
2. Critic pass on the wave 16 section (`wave` skill, Plan and launch 2); edit; launch T114 first, then
   T115 to T118 (five implementers, Opus-class, own worktrees, ports and AVDs).
3. Verify, merge, post-wave sweep, v0.1.9-alpha (`release` skill).
4. Carry-backs for Hatchery (T117's pull request): the harness group and its pusher-case skip, the integrate entry, the elided paths, the handoff rule, a lift for an unimported agreement section.

## Waiting on user

- Nothing from the update (ADR 0037): the pointer imports stay, the pre-push scan is chained, commits here are
  made as the placeholder identity in `policy.json` (`privacy.identities`; the repository's `user.name` and
  `user.email` are set to it) or the push scan refuses them, from a session and from a terminal alike.
- The wave 16 section, to read before the critic pass if wanted; real-phone checks of 0.1.8
  (`android-firstrun.py`, `features`); Google developer registration; MapLibre's own compass (T100); T104's
  steepness palette and captions; the wave 14 and 15 strings (in each PR); the 5 s timeout grace (T111).

## Open findings

- `docs/findings/2026-10-16-cold-view-gap-on-a-quiet-device.md` (T115)
- `docs/findings/2026-10-17-route-to-flows-in-other-apps.md` (T114 resolves it)
