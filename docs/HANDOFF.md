# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-01, after the wave 26 launch, by main session (wave 26 open, five implementers running)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` (the open
wave 26, its RAM paragraph; Later carries waves 24 and 25's follow-ups; the record `docs/tasks/wave-25.md`).

## Now

- **Wave 26 is open** (planned and launched 2026-11-01, commit 07b570a9; the critic's review in
  `out/wave26-research/critic-wave26.md`, the T203 survey beside it, both gitignored). Five implementers run
  in `~\Documents\src\dystopo-wt\T202` to `T206` on branches `task/T202` to `task/T206`: T202 (Opus, the
  server builds missing graphs at start and on refresh, port 8690), T203 (Opus, the terrain position source,
  emulator `dystopo35-T203` on console 5570, host 8694), T204 (Opus, the readout's defects, `dystopo35-T204`
  on console 5572, host 8696), T205 (Sonnet, the site's follow-ups, ports 8698, 8699, 8443), T206 (Opus, the
  controls inventory and proposal 0053, no code). Nothing verified or merged yet; no verifier launched.
- **The RAM rule is binding** (the owner's one request this wave): two emulators at the cap, no-daemon
  builds with the in-process Kotlin compiler, servers from `installDist` with `-Xmx2g`, at most two
  verifiers at once (so the coordinator launches a verifier only when fewer than two run).
- **The site is built but not installed.** The owner's five steps are on the board (Owner actions pending › The
  home site). The host name is a private term: never in a tracked file, brief or report.
- The live viewer on 8765 is up (PID in `out/coordinator-pids.txt`; `/catalog` 200). Meter at launch: 27 on
  the 7-day, 3 on the 5-hour.

## Next

1. As each implementer reports: a Sonnet verifier (`brief` skill, `verifier.md`; ports and AVDs in the wave
   paragraph: T202v 8692, T203v 8695 console 5574, T204v 8697 console 5576, T205v 8700/8701/8444), the
   crossing scenario for whichever of T203 and T204 merges second. T206's report gets the proposal critic
   (`proposal` skill, `brief-critic.md`, the most capable model), then the implementer revises.
2. At T203's merge, the coordinator adds its two reference-doc lines (from its report) to `main`; at T205's
   merge, an Owner actions line for the elevated `tools/ports.ps1` run.
3. Close: README status paragraph, the sweep (`sweep` skill; its emulator under the same RAM rule), an
   alpha (T203 and T204 change app code), the handoff, the record chore; the allowance's close reading.
4. Later: a Hatchery-rooted session for the pending carry-backs; the 13 untriaged ideas (`triage` skill).

## Waiting on user

- The home site's five steps and what holds port 80 (the ports script of T205 helps once run elevated).
- The paging file (12.8 GB on C:, set 32 GB or automatic); install v0.1.17-alpha and the hike checks; the
  `Question:` blocks; ADR 0051's two answers; the git housekeeping row.

## Open findings

- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: T189 ships the graph; the phone next.
