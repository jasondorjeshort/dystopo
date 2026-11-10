# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-10, by main session (wave 31 open: sixteen of seventeen merged; v0.1.25-alpha published; T256 and T257 running)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` › Wave 31, `out/wave31-research/` [no-check: gitignored].

## Now

- **Wave 31 is open** (plan at e184b663; T261 added): seventeen tasks, two lanes plus three alongside. Merged: all
  but T256 and T257. **v0.1.25-alpha published** 2026-11-10 09:48 UTC (tag on 947d1e71, versionCode 1421; the
  mid-wave sweep green after a cache repair, `out/sweep-20261110-0254`; notes in `out/wave31-reports/` [no-check: gitignored]).
- Running since 04:55: T256's implementer in lane A (Opus; AVD `dystopo35-T256`, console 5570, host 8694) and
  T257's in lane B (Opus; `dystopo35-T257`, 5572, 8696). Verifier ports: 5574/8698 for A, 5576/8700 for B.
- Rules learned: no force push works here (merge `main` in, never rebase a pushed branch); a push on a line of its
  own; `git worktree remove` fails with "Filename too long", so cleanup chores get the `\\?\` delete; a
  deterministic LZW or Deflate error in a cached DEM cell is a stale range cache: delete that cell's directory.
- **Gate:** the 5-hour meter hit 50 twice (17:04, 00:29); the owner chose no grant; the lanes resumed at the resets;
  5-hour 15, 7-day 51 at 04:55. Two Gradle agents at once: one Kotlin compiler OOM per `check` (green on retry).

## Next

1. T256 and T257 report: a verifier each (Sonnet, `tools/verify-task.ps1`), the `wave` skill's merge sequence, the
   log, a cleanup chore. Then the close by the `wave` skill: the post-wave sweep (`-Prev out/sweep-20261110-0254`),
   the second release (v0.1.26-alpha, ADR 0024), the meter against 53 (revised if five points off), a Haiku chore
   moving wave 31 to `docs/tasks/wave-31.md` [no-check: not yet written], then plan wave 32 from the wave
   paragraph's Not-taken list, the build orders in ADR 0060 and ADR 0061, and this wave's Later follow-ups.

## Waiting on user

- ADR 0060: the refresh default (automatic, your "just work", against ADR 0030's explicit refresh); the helper's
  keep-warm list as a new data flow; the daily limit's default, and country downloads past OpenFreeMap's limit.
- ADR 0061: the first-run card while the pack list loads (F1 wait, F2 unknown until known, F3, F4); F1 built first.
- The Menu choice (Layout C, wave 32); T220; proposals 0054 and 0055; v0.1.25-alpha's start over the large
  download; the owner's-eye items of the sixteen merges (ADR 0031, the board's Status lines).

## Open findings

- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; the snapshot loop is wave 32's.
- `docs/findings/2026-11-10-range-cache-keeps-blocks-across-a-republish.md`: 56 stale cells deleted; a validator is wave 32's.
