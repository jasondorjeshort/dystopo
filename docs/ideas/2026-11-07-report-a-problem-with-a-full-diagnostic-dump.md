---
date: 2026-11-07
kind: feature
---

# Report a problem can send a full diagnostic dump, so leaks can be scanned later

The owner, 2026-11-07, after an emulator's guard stop during T229's cold-launch runs: "We do want to test our own
code for leaks though; perhaps we should extend the 'report a problem' click in the app from just crashes to full
log dumps that we could later scan."

Today Report a problem (`androidApp/.../app/reports/`, T145, T148, T228) carries one report per event: a crash, a
freeze, a kill, a startup trace, each in the `dystopo-report 1` format, scrubbed, opened as a GitHub issue of at
most 4,000 characters with the rest on the clipboard. The Diagnostics row's Copy report and `GET /diagnostics`
(T146) give the heap and the counters at one moment, on demand. Nothing records the app's memory over time, and a
release build has no debug routes, so a slow leak on a phone has no trace at all. (The emulator's private-bytes
growth is the qemu host process, finding `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`,
not the app's heap; the app's own figures are what this idea would record.)

## Open

- What the dump holds: a sampled history of heap, native heap and PSS (one line a minute while the app runs),
  the tile and cache counters, the thread count, the startup history, and the app's own logcat lines, bounded in
  size; whether a heap histogram (class counts) can be taken on a release build without a debugger.
- Where it goes: an issue body cannot hold it, so a file the user shares from the system share sheet, or an
  attachment the GitHub flow can take; what `Scrub` must remove from log lines (paths, coordinates, names).
- A scanner on this side: a devtool command that reads a dump and reports growth over time, so a tester's dump
  answers "is it leaking" without a person reading it.
- The trigger: a tap only (the owner's words), never automatic (a new data flow is flagged, not guessed).
