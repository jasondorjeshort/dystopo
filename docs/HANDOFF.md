# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-15 18:20 UTC, at the commit after 1964fbc2, by main session (wave 33's ideas triaged; no wave open)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` (no wave is open),
`docs/tasks/wave-33.md` for the closed wave, ADR 0062 and 0063, and the blocks in `docs/ideas/2026-11-1[45]-*.md`.

## Now

- **v0.1.30-alpha is published** (2026-11-15, tag on cfb19f53, versionCode 1676). Its sweep is
  `out/sweep-20261114-2352` (green; the next release's `-Prev`). Wave 33 is closed; no agent, emulator or task
  branch is left.
- **The fourteen ideas of 2026-11-14 and 15 are triaged** (ten Plans, four Questions, one commit a file), and the
  ADR scan filed two chores (ADR 0062 and 0063, merge predictions due). The scan read only what wave 33 made due;
  older open predictions were not re-read and the fitness run was skipped.
- **T284's 33 % and 47 % are probably the model, not real**: published least-fuel savings average 5 to 10
  percent. The speed-profile idea's Plan is the check. The surveys behind the blocks were thin on consumer apps.
- The live viewer is wanted (`out/coordinator-pids.txt`). Rules: no force push; a false `:devtool:compileTestKotlin` failure or an out-of-memory Kotlin compile clears with `:devtool:clean check "-Pkotlin.daemon.jvmargs=-Xmx4g"`; a worktree folder with long paths goes by the `\?\` delete.

## Next

The owner agreed this order on 2026-11-15; the wave is planned later (critic first, `wave` skill).

1. **Tier 1, the next wave, small tooling** (each idea's Plan is its brief): emulators boot with a public fix;
   the sweep opens every user-visible change (`--first` fails on no match, a drive step in `features`, the two
   skills' sentence); the route sheet; the cost breakdown (the cheap half of "why this route"); the speed
   profile, pass one. The last two both own devtool's `RouteCommand.kt`: one after the other.
2. **Tier 2, after the profile's finding**: the card offers the other route; your own hike numbers and the trip's
   pace slider; the winding-roads cap only if the finding earns it.
3. **Tier 3, one pack and graph revision** (a proposal, if the owner answers yes): driving side, road names,
   stop and signal points, tunnel and bridge heights (T286's follow-up, no longer a task alone). Chores that can
   run early: the `montblanc` pack's top zoom (road names are written at z14 only) and the stop and signal count.
4. **Tier 4**: bike turns across big roads (two passes); the two ADR chores of 2026-11-15 and the two of
   2026-11-14 (0061, 0053), the coordinator's; the pressure-unit Plan; then the board's Later.
5. `docs/findings/README.md` lists the update-check 504 finding as open; its file says resolved (T247).

## Waiting on user

- **Four Answers in `docs/ideas/`**: a Shortest drive (at rest recommended); energy on a charge (is there an
  electric car or e-bike to check against); the pace from your tracks (the hike fit exists in Route times: is it
  what was meant, and is the drive half wanted); pack facts (one proposal, yes recommended).
- **On the phone, v0.1.30-alpha** (ADR 0031): the three names and their help, the card's fuel line and its
  wording, the owner's own bug (two left turns onto a side road). Strings: the merge bodies of T282 and T285.
- Still from v0.1.29-alpha: the activity chip, Trip details; OpenFreeMap's terms (the owner emails them). Left for the owner: the empty folders `dystopo-wt/T275` (busy) and `C:\c`, a stray `msg.txt` in `dystopo-wt`.

## Open findings

- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; cause unproven.
- `docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`: open in its file.
