# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-01, after the wave 26 launch, by main session (wave 26 open, five implementers running)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` (the open
wave 26 and its RAM paragraph; Later carries waves 24 and 25's follow-ups; the record `docs/tasks/wave-25.md`).

## Now

- **Wave 26 is open** (planned and launched 2026-11-01, commit 07b570a9; the critic's review and the T203
  survey under `out/wave26-research/`, gitignored). Five implementers run in `~\Documents\src\dystopo-wt\T202`
  to `T206` on `task/T202` to `task/T206`: T202 (Opus, missing graphs built at server start, port 8690), T203
  (Opus, the terrain position source, emulator `dystopo35-T203` console 5570, host 8694), T204 (Opus, the
  readout's defects, `dystopo35-T204` console 5572, host 8696), T205 (Sonnet, the site's follow-ups, ports 8698,
  8699, 8443), T206 (Opus, the controls inventory and proposal 0053, no code). Nothing verified or merged yet.
- **The RAM rule is binding** (the owner's one request): two emulators at the cap, no-daemon builds with the
  in-process Kotlin compiler, servers from `installDist` with `-Xmx2g`, at most two verifiers at once.
- **The site is built but not installed.** The owner's five steps are on the board (Owner actions pending › The
  home site). The host name is a private term: never in a tracked file, brief or report.
- The live viewer on 8765 is up (PID in `out/coordinator-pids.txt`). Meter at launch: 27 on the 7-day.

## Next

1. As each implementer reports: a Sonnet verifier (`brief` skill, `verifier.md`; ports and AVDs in the wave
   paragraph), the crossing scenario for whichever of T203 and T204 merges second. T206's report gets the
   proposal critic (`proposal` skill, `brief-critic.md`, the most capable model), then the implementer revises.
2. At T203's merge, add its two reference-doc lines (from its report) to `main`; at T205's merge, an Owner
   actions line for the elevated run of the ports script. [no-check: the script is T205's new file]
3. Close: README status paragraph, the sweep (`sweep` skill; its emulator under the same RAM rule), an alpha
   (T203 and T204 change app code), the handoff, the record chore; the allowance's close reading.
4. Later: a Hatchery-rooted session for the pending carry-backs; the 13 untriaged ideas (`triage` skill).

## Waiting on user

- The home site's five steps and what holds port 80.
- The paging file (12.8 GB on C:, set 32 GB or automatic); install v0.1.17-alpha and the hike checks; the
  `Question:` blocks; ADR 0051's two answers; the git housekeeping row.

## Open findings

- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: T189 ships the graph; the phone next.
