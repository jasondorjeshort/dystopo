# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-13 23:20 UTC, at commit e0a74fc1, by main session (all ten tasks merged; the closing sweep launching)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` › Wave 32 (its second paragraph,
"Second half, re-planned 2026-11-13"), `out/wave32-research/critic-wave32-second-half.md` [no-check: gitignored].

## Now

- **v0.1.28-alpha is published** (2026-11-13 11:26 UTC, tag on 2a84ae01): the early release, T276 and T277. The
  sweep before it was green without a rerun (`out/sweep-20261113-0446`, the next release's `-Prev`).
- **Every task of the wave is merged**: T267, T278, T263, T266, T268, T273, T279 and T270 after the release (their
  Status lines), all shipping in the closing alpha, v0.1.29-alpha. **The closing sweep** runs through a Sonnet agent
  (`-Prev out\sweep-20261113-0446`, default ports): no commit to `main` while it runs (the version code is the
  commit count). New parts since the last green sweep: `startup` (T268) and `a11y` (T270); a red one is read before
  any rerun. The tour's offline step was red once at two verifiers on the world view at z5 (green on retry).
- The live viewer is wanted (`out/coordinator-pids.txt`). Rules: no force push; a false `:devtool:compileTestKotlin` failure or an out-of-memory Kotlin compile clears with
  `:devtool:clean check "-Pkotlin.daemon.jvmargs=-Xmx4g"`; a worktree folder with long paths goes by the `\\?\` delete.

## Next

1. The sweep's summary and sheets read; green: the `release` skill (cut the changelog, tag v0.1.29-alpha, the
   draft checked by a Sonnet agent, notes after `scratchpad/release/notes.md`, publish, `gh release view`).
2. The release key is refused to task agents; the sweep and the draft check run their scripts in the main checkout.
3. The wave's close: README status, the allowance sentence's reading (meter 13 at 20:31 UTC; 1 at planning), the
   Later lines from the Status lines, the record chore (`docs/tasks/wave-32.md`).

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
