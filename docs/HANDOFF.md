# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-10, by main session (wave 31 closed, v0.1.26-alpha published; wave 32 planned and criticised, waiting for the owner's go)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` › Wave 32, `out/wave32-research/` [no-check: gitignored].

## Now

- **Wave 31 is closed** (record `docs/tasks/wave-31.md`; the log's "Wave 31 closes" entry of 2026-11-10): seventeen
  tasks, two alphas. **v0.1.26-alpha published** 2026-11-10 13:36 UTC (tag on e5331b9c, versionCode 1445, the
  before-and-after sheet attached); v0.1.25-alpha at 09:48 UTC. Nothing runs; no worktree but main and the carry-backs.
- **Wave 32 is planned** (298987de; twelve tasks T262 to T273 in two lanes plus one alongside, in two halves against
  the 7-day reset of 2026-11-13; the critic's 22 objections answered in the wave paragraph; research in
  `out/wave32-research/` [no-check: gitignored], the critic's report beside it) and **not launched: the owner's go is
  pending.** The first half is the release set T262, T272, T264, T269, T265 (lane A first T262, lane B first T264).
- Rules learned this wave: no force push works here (merge `main` in, never rebase a pushed branch); a push on a line
  of its own; an LZW error in a cached DEM cell is a stale range cache (delete the cell; T269); a sweep part can lose
  its emulator right after a guard reboot (one rerun; T273 adds the storage wait).
- **Gate:** 5-hour 11, 7-day 55 at 13:17 UTC (the allowance's 53 at the close, within the band; 2.5 a task stands).

## Next

1. On the owner's go: launch wave 32's first half by the `wave` skill (T262 in lane A with console 5570 and host
   8694, T264 in lane B with 5572 and 8696, T271 alongside; verifiers on 5574/8698 and 5576/8700); briefs by the
   `brief` skill from the task entries, the code maps in the session scratchpad (`wave32-map-*.md`) and the research.

## Waiting on user

- **Wave 32's go**, and its choices: T263's picker setting default (the research says skip the Record-as step; the
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
