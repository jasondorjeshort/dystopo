---
date: 2026-10-26
status: open
area: android / routing / memory
---

# The routing graph for a vector pack is built whole in memory on the phone and runs out of a 512 MB heap

## Question

The owner's phone filed five problem reports from v0.1.13-alpha in two days (issues 23 to 27): an ANR, a close
for memory, two copies of one crash, and an `OutOfMemoryError` on the `dystopo-routing` thread. What do they
share, and what is the one with a stack?

## What was tried

- `gh issue view` on all five. Four distinct events (issues 23 and 26 are the same crash, filed twice, same
  timestamp): an ANR with the user closing it (PSS 614 MB), the system closing the app for memory (PSS 718 MB,
  about 16 minutes later), a crash with no stack (PSS 672 MB, an hour after that), and the next day an
  `OutOfMemoryError`. The phone runs Android 12 with a 512 MB growth limit.
- The OOM's stack is R8-obfuscated (`r8-map-id-b17e28cd...`). The release build's `mapping.txt` on the second PC
  carries that same id, so it was retraced with the SDK's `retrace` (the plain mapping lookup does not match:
  R8 positions are compact).

## Result

- The memory is the common thread: three of the four events are at PSS 614 to 718 MB, and the fourth is an OOM at
  the heap's limit ("Failed to allocate a 2097168 byte allocation with 1075112 free bytes ... growth limit
  536870912").
- The OOM retraces to `RoutingGraphBuilder.assemble` (`RoutingGraphBuilder.kt:285`, the crossings step, an
  `Arrays.copyOf` growing an array) called from `RoutingGraphBuilder.build`, `RoutePlanner.run`
  (`RoutePlanner.kt:235`) and `ensureRegion` on the `dystopo-routing` thread. So the phone was building a
  routing graph for a whole vector pack in one pass, with the snapped vertices and every line held in arrays,
  while the rest of the app already held several hundred MB.
- Not known: which pack, and what asked for the build (a route, the drive overlay, or a pack that changed). The
  report removes pack names, and the no-stack crash and the ANR carry no trace to compare. That the build follows
  a large region download is a guess.

## Next

- Bound the build by the heap: build in tiles or strips and write as it goes, or refuse with a sentence past a
  size, as the area guard does (`shared/.../areas/AreaLimit.kt`).
- The new report (a crash on leaving airplane mode) is not yet filed; its stack says whether the connectivity
  change starts a build or a download.
- The unwritten trace of the crash in issues 23 and 26 may itself be an OOM that left the handler no memory to
  write with (a guess).
