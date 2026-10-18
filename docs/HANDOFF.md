# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-17, after 3f7bd8e (wave 16 planned, not launched), by main session (second account)
Resume: this file, `docs/reference/machines.md` › The other account, the `wave` skill, `docs/TASKS.md` ›
Wave 16 (T114 to T118, written 2026-10-17 with the owner; no critic pass yet); rewrite with the `handoff` skill.

## Now

- Wave 16 is planned, nothing launched. The owner's lead issue on 0.1.8 is T114 (route to here from the
  phone's position; today a long-press offers only a waypoint or a hand-placed origin); its design input is
  the finding `docs/findings/2026-10-17-route-to-flows-in-other-apps.md`, an agent's survey of the
  navigation and outdoor apps. T115 and T116 are the leftover sweeps; T117 and T118 apply ADR 0036.
- ADR 0036 (2026-10-17) records the owner's answers to ADR 0035: a subagent may pull, merge or rebase
  `main` and resolve conflicts (commit there stays refused); `docs/ideas/` takes the base's grammar and
  the `triage` skill. The handoff no longer carries process ids or ports (`handoff` skill).
- Both histories (this repository's, Hatchery's) were rewritten on 2026-10-17: hashes cited in `docs/`
  before then are dead until the re-record chore runs (ADR 0020's base line is now `987265c`). No agent runs.

## Next

1. Chores (Haiku): hook tests under the updated hooks; the cited hashes re-recorded through the rewrite
   kit's two `*.commit-map` files only (rule in the wave 16 paragraph); the live viewer at launch (`wave` skill).
2. Critic pass on the wave 16 section (`wave` skill, Plan and launch 2); edit; launch T114 first, then
   T115 to T118 (five implementers, Opus-class, own worktrees, ports and AVDs).
3. Verify, merge, post-wave sweep, v0.1.9-alpha (`release` skill).
4. The next `update-from-hatchery` is large (Hatchery ADRs 0016 to 0018: `CLAUDE.md` split into imported
   base files, patch-only updates, the personal-data scan); plan it with the owner after wave 16.

## Waiting on user

- The wave 16 section, to read before the critic pass if wanted; real-phone checks of 0.1.8
  (`android-firstrun.py`, `features`); Google developer registration; MapLibre's own compass (T100); T104's
  steepness palette and captions; the wave 14 and 15 strings (in each PR); the 5 s timeout grace (T111).

## Open findings

- `docs/findings/2026-10-16-cold-view-gap-on-a-quiet-device.md` (T115)
- `docs/findings/2026-10-17-route-to-flows-in-other-apps.md` (T114 resolves it)
