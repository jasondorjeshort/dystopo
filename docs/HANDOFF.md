# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-30, at commit 1083c17 plus this edit, by main session (wave 23 planned, past the critic, first batch launched)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` › Wave 23 (its paragraph holds the slots, ports, launch order and ownership), the surveys and the critic review in the gitignored `out/wave23-research/`.

## Now

- **Wave 23 is open** (fourteen tasks, T176 to T189, planned 2026-10-30 from the owner's hike priorities; seven
  surveys and the critic the same day, every objection taken; commits 7bb2aae and 1083c17). **Launched 2026-10-30,
  all Opus:** T176 (slot A, emulator console 5630, host 8750), T180 (slot B, console 5638, host 8758), T179, T181 and
  T186 without emulators, T189 on its server and `shared` halves (its phone half waits on a slot and on T184).
  Not yet launched, in order: T177 when slot A frees, T185 after T176 merges, T178 after T177, T187 after T180 and
  T179, T188 after T181, T182 after T181 (phone files after T188), T184 after T187, T183 when a slot frees.
- The live viewer is up on 8765 (PID 31104, `out/coordinator-pids.txt`). Usage at launch: 5-hour 16, 7-day 28.
- Owner words this session (2026-10-30): "We can probably fit all of that in the budget"; on the storm warning,
  "we're gaining thousands of feet of elevation so have to use QNH" (T178: the reduction to a local reference height
  with one fixed temperature per window; the critic's § 1 and 12 corrected the survey's method).

## Next

1. As each implementer reports: a Sonnet verifier (`brief` skill, `verifier.md`; the third emulator is its slot),
   then the `wave` skill's merge sequence; launch the next task of the chain when a slot frees. T189's first round
   may merge before its phone half; T186's proposal gets the `proposal` skill's critic from the coordinator, then
   ADR 0051.
2. At the close: the sweep, the release (the `RELEASE_*` secrets were set 2026-10-30: check the draft is
   release-signed), the handoff, the wave record, the allowance reading.
3. Later: a Hatchery-rooted session (five pending carry-backs per the triage of d14c2c1; the triage skill's
   carry-back step classifies against the wrong `main` in project trees); ADR 0040's and 0042's build tasks after
   the owner; ADR 0049's C after the z10 answer.

## Waiting on user

- `docs/TASKS.md` › Owner actions pending; the seven `Question:` blocks of 2026-10-30 in `docs/ideas/`; the hike
  checks (Later, 2026-10-30) once the next alpha carries T176, T177, T178 and T180.

## Open findings

- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: T149, T150 merged; T189 ships
  the graph; the phone next.
