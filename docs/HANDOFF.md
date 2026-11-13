# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-13 09:00 UTC, at commit e0d40195, by main session (T276 merged; T277 and T267 with verifiers)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` › Wave 32 (its second paragraph,
"Second half, re-planned 2026-11-13"), `out/wave32-research/critic-wave32-second-half.md` [no-check: gitignored].

## Now

- **Wave 32's second half runs** (the owner's go, 2026-11-13): nine tasks in two lanes. **T276 is merged**
  (ec21da97). **T277** (`task/T277`, head e15c3d13) and **T267** (`task/T267`, head 90111f7b, after a fix round)
  are each with a Sonnet verifier. The 7-day meter read 3 at 08:47 UTC (next reset 2026-11-20 04:00 UTC).
- T267's fix round gated the trim and the budget until the kept areas' pins are attached (the cause: `Areas.load()`).
- The live viewer is wanted and runs from the installed distribution (`out/coordinator-pids.txt`).
- Rules: no force push (merge `main` in); `check` can fail falsely at `:devtool:compileTestKotlin` (stale output;
  `:devtool:clean check` with `"-Pkotlin.daemon.jvmargs=-Xmx4g"` clears it); the tour's z8 and z10 views flake on a
  cold emulator beside another build (one rerun before a FIX).

## Next

1. Each verdict: MERGE means the merge sequence (`wave` skill), the cleanup chore and the log entry; FIX goes back.
2. The **early release**: once T277 has merged (and T267, unless it needs another round: then it ships in the
   closing alpha), launch nothing, run the sweep through an agent, publish the alpha (the owner's two fixes).
3. Then lane A: T278 (its brief carries T276's keys and gates, from T276's Status line and merge body), T263,
   T266, T268. Lane B after T267: T273, then T270 only after T263 has merged.
4. The closing sweep and alpha, the wave's close (the allowance sentence's reading, the record chore).

## Waiting on user

- The owner closes whatever window sits in the empty folders `dystopo-wt/T275` and `dystopo-wt/T267-verify`, then
  removes them; and removes the empty `C:\c` folder T264's first tilebuild run left.
- The owner's-eye items (ADR 0031): T276's chip "Walk" and its help text; T277's and T267's strings at their
  merges; the real-phone checks of v0.1.26-alpha and v0.1.27-alpha; T267's ownership pass on a real phone.
- OpenFreeMap's terms cap a download at 3,000 tiles or 50 MB (Later's first entry; the owner emails them).

## Open findings

- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; the snapshot loop is T273.
- `docs/findings/2026-11-10-range-cache-keeps-blocks-across-a-republish.md`: 56 stale cells deleted; the validator is T269.
- `docs/findings/2026-11-10-wave-cost-doubled-and-the-lane-width-does-not-explain-it.md`: read the halves at wave 32's close.
