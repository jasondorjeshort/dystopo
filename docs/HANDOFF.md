# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-11 13:50 UTC, by main session (the owner's answers recorded; T275 still paused)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` › Wave 32, `out/wave32-research/` [no-check: gitignored].

## Now

- Wave 31 closed (`docs/tasks/wave-31.md`); **v0.1.26-alpha published** 2026-11-10 (tag on e5331b9c, versionCode 1445).
- **Wave 32 first half** (launched 2026-11-10 17:40 UTC on the owner's go; plan 298987de, fixes 93636d62; research
  and the critic's report in `out/wave32-research/` [no-check: gitignored]): **T271, T262, T264, T269, T272, T265 and
  T274 merged** (log entries of 2026-11-10). Nothing runs. Per-agent costs: the cost finding's wave 32 line.
- Rules: no force push (merge `main` in); a push on its own line; an LZW error in a cached DEM cell is a stale range
  cache (T269); a sweep part can lose its emulator after a guard reboot (one rerun; T273).

## Next

1. **T275** (the sweep's red parts, `out/sweep-20261110-1815` [no-check: gitignored]; upgrade passed there) is paused
   at d14a6468 on `task/T275`, worktree `dystopo-wt/T275`, AVDs `dystopo35-T275` and `-T275s` kept: the chip fix and
   the tour's 1200 s a run are in and unit-tested; `check` and `sweep.ps1 -Only device-boot,device-tour,probe` (its
   commit message has the command) are not run. Relaunch an implementer to finish it, then a verifier, the merge.
2. Sweep green: the alpha by the `release` skill; then the second half after the 7-day reset of the account the
   session is on (read it fresh; 2026-11-13 04:00 UTC on this one).
3. The owner removes the empty `C:\c` folder T264's first tilebuild run left (the safety check refused the agent).

## Waiting on user

- Answered 2026-11-11 (`docs/TASKS.md` › Owner actions pending, "Answered 2026-11-11", and Later's first entry).
- The owner's-eye items of wave 31's merges (ADR 0031, the record's Status lines) and the real-phone checks of
  v0.1.26-alpha: the owner reads them "soon enough".
- Next from the answers: the daily-limit research (ADR 0060); the 0054 glacier detail (permanent snow without a
  mapped glacier) when its ADR is written.

## Open findings

- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; the snapshot loop is T273.
- `docs/findings/2026-11-10-range-cache-keeps-blocks-across-a-republish.md`: 56 stale cells deleted; the validator is T269.
- `docs/findings/2026-11-10-wave-cost-doubled-and-the-lane-width-does-not-explain-it.md`: read the halves at wave 32's close.
