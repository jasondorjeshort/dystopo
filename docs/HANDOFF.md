# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-10-29, at commit 3b30b84 plus this edit, by main session (ADR 0048 built by hand; wave 22 not yet planned)
Resume: this file, `docs/reference/machines.md` (the second PC), the `wave` skill, `docs/TASKS.md` › Wave 21 and Later.

## Now

- **Wave 21 is closed.** Nine of nine tasks merged and logged, run **one agent at a time** (owner, 2026-10-27): T149
  (dbe6e69), T150 (98c9a9d), T152 (3f0af48), T151 (213bb38), T156 (5412c7b), T153 (2d94d75), T155 (ad31b31), T154
  (9830d8d), T157 (f177758). **v0.1.14-alpha is published** (2026-10-28, from 965910c, before T157; built locally by
  the `release` skill's fallback because the repository has no `RELEASE_*` secrets; verified and installed from the
  draft). T157 (the middle detail tier, a version-3 record format) is on main unreleased and ships with the next alpha.
- The post-wave sweep was T154's (`out/sweep-w21/`, green but `notices`, fixed on its branch). Nothing is in flight.
  Merge drafts: `%TEMP%/dystopo-wave21/`; research and the critic review: the gitignored `out/wave21-research/`.
- **ADR 0048 is in** (2026-10-29, the coordinator's hand on `main`): waves sized by whole cost with an allowance
  sentence in the wave paragraph; an optional **Interacts with:** line per task, a crossing scenario for its verifier.
- Usage gate: the weekly hold is 100 (`policy.json`); the 7-day meter reset on 2026-10-30 and read 2% after it.
- Machine (`machines.md`, the second PC): 3 GB emulators, at most three, Gradle `--no-daemon`, viewer on 8765; the
  owner's phone is on wireless adb, so agents address only their emulator by serial; no `devtool.local.json`.

## Next

1. Plan wave 22 (`wave` skill, critic first), **hiking features** (owner, 2026-10-29; order is the coordinator's):
   the recorder with activity profiles, the live readout, centring on open, the hiking `Plan:` ideas (triaged
   2026-10-29); plus the wave 21 follow-ups (TASKS › Later, the T149 to T157 Status lines; the routing graph
   proposal first), the closed-waves idea (step A a task, step B a chore after it) and the owner's phone results.
   The wave paragraph carries the first allowance sentence (ADR 0048).
2. A Hatchery-rooted session (carry-backs, ADR 0048's included, the ownership idea, ADR 0021's follow-ups, the second base update; the ADR
   for T144's reference counting); ADR 0040's and 0042's build tasks after the owner.

## Waiting on user

- The owner's list is `docs/TASKS.md` › Owner actions pending (2026-10-28): install v0.1.14-alpha and watch Report a
  problem and the background CPU; the wave 18 to 21 strings; the decisions (auto-resume toggle, ADR 0040 and 0042, S Pen,
  Play); the git housekeeping; the `RELEASE_*` secrets (every release is a local build until set); the first PC's pack recipe.

## Open findings

- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: T149, T150 merged; the phone next.
