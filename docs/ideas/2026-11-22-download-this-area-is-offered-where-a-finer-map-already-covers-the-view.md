---
date: 2026-11-22
kind: bug
related: [2026-11-22-a-downloaded-map-must-never-make-the-view-worse-than-online.md, 2026-11-22-edit-and-resize-a-downloaded-area-and-download-this-area-opens-the-polygon-editor.md, 2026-11-07-downloads-as-a-synchronisation-in-cells.md]
---

# "Download this area" is offered where a finer offline map already covers the whole view

From the owner (2026-11-22, v0.1.41-alpha, "minor bug"): "download this area" stays an available tap "even when the entire
area is included in another polygon of a high resolution pack. If you do a download it records the area but not for
polygons."

**Where it stands.** The online indicator's button "Download this area" (`online/OnlineOverlay.kt`, T153 and T236) opens
the size-first card for the **visible box** (`areas/AreaPicker.kt`, T237): the view's rectangle, a generated name
(`view-<date>-<n>`), a detail chosen from the zoom (full from a z10 view, medium from z8, less below), and, outside the
pack path's limits, the area path as "a rectangle polygon through the area download" (`docs/reference/android-app.md`).
The status chip beside it already knows when the view is held: "on this phone" when a pack, or a cached tile inside an
offline map on the list, covers it (`Areas.isRecorded`). The button does not read that.

**What is wrong (two readings, the owner's second sentence is unclear).**
- The button should not be offered, or should say so ("Already on this phone"), when every tile the download would fetch
  is held by an open pack or area **at the same detail or finer**. A coarse (Less or Medium) pack under a view that would
  download Full is not covered, so the test compares detail, not only the outline.
- What the download then does: "it records the area but not for polygons" reads either as (a) the download makes a
  new **rectangle** area record while the owner's other maps are **polygons**, so a second row appears that duplicates
  tiles already held, or (b) the record is made and **no polygon is kept** (no outline on the map, no resize, nothing
  fetched because the tiles exist), a row that does nothing. To be reproduced before deciding which.

**The owner's frame (2026-11-22, same phone; a view inside the Medium US pack in the eastern states).** The chip reads
"Online · on this phone", the picker's rectangle is drawn over the view, and the card offers "About 93 MB (1,032 tiles
plus the trail graph) at full detail", "With routing and place search", with Medium (about 46 MB) and Less (about 43 MB)
as links and "Takes under a minute". The owner: "high resolution download within another, but no clear way to see
the resolutions." (The owner's "We only record for now" is about this session: ideas and bugs are recorded, not
investigated; research is later.) So this download is a **legitimate upgrade** (full detail with routing
and search, inside a Medium map-only pack), not a covered view, and the card never says what the phone already holds
there. That fixes the rule above: the button should not disappear for a view that a finer download would improve; the
card should say "On this phone at medium detail, map only" and offer the upgrade, and the "Already saved" case is
only the one where nothing the download takes is new.

**What more we would want to see.**
1. A frame of the view that is wholly inside one pack with the button showing, and the Offline maps list after a tap:
   the new row's name, size and shape (a row with 0 MB, or a duplicate of tiles?). `GET /areas/rows` (debug build) lists
   rows with shape and size.
2. Whether the covering map is a **pack** (the US or Colorado maps are both packs, "Map only", from `direct://`
   sources) or an **area record**: the chip's "on this phone" test (`Areas.isRecorded`) and the picker's size line may
   read different sets. The owner's US and Colorado maps being packs makes this the likelier mismatch.
3. The detail: which pack covers the view and at what zoom range, against the detail the card would choose.

Possible plan (the coordinator's suggestion, not triaged): reproduce on the emulator with a pack installed and the view inside it, then one rule used by the chip's text and
the button: **the view is covered when the finest source asked for each tile of the visible box is an offline map of at
least the detail the download would take**; then the button reads "Already saved" (disabled, with the covering map's
name) or is hidden, and the picker, opened any other way, says "Already on this phone" under the size line. Test:
`Areas` coverage with a pack, an area, both, and a coarser pack under a finer request; the chip and the button agree.
