# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-11 17:00 UTC, by main session (v0.1.27-alpha published; the second half waits for the reset)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` › Wave 32, `out/wave32-research/` [no-check: gitignored].

## Now

- **v0.1.27-alpha published** 2026-11-11 (tag on 5d7b641d, versionCode 1501): wave 32's first half; sweep
  `out/sweep-20261111-1012` [no-check: gitignored] green.
- **Wave 32 first half** (launched 2026-11-10 17:40 UTC on the owner's go; plan 298987de, fixes 93636d62; research
  and the critic's report in `out/wave32-research/` [no-check: gitignored]): **T271, T262, T264, T269, T272, T265,
  T274 and T275 merged** (log entries of 2026-11-10 and -11). Nothing runs. Per-agent costs: the cost finding's wave 32 line.
- Rules: no force push (merge `main` in); a push on its own line; an LZW error in a cached DEM cell is a stale range
  cache (T269); a sweep part can lose its emulator after a guard reboot (one rerun; T273).

## Next

1. Finish T275's cleanup: `dystopo-wt/T275` is held open by a process (find it by PID, not by name), then
   `worktree prune`, `branch -d task/T275`, AVD `dystopo35-T275`.
2. Wave 32's second half after the 7-day reset (read it fresh; 2026-11-14 03:00 UTC on this account, 75 at 13:35 UTC
   on 11-11, 80 on 11-12): **T276 first** (the owner's priority for the next release, 2026-11-12: an everyday route
   hides the backcountry lines), then **T277** (the profile's Close hidden on the owner's phone; the owner wants it in the next release too), T263, T266, T268, T267, T270, T273, then a sweep and a release.
3. The owner removes the empty `C:\c` folder T264's first tilebuild run left (the safety check refused the agent).

## Waiting on user

- Answered 2026-11-11 (`docs/TASKS.md` › Owner actions pending, "Answered 2026-11-11", and Later's first entry).
- The owner's-eye items of wave 31's merges (ADR 0031, the record's Status lines) and the real-phone checks of
  v0.1.26-alpha: the owner reads them "soon enough".
- The daily-limit research is in (`out/wave32-research/daily-limit-and-openfreemap.md` [no-check: gitignored]):
  OpenFreeMap's terms cap each download at 3,000 tiles or 50 MB, which a split would evade; asked the owner to
  chose both (cap direct mode now: Later's first entry; the owner emails them); the daily limit is 1 GB. The 0054 glacier detail (permanent snow without a
  mapped glacier) when its ADR is written.

## Open findings

- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; the snapshot loop is T273.
- `docs/findings/2026-11-10-range-cache-keeps-blocks-across-a-republish.md`: 56 stale cells deleted; the validator is T269.
- `docs/findings/2026-11-10-wave-cost-doubled-and-the-lane-width-does-not-explain-it.md`: read the halves at wave 32's close.
