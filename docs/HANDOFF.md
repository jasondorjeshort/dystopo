# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-13 23:55 UTC, at commit 0264deaf, by main session (the closing sweep red on two parts; T280 launching)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` › Wave 32 (its second paragraph,
"Second half, re-planned 2026-11-13"), `out/wave32-research/critic-wave32-second-half.md` [no-check: gitignored].

## Now

- **v0.1.28-alpha is published** (2026-11-13 11:26 UTC, tag on 2a84ae01): the early release, T276 and T277. The
  sweep before it was green without a rerun (`out/sweep-20261113-0446`, the next release's `-Prev`).
- **Eight tasks are merged after it** (T267, T278, T263, T266, T268, T273, T279, T270), all for v0.1.29-alpha.
  **The closing sweep of 0264deaf was red** (`out/sweep-20261113-1645`, the log's entry): `startup` twice (`world`
  and `assets` near 40 ms against ceilings of 22 and 21) and `a11y` once (green in its rerun); the rest green.
- **T280** (the board; an eleventh task) fixes both: an Opus implementer, a Sonnet verifier, the merge, a second
  full sweep through a Sonnet agent (no commit to `main` while it runs), then the release.
- The live viewer is wanted (`out/coordinator-pids.txt`). Rules: no force push; a false `:devtool:compileTestKotlin` failure or an out-of-memory Kotlin compile clears with `:devtool:clean check "-Pkotlin.daemon.jvmargs=-Xmx4g"`; a worktree folder with long paths goes by the `\\?\` delete.

## Next

1. T280's report: a verifier, the merge sequence, the cleanup chore, the log entry.
2. The second sweep (`-Prev out\sweep-20261113-0446`); green: the `release` skill (the notes are drafted in the
   scratchpad, `release/notes-0.1.29.md`, with three placeholders). The release key is refused to task agents;
   the sweep and the draft check run their scripts in the main checkout.
3. The wave's close: README status, the allowance sentence's reading (meter 15 at 23:25 UTC; 1 at planning), the
   Later lines from the Status lines, the record chore (wave 32's record under `docs/tasks/`).

## Waiting on user

- The owner closes whatever window sits in the empty folders `dystopo-wt/T275` and `dystopo-wt/T267-verify`, then
  removes them; and removes the empty `C:\c` folder T264's first tilebuild run left.
- On the phone, v0.1.28-alpha: the profile's Close uncovered (T277's cause was found on an emulated Android 12
  window, not on the phone), and Walk on a town route. The strings to read (ADR 0031) are in the two merge bodies.
- OpenFreeMap's terms cap a download at 3,000 tiles or 50 MB (Later's first entry; the owner emails them).

## Open findings

- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; T273 measured it (the sweep keeps rebooting), cause unproven.
- `docs/findings/2026-11-10-range-cache-keeps-blocks-across-a-republish.md`: 56 stale cells deleted; the validator is T269.
- `docs/findings/2026-11-10-wave-cost-doubled-and-the-lane-width-does-not-explain-it.md`: read the halves at wave 32's close.
