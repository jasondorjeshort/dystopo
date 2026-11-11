# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-11 01:50 UTC, by main session (stopped for a Windows restart: the first half merged, T275 paused)
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

- Wave 32's choices: T263's picker setting default (the research says skip the Record-as step; the
  plan keeps it until you say); the course entry off by default in the readout (seen on the v0.1.26 install).
- ADR 0060: the refresh default, the keep-warm list as a new data flow, the daily limit's default, country downloads.
- ADR 0061: the first-run card while the pack list loads (F1 wait, F2 unknown until known, F3, F4); F1 built first.
- The Menu choice (Layout C); T220; proposals 0054 and 0055; the owner's-eye items of wave 31's merges (ADR 0031,
  the record's Status lines); the four triage Questions (the flight's freeze report, the accelerometer, a raster
  cache, two channels at beta); the real-phone checks of v0.1.26-alpha.

## Open findings

- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; the snapshot loop is T273.
- `docs/findings/2026-11-10-range-cache-keeps-blocks-across-a-republish.md`: 56 stale cells deleted; the validator is T269.
- `docs/findings/2026-11-10-wave-cost-doubled-and-the-lane-width-does-not-explain-it.md`: read the halves at wave 32's close.
