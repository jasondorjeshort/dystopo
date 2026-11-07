---
date: 2026-11-07
status: resolved
area: android / problem reports
---

# Eleven problem reports from the owner's phone fall into five causes; nine are closed, the two startup freezes wait for T242

## Question

Issues 23 to 33 of the repository are the problem reports the owner sent from the app (Settings, About and
advanced, Report a problem). What does each one say, what is its cause where it can be known, and which can be
closed? All eleven come from one phone (Samsung SM-N975U1, Android 12, API 31, 512 MB heap limit).

## What was tried

- `gh issue list --state open` and `gh issue view` on every issue; read against
  `2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md` (it retraced issue 27's stack with the
  release build's `mapping.txt`), `docs/TASKS.md` T242 and its Owner actions, ADR 0058, and the merge commits of
  T148, T149, T150, T152 and T189.
- One comment per issue, closed 2026-11-07 as part of this triage: `completed` where a merged task addressed the
  cause, `not planned` where no cause can be read or there is no defect. Issues 31 and 32 are commented and left
  open until T242 ships.

## Result

| Issue | Kind | Version | Cause | Disposition |
|---|---|---|---|---|
| 23 | Crash, no stack, PSS 672 MB | 0.1.13-alpha | Unknown; no exception or frame in the report | Closed, not planned |
| 24 | ANR, no stack, PSS 614 MB | 0.1.13-alpha | Unknown; same session as 23 and 25, so memory pressure is likely | Closed, not planned (T148 follow-up: a real ANR stack) |
| 25 | Closed for memory, PSS 718 MB | 0.1.13-alpha | Memory pressure from whole-pack graph and index builds | Closed, completed (T149, T150, T152, T189) |
| 26 | Crash | 0.1.13-alpha | Duplicate of 23 (same time, filed twice) | Closed, not planned |
| 27 | Crash, OutOfMemoryError on `dystopo-routing` | 0.1.13-alpha | `RoutingGraphBuilder.assemble` growing an array at the heap limit | Closed, completed (T149, T189) |
| 28 | Crash, no stack, PSS 250 MB | 0.1.13-alpha | Unknown; no exception or frame | Closed, not planned |
| 29 | Crash, OutOfMemoryError on `main` | 0.1.13-alpha | A full heap; the main thread was the victim of a 48-byte allocation | Closed, completed (T149, T152, T189) |
| 30 | Closed for memory, PSS 22 MB | 0.1.15-alpha | The system reclaiming a cached app; not a leak | Closed, not planned (no defect) |
| 31 | Startup freeze, no first frame in 120 s | 0.1.21-alpha | `TileCache.<init>` walking the cache on the main thread | Commented, open until T242 ships |
| 32 | Startup freeze, first frame at 82.35 s | 0.1.21-alpha | The same walk; heap 13 MB of 512 MB, so file stats and not memory | Commented, open until T242 ships |
| 33 | Last start, hot, 0.07 s | 0.1.21-alpha | None; a normal start sent by hand | Closed, not planned (no defect) |

**What the renamed stacks could not tell.** v0.1.13 is before ADR 0058's readable release stacks. Issue 27's was
retraced with the build's mapping (the release `mapping.txt` of that build carries the same map id) and names
`RoutingGraphBuilder`. Issue 29's frames (a string-building call from a main-looper message) were not retraced
and point at no one culprit, which fits an app-wide full heap. Issues 23, 26 and 28 have no stack at all (an
out-of-memory crash that left the handler no memory to write with is a guess, not shown), and issue 24 has no
main-thread stack. Their cause cannot be known; they are closed as not planned, and a repeat on a later build
would name its frame.

**The pattern across the phone.** Eleven reports, five causes:
- Memory pressure of v0.1.13 (23, 24, 25, 26, 27, 28, 29): seven reports, all within two days of 2026-10-25, at
  PSS 250 to 718 MB; addressed by T149 (builds bounded and not started after a download), T150 (the app at rest),
  T152 and T189 (the graph shipped in the pack). No report of that kind has come from a later build.
- A cached app reclaimed at a small footprint (30): one report, normal Android behaviour.
- The startup freeze (31, 32): two reports from v0.1.21-alpha after the overnight download; T242. The reports
  exist because T227 and T228 built the startup trace; the history inside them shows hot starts of a few tenths of
  a second around the freezes.
- A normal start sent by hand (33): one report, no defect.
- Duplicates: 26 repeats 23 (counted above).
