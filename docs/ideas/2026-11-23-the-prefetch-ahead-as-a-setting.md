---
date: 2026-11-23
kind: feature
related: [2026-10-13-auto-download-when-zoomed-in.md]
---

# The prefetch ahead while navigating, as a setting: off, or a distance up to about 10 miles

The owner, 2026-11-23, while wave 48 was planned (the direct-mode cap had just kept the prefetch automatic): "At
some point we should make prefetch an option (0, up to about 10 miles?)".

**Where it stands.** While navigating, the app fetches the map ahead of the walker by itself
(`androidApp/.../app/areas/AheadPrefetch.kt`): every 1 km segment that starts within 3 km of the progress
(`AHEAD_M`), the tiles within 300 m of the line (`BUFFER_M`), zooms 12 and up, pinned; `GET /areas/prefetch`
reports it. The distance is a constant and there is no switch. In direct mode it reads the public sources; wave
48's cap task (the board's draft) leaves it automatic under the limit of four requests at once, by the owner's
answer of the same day.

**What is wanted.** A setting: off (0), or how far ahead, up to about 10 miles (16 km), shown in the user's units.

## Open

- The steps and the default (today's 3 km, about 2 miles), and whether one setting serves hiking and driving: at
  driving speed 3 km is two minutes of road, at walking speed most of an hour.
- What a 16 km window costs in tiles and bytes on a route of each kind, and against the session limit and Mobile
  data in direct mode (ADR 0023: a visible limit, never a Wi-Fi gate).
- Whether it syncs or stays per device (a data-use choice, like `onlineDetail`).

Plan: one small pass after wave 48's cap task has merged. `AHEAD_M` becomes a setting (the prefetch ahead: Off,
and four or five distances up to 16 km in the user's units; the default today's 3 km), per device, in Settings >
Sources beside Mobile data, with help that says what it downloads and when; the window's tiles and bytes for a
hike and a drive at each step measured with `GET /areas/prefetch` and written into the report; a test of the
window at each step and of Off (nothing fetched). The report says why it is a setting (the owner's words above).
