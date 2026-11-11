# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-11 15:30 UTC, by main session (T275 merged; the post-wave sweep next)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` › Wave 32, `out/wave32-research/` [no-check: gitignored].

## Now

- Wave 31 closed (`docs/tasks/wave-31.md`); **v0.1.26-alpha published** 2026-11-10 (tag on e5331b9c, versionCode 1445).
- **Wave 32 first half** (launched 2026-11-10 17:40 UTC on the owner's go; plan 298987de, fixes 93636d62; research
  and the critic's report in `out/wave32-research/` [no-check: gitignored]): **T271, T262, T264, T269, T272, T265,
  T274 and T275 merged** (log entries of 2026-11-10 and -11). Nothing runs. Per-agent costs: the cost finding's wave 32 line.
- Rules: no force push (merge `main` in); a push on its own line; an LZW error in a cached DEM cell is a stale range
  cache (T269); a sweep part can lose its emulator after a guard reboot (one rerun; T273).

## Next

1. The cleanup chore for T275 (branch, worktree `dystopo-wt/T275`, the long-path `T275-verify` folder, AVD
   `dystopo35-T275`), then the post-wave sweep (`sweep` skill) on `main` at 7b0fd2eb or later.
2. Sweep green: the alpha by the `release` skill; then the second half after the 7-day reset (read it fresh;
   2026-11-14 03:00 UTC on this account, 75 at 13:35 UTC on 11-11).
3. The owner removes the empty `C:\c` folder T264's first tilebuild run left (the safety check refused the agent).

## Waiting on user

- Answered 2026-11-11 (`docs/TASKS.md` › Owner actions pending, "Answered 2026-11-11", and Later's first entry).
- The owner's-eye items of wave 31's merges (ADR 0031, the record's Status lines) and the real-phone checks of
  v0.1.26-alpha: the owner reads them "soon enough".
- The daily-limit research is in (`out/wave32-research/daily-limit-and-openfreemap.md` [no-check: gitignored]):
  OpenFreeMap's terms cap each download at 3,000 tiles or 50 MB, which a split would evade; asked the owner to
  pick (email them, a server for big downloads, or both) and the limit (1 GB a day; a separate mobile one?). The 0054 glacier detail (permanent snow without a
  mapped glacier) when its ADR is written.

## Open findings

- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; the snapshot loop is T273.
- `docs/findings/2026-11-10-range-cache-keeps-blocks-across-a-republish.md`: 56 stale cells deleted; the validator is T269.
- `docs/findings/2026-11-10-wave-cost-doubled-and-the-lane-width-does-not-explain-it.md`: read the halves at wave 32's close.
