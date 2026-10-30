# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-30, at commit 37e4f6f plus this edit, by main session (wave 22: 15 of 17 merged)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` › Wave 22 (its paragraph holds the launch order, the ports and the emulator rule).

## Now

- **Wave 22 is open** (17 tasks, T158 to T174; planned, researched and past the critic on 2026-10-30, commit 97e01cd).
  **Merged (15):** T168 (ADR 0050, step B done), T158, T165, T169 (proposal 0049, ADR 0049: B first, C on the
  owner's z10 answer), T162, T167, T166, T171, T160, T174, T159, T170, T164, T163, T172 (154df4f). **In flight:**
  T161 implementing (Opus, console 5636, host 8756); the T172 cleanup chore. **Still to launch:** T173 (after T161).
  Local pre-rebase branch copies (`task/T169`, `T166`, `T160`, `T172`) are on the owner's housekeeping list.
- **Emulator rule (owner, 2026-10-30):** `-Memory 3072`, at most three AVDs alive, at most two emulator-needing
  implementers at a time, the third slot for a verifier. The Kotlin compiler ran out of memory under five parallel
  `check` builds plus three emulators several times (a two-minute wait and a retry passed each time).
- Research, the critic review and the board draft: the gitignored `out/wave22-research/`; scratchpad `%TEMP%/dystopo-wave22/`.
- The live viewer is up on 8765 (PID 31104, `out/coordinator-pids.txt`). Usage: 7-day 3 at launch, 12 after 11 merges; read again at the close.

## Next

1. Verify, merge and log each branch by the `wave` skill's merge sequence; launch the next task per the order above.
2. Close the wave: the sweep, the alpha, the three triage-scan chores of 2026-10-29 (ADR 0039, 0041, 0044
   predictions), the allowance reading in the wave paragraph.
3. A Hatchery-rooted session (carry-backs, ADR 0048's included, the ownership idea, ADR 0021's follow-ups, the second
   base update; the ADR for T144's reference counting); ADR 0040's and 0042's build tasks after the owner.

## Waiting on user

- `docs/TASKS.md` › Owner actions pending (2026-10-28): install v0.1.14-alpha and watch Report a problem and the
  background CPU; the wave 18 to 21 strings; the decisions (auto-resume toggle, ADR 0040 and 0042, S Pen, Play); the
  git housekeeping; the `RELEASE_*` secrets; the first PC's pack recipe. New this wave: Android 14 lets users swipe an
  ongoing notification away (T159: the recording survives and it returns within 6 s); the z10 answer decides ADR 0049's C.

## Open findings

- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: T149, T150 merged; the phone next.
