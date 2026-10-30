# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-30, at commit 1c60fac plus this edit, by main session (wave 22: T168 and T158 merged, step B done)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` › Wave 22 (its paragraph holds the launch order, the ports and the emulator rule).

## Now

- **Wave 22 is open** (17 tasks, T158 to T174; planned, researched and past the critic on 2026-10-30, commit 97e01cd).
  **Merged:** T168 (c36f666; ADR 0050 and step B done: waves 1 to 21 are in `docs/tasks/`, the board 165 lines),
  T158 (1c60fac). **In flight:** T162 (verifying), T165 (verifying), T170, T167, T166, T160, T171 (implementing),
  T169 (proposal 0049 pushed, the critic reading it; then the implementer revises, a verifier, the merge, ADR 0049).
- **Emulator rule (owner, 2026-10-30):** `-Memory 3072`, at most three AVDs alive, at most two emulator-needing
  implementers at a time, the third slot for a verifier. Launch next: T165 and T167 (pure) after the first batch's
  `check` runs; then T169 and T166; T160 and T159 after T158 and T162 merge; T171 after T158; T172 after T159;
  T163 when an emulator slot frees; T164 after T171; T161 after T160 and T172; T173 after T161; T174 after T165.
- Research, the critic review and the board draft: the gitignored `out/wave22-research/`; scratchpad `%TEMP%/dystopo-wave22/`.
- The live viewer is up on 8765 (PID 31104, `out/coordinator-pids.txt`). Usage at launch: 7-day 3, 5-hour 13.
- Still to launch: T159 (after T162 merges), T172 (after T159), T163 (emulator slot), T164 (after T171), T161
  (after T160 and T172), T173 (after T161), T174 (after T165).

## Next

1. Verify, merge and log each branch by the `wave` skill's merge sequence; launch the next task per the order above.
2. Close the wave: the sweep, the alpha, the three triage-scan chores of 2026-10-29 (ADR 0039, 0041, 0044
   predictions), the allowance reading in the wave paragraph.
3. A Hatchery-rooted session (carry-backs, ADR 0048's included, the ownership idea, ADR 0021's follow-ups, the second
   base update; the ADR for T144's reference counting); ADR 0040's and 0042's build tasks after the owner.

## Waiting on user

- `docs/TASKS.md` › Owner actions pending (2026-10-28): install v0.1.14-alpha and watch Report a problem and the
  background CPU; the wave 18 to 21 strings; the decisions (auto-resume toggle, ADR 0040 and 0042, S Pen, Play); the
  git housekeeping; the `RELEASE_*` secrets; the first PC's pack recipe. New this wave: T159 will report why an
  ongoing notification can still be swiped on Android 14 and up.

## Open findings

- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: T149, T150 merged; the phone next.
- `docs/findings/2026-10-28-open-view-ignores-the-location-fix.md`: T162 in flight resolves it.
