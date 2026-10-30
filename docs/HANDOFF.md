# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-30, at commit f849a8d plus this edit, by main session (wave 22: all 17 merged, the sweep running)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` › Wave 22 (its paragraph holds the launch order, the ports and the emulator rule).

## Now

- **Wave 22 is closing** (17 tasks, T158 to T174, all merged on 2026-10-30, the last T173 at bde2a49; ADR 0049 and
  0050; the three triage-scan chores of 2026-10-29 done at ccb5af5: ADR 0041 held, 0039 undecidable, 0044 half held
  with a finding and the brief's report line fixed). **Running:** the post-wave sweep (Sonnet, `-NoDaemon -EmuMemory
  3072`, `out/sweep-wave22`; T170's green run) and the T173 cleanup chore. **Then:** the alpha (`release` skill,
  v0.1.15-alpha, the CHANGELOG cut, the README status line), the allowance reading in the wave paragraph, this file.
  Local pre-rebase branch copies (`task/T169`, `T166`, `T160`, `T172`) are on the owner's housekeeping list.
- **Emulator rule (owner, 2026-10-30):** `-Memory 3072`, at most three AVDs alive, at most two emulator-needing
  implementers at a time, the third slot for a verifier. The Kotlin compiler ran out of memory under five parallel
  `check` builds plus three emulators several times (a two-minute wait and a retry passed each time).
- Research, the critic review and the board draft: the gitignored `out/wave22-research/`; scratchpad `%TEMP%/dystopo-wave22/`.
- The live viewer is up on 8765 (PID 31104, `out/coordinator-pids.txt`). Usage: 7-day 3 at launch, 12 after 11 merges, 25 after all 17 (5-hour 37).

## Next

1. Read the sweep's report; a failed part becomes the next wave's sweep task (`finding` skill); green: the alpha.
2. Plan wave 23 from the owner's next theme: the deferred-by-name list in the wave 22 paragraph (sun and shade,
   the viewshed, the drainage toggle, the phone's sheet, the terrain fix's live source) and the Status follow-ups.
3. A Hatchery-rooted session (carry-backs, ADR 0048's included, the ownership idea, ADR 0021's follow-ups, the second
   base update; the ADR for T144's reference counting); ADR 0040's and 0042's build tasks after the owner.

## Waiting on user

- `docs/TASKS.md` › Owner actions pending (2026-10-28): install v0.1.14-alpha and watch Report a problem and the
  background CPU; the wave 18 to 21 strings; the decisions (auto-resume toggle, ADR 0040 and 0042, S Pen, Play); the
  git housekeeping; the `RELEASE_*` secrets; the first PC's pack recipe. New this wave: Android 14 lets users swipe an
  ongoing notification away (T159: the recording survives and it returns within 6 s); the z10 answer decides ADR 0049's C.

## Open findings

- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: T149, T150 merged; the phone next.
