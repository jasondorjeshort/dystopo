# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-28, at commit 8fbc94d plus this edit, by main session (wave 21: v0.1.14-alpha published, T157 in flight)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` › Wave 21.

## Now

- **v0.1.14-alpha is published** (2026-10-28, built locally by the `release` skill's fallback because the repository has
  no `RELEASE_*` secrets; verified and installed from the draft; the log entry is in PLANNING). Wave 21 ran **one agent
  at a time** (owner, 2026-10-27); eight of nine tasks are merged and logged, branches and worktrees cleaned: T149
  (dbe6e69), T150 (98c9a9d), T152 (3f0af48), T151 (213bb38), T156 (5412c7b), T153 (2d94d75), T155 (ad31b31), T154
  (9830d8d). The post-wave sweep was T154's (`out/sweep-w21/`, green but `notices`, fixed on its branch).
- **T157 (the middle detail tier) is the last task**; its implementer (Opus) was launched 2026-10-28 on worktree
  `~/Documents/src/dystopo-wt/T157`, AVD `dystopo35-T157`, console 5630, host 8750. It merges after v0.1.14-alpha
  and ships in the next release. Merge drafts: `%TEMP%/dystopo-wave21/`; research: `out/wave21-research/`.
- Usage gate: the weekly hold is 100 (`policy.json`); the 7-day meter read 97% at T157's launch and resets 2026-10-30
  about 04:00 UTC. If the gate closes before T157's verifier, the branch waits for the reset.
- Machine (`machines.md`, the second PC): 3 GB emulators, at most three, Gradle `--no-daemon`, the live viewer on 8765;
  the owner's phone is on wireless adb, so agents address only their emulator by serial; no `devtool.local.json`.

## Next

1. When T157 reports: a Sonnet verifier (host 8790, console 5660), the merge sequence (`wave` skill), the log entry,
   a Haiku cleanup chore, the handoff. No release for T157 alone unless the owner asks.
2. Plan wave 22 (`wave` skill, critic first) from the wave 21 Status follow-ups: the streaming or server-shipped
   routing graph (a city-sized pack is refused on a 512 MB heap; proposal first), precomputed country sizes, the
   on-disk tile cache index, `PlaceSearch.peek` on the main thread, the `sweep.ps1` switches; and the phone results.
3. A Hatchery-rooted session (carry-backs, the ownership idea, ADR 0021's follow-ups, the second base update; the ADR
   for T144's reference counting); ADR 0040's and 0042's build tasks after the owner.

## Waiting on user

- The owner's list is `docs/TASKS.md` › Owner actions pending (2026-10-28): install v0.1.14-alpha and watch Report a
  problem and the background CPU; the wave 18 to 21 strings; the decisions (auto-resume toggle, ADR 0040 and 0042, S Pen,
  Play); the git housekeeping; the `RELEASE_*` secrets (every release is a local build until set); the first PC's pack recipe.

## Open findings

- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: T149, T150 merged; the phone next.
