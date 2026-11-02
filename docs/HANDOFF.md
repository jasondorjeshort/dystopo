# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-02, at the wave 26 close, by main session (wave 26 closed, v0.1.18-alpha published)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` (no open
wave; Later carries waves 24 to 26's follow-ups; the record `docs/tasks/wave-26.md`), ADR 0053.

## Now

- **Wave 26 is closed** (T202 to T207 merged 2026-11-02; the close entry in `docs/PLANNING.md`'s log). The sweep
  was green after T207's fix; **v0.1.18-alpha is published** (verified and installed from the draft on an
  emulator; no phone was connected). Nothing is running: no worktree, no task branch, no emulator of ours
  (`adb devices` empty; one stray `emulator.exe` with no device was seen and left). The live viewer on 8765 is up
  (PID in `out/coordinator-pids.txt`). Meter at the close: 32 on the 7-day.
- **The RAM rule worked**, and the owner clarified it (2026-11-02): "We can use ram, we just can't be using lots
  of ram on multiple processes at once." So the next wave bounds the count of heavy processes (emulators at the
  cap, one compile per agent, few verifiers, servers from `installDist`, no idle daemons), not any one
  process's heap: the 3 GB compiler and 2 GB server caps of wave 26 were the coordinator's inference.
- **The site is built but not installed.** The owner's six steps are on the board (Owner actions pending › The
  home site). The host name is a private term: never in a tracked file.

## Next

1. The next wave is unplanned. Candidates: ADR 0053's three build steps (the map options first, no visible
   change); T203's pass 2 (the step detector and its permission prompt, flagged for the owner); the wave 26
   follow-ups in Later (T204's route card at font scale 2.0, the climb-cap figures' sources; T202's Prometheus
   counter); the 13 untriaged ideas (`triage` skill, also the ADR 0012 scan); the pack-mode stand-in page's
   scale bar if the owner wants one.
2. A Hatchery-rooted session: the pending carry-backs plus T190's two, T201's (`SubagentStop` fields), the
   sentinel rule.

## Waiting on user

- Install v0.1.18-alpha and the phone checks on the board (the terrain estimate under trees with a real
  barometer, the readout across a barometer silence, the strip at your font size); the T203 and T204 strings.
- The home site's six steps and what holds port 80; the inventory's Category column and ADR 0053's D1 to D6;
  the paging file (12.8 GB on C:); the `Question:` blocks; ADR 0051's two answers; the git housekeeping row.

## Open findings

- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: T189 ships the graph; the phone next.
