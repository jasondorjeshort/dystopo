# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-15 07:00 UTC, at the commit after cfb19f53, by main session (wave 33 closed, v0.1.30-alpha published)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` (no wave is open),
`docs/tasks/wave-33.md` for the closed wave, ADR 0062 and 0063.

## Now

- **v0.1.30-alpha is published** (2026-11-15 06:44 UTC, tag on cfb19f53, versionCode 1676): wave 33, six tasks
  over v0.1.29-alpha (T281, T282, T283, T284, T286, T285): drive routes pay for turns, and Plan drives for
  (Least time, Least fuel, Quicker planning). Its sweep is `out/sweep-20261114-2352` (green, no rerun; the next
  release's `-Prev`). The log has the entry.
- **Wave 33 is closed**: its record is `docs/tasks/wave-33.md`, its follow-ups are in the board's Later, and no
  agent, emulator or task branch is left. The meter read 39 at the close against a figure of 43 (1.0 a task stays).
- **The release's sheet was rebuilt again**, without five frames showing a map outside `areas.json` (now also the device tour's two navigation-card frames); cause unknown, a Later line.
- The live viewer is wanted (`out/coordinator-pids.txt`). Rules: no force push; a false `:devtool:compileTestKotlin` failure or an out-of-memory Kotlin compile clears with `:devtool:clean check "-Pkotlin.daemon.jvmargs=-Xmx4g"`; a worktree folder with long paths goes by the `\?\` delete.

## Next

1. **Plan the next wave** (critic first, `wave` skill): the graph builder interpolating tunnel and bridge
   heights between portals (T286's follow-up: it ends the tile-border remainder and corrects the profile, hike
   and bike); a drive in the sweep (Later's first line: no part opens Plan drives for or a drive's card);
   whether the `montblanc` pack lacks road names or the data does (T282).
2. The other drive ideas (curvature, stops and signals, shortest) need research; the pressure-unit and Send diagnostics ideas are untriaged (`triage` skill).
3. Then the board's Later, the ideas' Plans and the two ADR 0012 hits (0061, 0053).

## Waiting on user

- **On the phone, v0.1.30-alpha** (ADR 0031, the owner adjusts): the names Least time, Least fuel, Quicker
  planning and their help; the card's "about N % less fuel than the least-time route, M min longer" (and "under a
  minute longer", "no longer", the coordinator's wording); whether a town's 33 % and 47 % read as believable (the
  model's turn fuel, T284's Status); the owner's own bug (two left turns onto a side road). Strings: the merge
  bodies of T282 and T285.
- Still from v0.1.29-alpha: the activity chip, Trip details; OpenFreeMap's terms (the owner emails them). Left for the owner: the empty folders `dystopo-wt/T275` (busy) and `C:\c`, a stray `msg.txt` in `dystopo-wt`.

## Open findings

- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; cause unproven.
- `docs/findings/2026-11-10-range-cache-keeps-blocks-across-a-republish.md`: 56 stale cells deleted; the validator is T269.
