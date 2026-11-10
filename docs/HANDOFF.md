# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-10, by main session (wave 32: T271, T262, T264 merged; gate shut until the 5-hour reset)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` › Wave 32, `out/wave32-research/` [no-check: gitignored].

## Now

- Wave 31 closed (`docs/tasks/wave-31.md`); **v0.1.26-alpha published** 2026-11-10 (tag on e5331b9c, versionCode 1445).
- **Wave 32 first half** (launched 2026-11-10 17:40 UTC on the owner's go; plan 298987de, fixes 93636d62; research
  and the critic's report in `out/wave32-research/` [no-check: gitignored]): **T271, T262, T264 merged** (log
  entries of 2026-11-10). Nothing runs. Per-agent token counts for the close: the session scratchpad's
  `wave32-costs.tsv` [no-check: scratchpad]. Usage gate shut at 19:09 UTC (5-hour 53, 7-day 74); 5-hour reset 20:40.
- Rules: no force push (merge `main` in); a push on its own line; an LZW error in a cached DEM cell is a stale range
  cache (T269); a sweep part can lose its emulator after a guard reboot (one rerun; T273).

## Next

1. After the 5-hour reset: a Haiku chore cleans up T262 and T264 (branches, worktrees `dystopo-wt/T262` and `T264`,
   AVDs `dystopo35-T262` and `dystopo35-T264`; the verifiers' AVDs are gone). Then T272 (lane A, 5570/8694) and
   T269 (lane B, no emulator); T265 after T269. The tour's offline step flakes on emulator fetches (two branches).
2. After the first half: sweep and alpha (the sweep runs T262's `upgrade` check); the second half after the 7-day
   reset of 2026-11-14 03:00 UTC.
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
