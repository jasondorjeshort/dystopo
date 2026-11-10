# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-10, by main session (wave 31: every task merged; the post-wave sweep running; the close and v0.1.26-alpha next)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` › Wave 31, `out/wave31-research/` [no-check: gitignored].

## Now

- **Wave 31 is merged** (plan at e184b663): every task in, T256 at e08e9c2b and T257 at 7ed8f5c3 (one fix round)
  today; **v0.1.25-alpha published** 2026-11-10 09:48 UTC (947d1e71, versionCode 1421). Both lanes cleaned up.
- Running since 06:50: the post-wave sweep (Sonnet; `tools/sweep.ps1 -NoDaemon -Prev out/sweep-20261110-0254
  -First route-card,track,map,readout,settings`, consoles 5640 and 5642; one to two hours). The v0.1.26-alpha notes
  are drafted in the session scratchpad (`notes-v0.1.26-alpha.md`; the changes line and the checksums to fill).
- For wave 32: the `triage` skill ran (4733f465: 18 Plans, 4 Questions) and the ADR 0012 scan's four hits are recorded.
- Rules learned: no force push works here (merge `main` in, never rebase a pushed branch); a push on a line of its
  own; an LZW error in a cached DEM cell is a stale range cache (delete that cell's directory).
- **Gate:** held twice at 50 on the 5-hour meter (no grant, resumed at the resets); 5-hour 3, 7-day 52 at 06:57.

## Next

1. Sweep green: the `release` skill for v0.1.26-alpha (CHANGELOG cut, tag, draft, an agent verifies and installs
   from the draft with `upgrade` from v0.1.25-alpha, `changes.png` attached, notes, publish, record); red: a fix
   task or one rerun. Then the close by the `wave` skill: the meter into the allowance sentence (about 53 expected;
   revise the figure only if five off), the handoff, a Haiku chore moving wave 31 to `docs/tasks/` by its README.
2. Plan wave 32 (written and criticised; launched on the owner's word) from the ideas' Plans (the tracks and routes
   manager, the activity as the app's mode, the whole world after the basemap, the launch budget after ADR 0061's
   step 1, the accessibility part), ADR 0060's and ADR 0061's build orders, and this wave's Later follow-ups.

## Waiting on user

- ADR 0060: the refresh default, the keep-warm list as a new data flow, the daily limit's default, country downloads.
- ADR 0061: the first-run card while the pack list loads (F1 wait, F2 unknown until known, F3, F4); F1 built first.
- The Menu choice (Layout C); T220; proposals 0054 and 0055; the owner's-eye items of the seventeen merges (ADR 0031);
  the four triage Questions (the flight's freeze report, the accelerometer, a raster cache, two channels at beta).

## Open findings

- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; the snapshot loop is wave 32's.
- `docs/findings/2026-11-10-range-cache-keeps-blocks-across-a-republish.md`: 56 stale cells deleted; a validator is wave 32's.
- `docs/findings/2026-11-10-wave-cost-doubled-and-the-lane-width-does-not-explain-it.md`: read the halves at wave 32's close.
