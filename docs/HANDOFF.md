# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-13 18:55 UTC, at commit 4f9b0c58, by main session (T273 merged; T270 and the added T279 launching)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` › Wave 32 (its second paragraph,
"Second half, re-planned 2026-11-13"), `out/wave32-research/critic-wave32-second-half.md` [no-check: gitignored].

## Now

- **v0.1.28-alpha is published** (2026-11-13 11:26 UTC, tag on 2a84ae01): the early release, T276 and T277. The
  sweep before it was green without a rerun (`out/sweep-20261113-0446`, the next release's `-Prev`).
- **T267, T278, T263, T266, T268 and T273 are merged** after the release (their Status lines): all ship in the
  closing alpha. T273's verifier found the sweep tour red after a forced reboot (`-EmuRebootGB 4.5`) on `main` too:
  0-byte elevation tiles from the phone's own store. **T279** (added, lane A: console 5570 / host 8694) takes it;
  **T270** (lane B: console 5574 / host 8698) is the last planned task. Briefs: scratchpad `briefs/`. Meter 8 at
  18:42 UTC. A red `startup` part (T268) is read before any rerun; the closing sweep keeps `-EmuRebootGB 6`.
- The live viewer is wanted (`out/coordinator-pids.txt`). Rules: no force push; a false `:devtool:compileTestKotlin` failure or an out-of-memory Kotlin compile clears with
  `:devtool:clean check "-Pkotlin.daemon.jvmargs=-Xmx4g"`; a worktree folder with long paths goes by the `\\?\` delete.

## Next

1. Each implementer's report gets a Sonnet verifier (`brief` skill, `verifier.md`), then the merge sequence.
2. T270 and T279 run side by side (disjoint Owns; T279 touches no sweep file). The release key is refused
   to task agents: no brief asks for `android-ui.py upgrade`; the closing sweep runs it (T278's kept trips ride on it).
3. The closing sweep (`-Prev out\sweep-20261113-0446`) and alpha, the wave's close (the allowance sentence's reading,
   the record chore).

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
