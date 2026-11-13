# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-13 11:40 UTC, at commit 2a84ae01, by main session (v0.1.28-alpha published; T267 in a third pass)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` › Wave 32 (its second paragraph,
"Second half, re-planned 2026-11-13"), `out/wave32-research/critic-wave32-second-half.md` [no-check: gitignored].

## Now

- **v0.1.28-alpha is published** (2026-11-13 11:26 UTC, tag on 2a84ae01): the early release, T276 and T277. The
  sweep before it was green without a rerun (`out/sweep-20261113-0446`, the next release's `-Prev`).
- **T267** (`task/T267`, head 90111f7b) is held on its branch: its fix round verified (MERGE WITH NOTES), but the
  tour's offline step failed twice there with no baseline. A third verifier is rerunning the tour; green twice
  means merge, red on `main` too means merge with a note, red only on the branch goes back to the implementer.
- Wave 32's second half continues: seven tasks left. The 7-day meter read 4 at 11:00 UTC (reset 2026-11-20 04:00 UTC).
- The live viewer is wanted and runs from the installed distribution (`out/coordinator-pids.txt`).
- Rules: no force push; a false `:devtool:compileTestKotlin` failure or an out-of-memory Kotlin compile clears with
  `:devtool:clean check "-Pkotlin.daemon.jvmargs=-Xmx4g"`; a worktree folder with long paths goes by the `\\?\` delete.

## Next

1. T267's verdict, then its merge sequence, cleanup chore (`dystopo-wt/T267` and `T267-verify`) and log entry.
2. Lane A: **T278** next (its brief carries T276's keys and gates, from T276's merge body ec21da97, and the card's
   "Based on a typical hiking pace" under Walk), then T263, T266 (after T267), T268.
3. Lane B after T267: T273, then T270 only after T263 has merged.
4. The closing sweep and alpha, the wave's close (the allowance sentence's reading, the record chore).

## Waiting on user

- The owner closes whatever window sits in the empty folders `dystopo-wt/T275` and `dystopo-wt/T277-verify`, then
  removes them; and removes the empty `C:\c` folder T264's first tilebuild run left.
- On the phone, v0.1.28-alpha: the profile's Close uncovered (T277's cause was found on an emulated Android 12
  window, not on the phone), and Walk on a town route. The strings to read (ADR 0031) are in the two merge bodies.
- OpenFreeMap's terms cap a download at 3,000 tiles or 50 MB (Later's first entry; the owner emails them).

## Open findings

- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; the snapshot loop is T273.
- `docs/findings/2026-11-10-range-cache-keeps-blocks-across-a-republish.md`: 56 stale cells deleted; the validator is T269.
- `docs/findings/2026-11-10-wave-cost-doubled-and-the-lane-width-does-not-explain-it.md`: read the halves at wave 32's close.
