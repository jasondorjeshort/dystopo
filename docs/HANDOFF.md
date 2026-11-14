# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-14 21:30 UTC, at commit fd4eee0c plus the plan commit, by main session (wave 33 planned, researched and through its critic; ADR 0062 written; nothing launched)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` (wave 33: T281, T282, T283,
strictly in order), ADR 0062, `docs/tasks/wave-32.md` for the closed wave.

## Now

- **v0.1.29-alpha is published** (2026-11-14 07:10 UTC, tag on 6eeb5358, versionCode 1593): the closing release of
  wave 32, nine tasks over v0.1.28-alpha (T267, T278, T263, T266, T268, T273, T279, T270, T280). Its sweep is
  `out/sweep-20261114-0027` (green, no rerun; the next release's `-Prev`). The log has the entries.
- **Wave 32 is closed**: its record is `docs/tasks/wave-32.md`, its follow-ups are in the board's Later (one line a
  task, the done ones struck), and no agent, emulator or task branch is left.
- **The sheet attached to the release was rebuilt** without four frames that show a map outside `areas.json` (the
  a11y part's first-run screens and `bothunits`' second phone); the cause is unknown (the log's publish entry).
- The wave skill's cost figure is 1.0 a task from this close (16 points for eleven tasks; the meter read 17).
- The live viewer is wanted (`out/coordinator-pids.txt`). Rules: no force push; a false `:devtool:compileTestKotlin` failure or an out-of-memory Kotlin compile clears with `:devtool:clean check "-Pkotlin.daemon.jvmargs=-Xmx4g"`; a worktree folder with long paths goes by the `\\?\` delete.

## Next

1. **Wave 33, drive routes pay for turns** (the owner, 2026-11-14: the next pass): on the board, its three
   surveys in `out/wave33-research/`, both critics answered in the wave paragraph, the decisions in ADR 0062 and
   0063. T281 (the directed-edge drive search at zero turn cost) is launched (the owner's go, 2026-11-14); then,
   each after the last one's merge: T282 (the turn values, the drive choice), T283 (the driving side), T284 (least
   fuel, the metric), T285 (least fuel on the phone; may move to the next wave); then the sweep and an alpha.
2. After it: the other drive ideas (curvature, stops and signals, shortest) need research; the
   pressure-unit and Send diagnostics ideas are untriaged; then the board's Later and the ideas' Plans (the
   sweep-order rule, the days-old recording, the backup proposal, the signing step, Report recent stats, the
   power-cut part, search outside a pack) and the two ADR 0012 hits (0061, 0053).

## Waiting on user

- **Answered 2026-11-14** (the board's Owner actions): the published v0.1.27 and v0.1.28 sheets stay; alpha upgrades
  need not be backward compatible (0033 stands until its amending ADR); the release key is used, never read.
- No idea Question is open: "Trim waits" is the owner's; the rest were decided by the owner's leave with a critic (two `Waits:` are the coordinator's by that leave, the owner's to overrule: the shared map picture, the sweep's reruns).
- On the phone, v0.1.29-alpha: the activity chip, Trip details. The strings of T263 and T278 to read (ADR 0031)
  are in their merge bodies. OpenFreeMap's terms (Later's first entry; the owner emails them).
- Two empty folders are left for the owner: `dystopo-wt/T275` (an `rmdir` reads busy: some window sits in it) and
  `C:\c` (the harness refused an agent's `rmdir` of it as a system path). `dystopo-wt/T267-verify` is gone.

## Open findings

- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; T273 measured it (the sweep keeps rebooting), cause unproven.
- `docs/findings/2026-11-10-range-cache-keeps-blocks-across-a-republish.md`: 56 stale cells deleted; the validator is T269.
