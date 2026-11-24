---
date: 2026-11-24
kind: feature
related: [2026-11-22-edit-and-resize-a-downloaded-area-and-download-this-area-opens-the-polygon-editor.md, 2026-11-22-a-storage-advisor-that-trades-detail-for-space.md, 2026-11-22-a-much-smaller-detail-level-the-whole-us-in-under-10-gb.md]
---

# Change a saved area's detail and keep its shape

Source: the owner, 2026-11-24, in conversation after the tile-only screenshots: "raise or lower the resolution of a
pack. This requires a (possibly deferred) re-download but preserves the area selection. Seems like an easy implement
to make it very easy to adjust storage usage."

The owner's maps are areas ("Map only", from the direct sources), and an area already has a detail level
(`areas/AreaDetail`: Less, Medium, Full; `AreaDownload.kt` offers `lessDetail` and `mediumDetail` before a download).
After the download the level is fixed: changing it means drawing the shape again. The idea: a row action in Offline
maps (`areas/OfflineMapList.kt`), "Detail", with the levels and the size each would take against today's, keeping
the stored polygon.

- **Lower:** nothing to download; the zooms above the new level are released (`TileCache.clearWhere` over the area's
  rows, pins asked again as the resize idea says), the record's zooms rewritten, the bytes freed shown.
- **Raise:** only the added zooms are fetched (the resize idea's ruling of 2026-11-22: a change fetches the
  difference, never the whole area again), through the resumable download (ADR 0046); the area stays usable at its
  old detail meanwhile, and the row says how far the upgrade has got. "Deferred" is the user's choice to start it now
  or leave it pending on the row with a Start button; never a Wi-Fi gate (ADR 0023).

Lever: the action the storage advisor waits for (its "a way to drop the top zoom of an existing map"), and the
simplest half of the resize idea (the shape unchanged), so it can come first and the editor reuse it.

## Open

- In direct mode, wave 48's cap (T369: 3,000 vector tiles and 50 MB a download) will refuse most raises of a large
  area; the sentence should offer a server or a smaller shape, as the cap's does.
- Whether a pack built by a server gets the same action (a rebuild or a different pack), or areas only, as the resize
  idea decided.

Priority: wave 49, second, after search and peaks from tiles (the owner, 2026-11-24, by multiple choice).

Plan: one implementer in wave 49 (wave 48's T366 and T369 own `OfflineMapList.kt` and `AreaDownload.start`; take
them as those tasks leave them). A "Detail" action on an area's row with Less, Medium and Full, each with its size and
the change against today's (from the plans `AreaDownload` already makes); the stored polygon kept. Lower: release the
area's rows above the new zooms through `TileCache.clearWhere` (tiles another area or a pin holds stay), rewrite the
record's zooms, report the bytes freed. Raise: a download of the added zooms only over the same polygon, through the
resumable path, under T369's cap in direct mode with its sentence; the row shows the area at its old detail with the
upgrade's progress, and "Later" leaves it pending with a Start button. The rule of which tiles go and which come as a
pure function in `shared` with tests; `POST` on the areas' route (`AreaRoutes.kt`) sets a detail and `GET` reports it.
Verify on the emulator with a drawn Mount Rainier area: Full to Less (bytes freed, the map still drawn at the lower
zooms, no request made), then Less to Medium (only the added zooms fetched, by the fetch counters); screenshots of the
row and its sheet viewed; the new strings in the report (ADR 0031). Out of scope: packs, the shape editor, the
storage advisor.
