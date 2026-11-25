---
date: 2026-11-25
kind: bug
related: [2026-10-13-battery-efficiency.md]
---

# No silent work in the background

The owner, 2026-11-25, after the phone session that found the app killed again and again for background CPU: "We can
enable an exception but mostly we don't want to silently be in the background doing work. If we're doing work we want
to be up front about it, possibly with an ongoing notification or etc." Background battery use is the measure.

The case that prompted it: `docs/findings/2026-11-25-tile-cache-index-walk-loops-in-the-background.md` (the online
tile cache's index walk, `TileCache.kt`, restarts at every start with no screen and is killed by Android for CPU, about
2 % to 6 % of the battery a day on the owner's phone). The battery idea it relates to wants a measured budget; this one
is the rule that no work runs unseen.

What it would take: `DystopoApp.onCreate` starts every launch stage whether or not a screen will open; a start with no
activity (a preload, a job, a broadcast) should run only what that start needs. Work that must go on in the background
(recording, area downloads) already shows a notification; anything else either waits for the screen or gets one.

Plan: in wave 49 (the owner, 2026-11-25: "Wave 49"). (1) `TileCache`: no walk until an activity has started, or the
walk under an ongoing notification; the walk writes its progress so a kill resumes it; the snapshot survives a kill
after changes (a journal of changes since its last write, not a discard). (2) A survey of every launch stage for work at
a start without a screen, each one deferred or given a notification. (3) Checked on the owner's phone: no
`excessive cpu` exit and the app near zero in `dumpsys batterystats` over a day left in the background.
