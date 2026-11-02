# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-02, after T203's merge, by main session (wave 26: all five merged, the post-wave sweep running)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` (wave 26 with
its five Status lines; Later carries waves 24 and 25's follow-ups; the record `docs/tasks/wave-25.md`), ADR 0053.

## Now

- **Wave 26 is merged, not closed.** T205 (ec0da360), T206 (8a402604, ADR 0053), T202 (442028b6), T204 (579ca364),
  T203 (ad37397f) are on `main`, each logged (`docs/PLANNING.md`, the board's Status lines); every branch,
  worktree and AVD of the wave is cleaned; the README status paragraph is updated. The post-wave sweep runs from
  the main checkout (a Sonnet agent, `-NoDaemon`, its folder under `out/` dated 2026-11-02); no verifier or
  implementer is running. The live viewer on 8765 is up (PID in `out/coordinator-pids.txt`).
- **The RAM rule held** (the owner's one request): no build or emulator incident this wave; verifier checks ran
  3 to 8 minutes under `--no-daemon` with the in-process Kotlin compiler.
- **The site is built but not installed.** The owner's six steps are on the board (Owner actions pending › The
  home site). The host name is a private term: never in a tracked file.

## Next

1. Read the sweep's summary and sheet; a finding per defect (`finding` skill); green: publish v0.1.18-alpha
   (`release` skill: CHANGELOG section, tag, the draft verified and installed from the draft, notes, publish,
   the log and README line).
2. Close (`wave` skill, Close the wave, steps 4 and 5): the handoff; the allowance's close reading in the wave
   paragraph (27 at planning, 47 budgeted); the Haiku record chore (the wave 26 record under `docs/tasks/`, the
   follow-ups lifted into Later).
3. Next wave candidates: ADR 0053's three build steps (the map options first); T203's pass 2 (the step detector
   and its permission prompt, flagged for the owner); T204's and T203's follow-ups; the 13 untriaged ideas
   (`triage` skill); a Hatchery-rooted session for the pending carry-backs.

## Waiting on user

- The home site's six steps and what holds port 80.
- The inventory's Category column (`docs/design/proposals/0053-controls-inventory.md`) and ADR 0053's D1 to D6,
  after the build; the strings of T203 and T204 (ADR 0031, in their merge commits); the paging file (12.8 GB on
  C:); install the next alpha and the hike checks; the `Question:` blocks; ADR 0051's two answers; the git
  housekeeping row.

## Open findings

- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: T189 ships the graph; the phone next.
