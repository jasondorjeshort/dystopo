# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-10, by main session (wave 32 launched on the owner's go: T262, T264, T271 running)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` › Wave 32, `out/wave32-research/` [no-check: gitignored].

## Now

- Wave 31 closed (`docs/tasks/wave-31.md`); **v0.1.26-alpha published** 2026-11-10 (tag on e5331b9c, versionCode 1445).
- **Wave 32 launched 2026-11-10 17:40 UTC on the owner's go** (plan 298987de, three fixes 93636d62; research and the
  critic's report in `out/wave32-research/` [no-check: gitignored]): T262 (lane A, 5570/8694), T264 (lane B,
  5572/8696), T271 (alongside, no Gradle) running. Meter at the go 7-day 68, 5-hour 32: the second half waits for the
  reset of 2026-11-14 03:00 UTC. Free RAM 90.5 GB.
- Rules learned this wave: no force push works here (merge `main` in, never rebase a pushed branch); a push on a line
  of its own; an LZW error in a cached DEM cell is a stale range cache (delete the cell; T269); a sweep part can lose
  its emulator right after a guard reboot (one rerun; T273 adds the storage wait).

## Next

1. As each reports: a verifier (Sonnet, `wave` skill; ports 5574/8698 and 5576/8700, T271's on a free slot with a
   real dump), merge on MERGE. Lane A next T272, lane B next T269; then T265 after T262, T264 and T269 merge.
2. After the first half: sweep and alpha; the second half after the 7-day reset.

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
