# Handoff

Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
Updated: 2026-11-13 05:45 UTC, at commit 252bde0d, by main session (wave 32's second half launched)
Resume: this file, `docs/reference/machines.md`, the `wave` skill, `docs/TASKS.md` › Wave 32 (its second paragraph,
"Second half, re-planned 2026-11-13"), `out/wave32-research/critic-wave32-second-half.md` [no-check: gitignored].

## Now

- **Wave 32's second half runs** (the owner's go, 2026-11-13; re-planned after a second critic, 252bde0d): nine tasks
  in two lanes. **T276** (lane A) and **T267** (lane B) are with their implementers on `task/T276` and `task/T267`; no
  report yet. The 7-day meter read 1 at the launch (reset 2026-11-13; the next is 2026-11-20 04:00 UTC).
- The live viewer is wanted and runs from the installed distribution (`out/coordinator-pids.txt`).
- Rules: no force push (merge `main` in); a push on its own line; an LZW error in a cached DEM cell is a stale range
  cache (T269); the tour's offline step flaked for T262 and T264 (one rerun before a FIX).

## Next

1. Each implementer's report gets a Sonnet verifier (`brief` skill, `verifier.md`), then the merge sequence.
2. Lane A after T276: **T277**, then the **early release**: once T276 and T277 have merged and lane B's running task
   has merged, launch nothing, run the sweep, publish the alpha (the owner's two priority fixes).
3. Then lane A: T278, T263, T266, T268. Lane B after T267: T273, then T270 only after T263 has merged.
4. The closing sweep and alpha, the wave's close (the allowance sentence's reading, the record chore).

## Waiting on user

- The owner closes whatever window sits in the empty folder `dystopo-wt/T275` (its branch and AVD are gone), then
  removes the folder; and removes the empty `C:\c` folder T264's first tilebuild run left.
- Answered 2026-11-11 (`docs/TASKS.md` › Owner actions pending, "Answered 2026-11-11", and Later's first entry).
- The owner's-eye items of wave 31's merges (ADR 0031, the record's Status lines) and the real-phone checks of
  v0.1.26-alpha and v0.1.27-alpha: the owner reads them "soon enough".
- OpenFreeMap's terms cap each download at 3,000 tiles or 50 MB (Later's first entry; the owner emails them); the
  0054 glacier detail (permanent snow without a mapped glacier) when its ADR is written.

## Open findings

- `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`: guarded; the snapshot loop is T273.
- `docs/findings/2026-11-10-range-cache-keeps-blocks-across-a-republish.md`: 56 stale cells deleted; the validator is T269.
- `docs/findings/2026-11-10-wave-cost-doubled-and-the-lane-width-does-not-explain-it.md`: read the halves at wave 32's close.
