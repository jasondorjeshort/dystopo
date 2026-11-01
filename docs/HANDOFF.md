# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-31, at commit 8bd5f9bb plus this edit, by main session (wave 24 launched)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` (the Wave 24
paragraph: Emulator slots, Launch day, Memory, Launch order, Past the critic; the previous wave: `docs/tasks/wave-23.md`).

## Now

- **Wave 24 is launched** (the owner's go, 2026-10-31, with one condition: "I really want to make sure we do not
  overwhelm the PC with emulators"; the paragraph's Launch day sentence is the rule until T190's lock enforces it).
  Five surveys done (`out/wave24-research/`, gitignored, summarised in each task's Research line); the critic's
  19 objections answered on the board (8bd5f9bb). **Merged and logged:** T198 (02fb2a95), T193 (013afa8a), T190
  (e1863320: the lock is live, `~/.dystopo/emulators/`, cap 2, guests at 3 GB). **Running:** T191's and T192's
  verifiers (slot V), T194's implementer (Opus). **Not launched:** T195 and T197 (after T192 merges), T196 (after
  T195 and T197). The live viewer on 8765 runs (PID in `out/coordinator-pids.txt`). ADR 0049's and 0050's
  Outcomes written (held), their ideas deleted; the orphan `T178` folders removed.
- Meters at launch: 7-day 60 (Fable 70), resets 2026-11-06 04:00 UTC (`/usage` shows no 5-hour meter here).

## Next

1. As each implementer reports: a Sonnet verifier on slot V (one at a time; T190's waits for an empty
   `adb devices`), then the merge sequence. T195 and T197 launch when T192 merges; T194 after T193; T196 after
   T195 and T197 (critic 9 suggests a sixth survey, exposed ground in storm guidance, before T196's brief).
2. Close: the sweep only with slot I empty (its two emulators are the cap), then the release (ADR 0024); the
   allowance sentence's close reading; the wave moved to its record [no-check: written at the close].
3. Later: a Hatchery-rooted session (the carry-backs); ADR 0040's, 0042's and 0049's C after the owner's answers.

## Waiting on user

- `docs/TASKS.md` › Owner actions pending: install v0.1.16-alpha and the hike checks; the `Question:` blocks of
  2026-10-30 and 2026-10-31; ADR 0051's two answers; the git housekeeping row. **The paging file is still the
  limit:** it reads 12.8 GB on C: and does not grow (commit limit 60 GB, 44 GB used mid-wave; T191 saw 1 GB free
  commit during a `check`); set it to 32 GB or more, or system-managed with automatic management on.

## Open findings

- `docs/findings/2026-10-31-sweep-hung-when-the-paging-file-ran-out.md` (T191 resolves it),
  `2026-10-31-appended-blocks-drop-their-closers-on-rebase.md` (the wave paragraph's sentinel rule) and
  `2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md` (T189 ships the graph; the phone next).
